package com.briefy.domain.delivery.service;

import com.briefy.domain.briefing.entity.BriefingReport;
import com.briefy.domain.briefing.repository.BriefingReportRepository;
import com.briefy.domain.delivery.entity.DeliveryLog;
import com.briefy.domain.delivery.entity.DeliveryStatus;
import com.briefy.domain.delivery.repository.DeliveryLogRepository;
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
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmailDeliveryService {

  private static final Logger log = LoggerFactory.getLogger(EmailDeliveryService.class);

  private final DeliveryLogRepository deliveryLogRepository;
  private final BriefingReportRepository briefingReportRepository;
  private final UserRepository userRepository;
  private final EmailSender emailSender;

  public EmailDeliveryService(
      DeliveryLogRepository deliveryLogRepository,
      BriefingReportRepository briefingReportRepository,
      UserRepository userRepository,
      EmailSender emailSender) {
    this.deliveryLogRepository = deliveryLogRepository;
    this.briefingReportRepository = briefingReportRepository;
    this.userRepository = userRepository;
    this.emailSender = emailSender;
  }

  /**
   * Scheduler-only path: re-checks briefingEmailEnabled immediately before sending to guard against
   * subscription changes between scheduling and delivery.
   */
  @Transactional(noRollbackFor = Exception.class)
  public DeliveryLog autoDeliverBriefingReport(Long reportId) {
    BriefingReport report =
        briefingReportRepository
            .findById(reportId)
            .orElseThrow(() -> new BusinessException(ErrorCode.BRIEFING_REPORT_NOT_FOUND));

    User user =
        userRepository
            .findById(report.getUserId())
            .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

    if (!user.isBriefingEmailEnabled()) {
      log.info(
          "Skipping auto email for user {} (reportId={}) — briefing email subscription is disabled",
          user.getId(),
          reportId);
      return null;
    }

    return deliverBriefingReport(reportId);
  }

  /**
   * noRollbackFor ensures the FAILED status is committed to the DB even when an exception
   * propagates, so the delivery failure is always visible for auditing.
   */
  @Transactional(noRollbackFor = Exception.class)
  public DeliveryLog deliverBriefingReport(Long reportId) {
    // 이미 SENT 상태인 로그가 있으면 중복 발송 방지
    if (deliveryLogRepository.existsByBriefingReportIdAndStatus(reportId, DeliveryStatus.SENT)) {
      log.info("Email already delivered for reportId={} — skipping", reportId);
      return deliveryLogRepository
          .findByBriefingReportId(reportId)
          .orElseThrow(() -> new BusinessException(ErrorCode.EMAIL_ALREADY_DELIVERED));
    }

    BriefingReport report =
        briefingReportRepository
            .findById(reportId)
            .orElseThrow(() -> new BusinessException(ErrorCode.BRIEFING_REPORT_NOT_FOUND));

    User user =
        userRepository
            .findById(report.getUserId())
            .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

    String toEmail =
        (user.getReportEmail() != null && !user.getReportEmail().isBlank())
            ? user.getReportEmail()
            : user.getEmail();

    DeliveryLog deliveryLog =
        DeliveryLog.createPending(report.getUserId(), reportId, toEmail, report.getTitle());
    deliveryLogRepository.save(deliveryLog);

    try {
      String htmlContent = MarkdownToHtmlConverter.convert(report.getContent());
      EmailMessage message = new EmailMessage(toEmail, report.getTitle(), htmlContent);
      EmailSendResult result = emailSender.send(message);
      if (result.success()) {
        deliveryLog.markSent(result.providerMessageId());
      } else {
        deliveryLog.markFailed(result.errorMessage());
      }
    } catch (Exception e) {
      deliveryLog.markFailed(e.getMessage() != null ? e.getMessage() : "Unknown error");
    }

    return deliveryLog;
  }
}
