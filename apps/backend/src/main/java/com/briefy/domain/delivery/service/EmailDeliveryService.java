package com.briefy.domain.delivery.service;

import com.briefy.domain.briefing.entity.BriefingReport;
import com.briefy.domain.briefing.repository.BriefingReportRepository;
import com.briefy.domain.delivery.entity.DeliveryLog;
import com.briefy.domain.delivery.entity.DeliveryStatus;
import com.briefy.domain.user.entity.User;
import com.briefy.domain.user.repository.UserRepository;
import com.briefy.global.email.EmailMessage;
import com.briefy.global.email.EmailSendResult;
import com.briefy.global.email.EmailSender;
import com.briefy.global.email.MarkdownToHtmlConverter;
import com.briefy.global.exception.BusinessException;
import com.briefy.global.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 이메일 발송 서비스.
 *
 * <h2>멱등성 / 상태 전이</h2>
 *
 * <pre>
 * PENDING ──claimForSending──▶ SENDING ──send()──▶ SENT
 *                                               ╰──▶ FAILED
 * FAILED  ──claimForSending──▶ SENDING  (관리자 재시도)
 * SENT    ──(skip)──▶ SENT    (중복 발송 방지)
 * SENDING ──(skip)──▶ SENDING (동시 요청 처리 중)
 * </pre>
 *
 * <h2>트랜잭션 경계</h2>
 *
 * <p>이 클래스의 public 메서드는 @Transactional 없음. 모든 DB 조작은 {@link
 * DeliveryLogPersistenceService}(REQUIRES_NEW)에 위임하며, EmailSender 호출은 트랜잭션 밖에서 실행된다.
 *
 * <h2>재시도</h2>
 *
 * <p>EmailSendResult.retryable=true인 일시적 오류에 대해 최대 {@value #EMAIL_MAX_RETRIES}회 재시도. 영구 오류
 * (retryable=false)는 즉시 FAILED 처리.
 *
 * <h2>SES 모호 구간</h2>
 *
 * <p>SES가 요청을 수락한 직후 DB SENT 갱신 전 프로세스가 종료되면 DeliveryLog가 SENDING 상태로 남는다. 재시작 시 FAILED로 초기화하고
 * 재시도하므로 중복 발송이 발생할 수 있다. exactly-once는 보장하지 않는다.
 */
@Service
public class EmailDeliveryService {

  private static final Logger log = LoggerFactory.getLogger(EmailDeliveryService.class);

  /** 일시적 오류 시 최대 재시도 횟수 (최초 시도 포함하면 최대 3회 발송 시도) */
  static final int EMAIL_MAX_RETRIES = 2;

  /** 재시도 간격 (밀리초). 테스트에서 0으로 덮어써서 대기시간 없이 실행 가능. */
  long emailRetryBackoffMs = 3_000L;

  private final DeliveryLogPersistenceService deliveryLogPersistenceService;
  private final BriefingReportRepository briefingReportRepository;
  private final UserRepository userRepository;
  private final EmailSender emailSender;

  public EmailDeliveryService(
      DeliveryLogPersistenceService deliveryLogPersistenceService,
      BriefingReportRepository briefingReportRepository,
      UserRepository userRepository,
      EmailSender emailSender) {
    this.deliveryLogPersistenceService = deliveryLogPersistenceService;
    this.briefingReportRepository = briefingReportRepository;
    this.userRepository = userRepository;
    this.emailSender = emailSender;
  }

  /**
   * 스케줄러 경로: 구독 재확인 후 DeliveryLog를 멱등적으로 생성하여 발송.
   *
   * <p>PENDING 상태일 때만 발송한다. 이미 SENT/SENDING/FAILED인 경우 현재 상태를 반환한다.
   */
  public DeliveryLog autoDeliverBriefingReport(Long reportId) {
    BriefingReport report = findReport(reportId);
    User user = findUser(report.getUserId());

    if (!user.isBriefingEmailEnabled()) {
      log.info(
          "Skipping auto email userId={} reportId={} — subscription disabled",
          user.getId(),
          reportId);
      return null;
    }

    String toEmail = resolveEmail(user);
    DeliveryLog deliveryLog =
        deliveryLogPersistenceService.createOrGet(
            reportId, user.getId(), toEmail, report.getTitle());

    if (deliveryLog.getStatus() != DeliveryStatus.PENDING) {
      log.info(
          "DeliveryLog {} for reportId={} is {} — skipping auto delivery",
          deliveryLog.getId(),
          reportId,
          deliveryLog.getStatus());
      return deliveryLog;
    }

    return executeDelivery(deliveryLog, user, report);
  }

  /**
   * 관리자 API 경로: 멱등적으로 이메일 발송.
   *
   * <ul>
   *   <li>DeliveryLog 없음 → PENDING 생성 후 발송
   *   <li>PENDING/FAILED → SENDING 선점 후 발송
   *   <li>SENDING → 이미 처리 중 (중복 발송 없음), 현재 상태 반환
   *   <li>SENT → 재발송 없이 기존 성공 결과 반환
   * </ul>
   */
  public DeliveryLog deliverBriefingReport(Long reportId) {
    BriefingReport report = findReport(reportId);
    User user = findUser(report.getUserId());

    String toEmail = resolveEmail(user);

    // 멱등 생성: UNIQUE 충돌 시 기존 로그 재조회
    DeliveryLog deliveryLog =
        deliveryLogPersistenceService.createOrGet(
            reportId, user.getId(), toEmail, report.getTitle());

    return switch (deliveryLog.getStatus()) {
      case PENDING, FAILED -> executeDelivery(deliveryLog, user, report);
      case SENDING -> {
        log.info(
            "DeliveryLog {} is SENDING — concurrent request in progress, reportId={}",
            deliveryLog.getId(),
            reportId);
        yield deliveryLog;
      }
      case SENT -> {
        log.info("Email already SENT for reportId={}", reportId);
        yield deliveryLog;
      }
    };
  }

  /**
   * 공통 발송 실행: SENDING 선점 → EmailSender 호출 → 상태 저장.
   *
   * <p>이 메서드는 @Transactional 없음. EmailSender.send()는 항상 TX 밖에서 호출된다.
   */
  private DeliveryLog executeDelivery(DeliveryLog deliveryLog, User user, BriefingReport report) {
    if (!user.isBriefingEmailEnabled()) {
      log.info(
          "Skipping delivery userId={} reportId={} — subscription disabled at send time",
          user.getId(),
          report.getId());
      return deliveryLog;
    }

    // PENDING/FAILED → SENDING 조건부 선점 (REQUIRES_NEW TX)
    boolean claimed = deliveryLogPersistenceService.claimForSending(deliveryLog.getId());
    if (!claimed) {
      log.info(
          "DeliveryLog {} already claimed by concurrent request, reportId={}",
          deliveryLog.getId(),
          report.getId());
      return deliveryLogPersistenceService.findByReportId(report.getId()).orElse(deliveryLog);
    }

    String toEmail = deliveryLog.getToEmail();
    try {
      String htmlContent = MarkdownToHtmlConverter.convert(report.getContent());
      EmailMessage message = new EmailMessage(toEmail, report.getTitle(), htmlContent);

      // TX 없이 이메일 발송 (재시도 포함)
      EmailSendResult result = sendWithRetry(deliveryLog.getId(), message);

      if (result.success()) {
        deliveryLogPersistenceService.markSent(deliveryLog.getId(), result.providerMessageId());
        log.info(
            "Email sent successfully deliveryLogId={} reportId={} providerMessageId={}",
            deliveryLog.getId(),
            report.getId(),
            result.providerMessageId());
      } else {
        deliveryLogPersistenceService.markFailed(deliveryLog.getId(), result.errorMessage());
        log.warn(
            "Email delivery failed deliveryLogId={} reportId={} error={}",
            deliveryLog.getId(),
            report.getId(),
            result.errorMessage());
      }
    } catch (Exception e) {
      deliveryLogPersistenceService.markFailed(
          deliveryLog.getId(), "unexpected:" + e.getClass().getSimpleName());
      log.error(
          "Unexpected error during email delivery deliveryLogId={} reportId={}",
          deliveryLog.getId(),
          report.getId(),
          e);
    }

    return deliveryLogPersistenceService.findByReportId(report.getId()).orElse(deliveryLog);
  }

  /**
   * 재시도 루프.
   *
   * <p>EmailSendResult.retryable=true인 경우에만 재시도. 영구 오류는 즉시 종료.
   *
   * <p>주의: SES 요청 수락 직후 DB SENT 갱신 전 프로세스 종료 시 DeliveryLog가 SENDING 상태로 남을 수 있음. 이 모호한 구간은 애플리케이션
   * 수준에서 해소 불가. exactly-once를 보장하지 않음.
   */
  private EmailSendResult sendWithRetry(Long deliveryLogId, EmailMessage message) {
    EmailSendResult result = null;
    for (int attempt = 0; attempt <= EMAIL_MAX_RETRIES; attempt++) {
      if (attempt > 0) {
        deliveryLogPersistenceService.incrementRetry(deliveryLogId);
        try {
          Thread.sleep(emailRetryBackoffMs);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          return result != null ? result : EmailSendResult.fail("retry_interrupted");
        }
      }
      result = emailSender.send(message);
      if (result.success() || !result.retryable()) {
        break;
      }
      log.warn(
          "[Email] Attempt {} failed (retryable=true) deliveryLogId={} error={}",
          attempt + 1,
          deliveryLogId,
          result.errorMessage());
    }
    return result != null ? result : EmailSendResult.fail("retry_exhausted");
  }

  private BriefingReport findReport(Long reportId) {
    return briefingReportRepository
        .findById(reportId)
        .orElseThrow(() -> new BusinessException(ErrorCode.BRIEFING_REPORT_NOT_FOUND));
  }

  private User findUser(Long userId) {
    return userRepository
        .findById(userId)
        .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
  }

  private String resolveEmail(User user) {
    return (user.getReportEmail() != null && !user.getReportEmail().isBlank())
        ? user.getReportEmail()
        : user.getEmail();
  }
}
