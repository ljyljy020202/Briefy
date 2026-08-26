package com.briefy.domain.delivery.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.briefy.domain.briefing.entity.BriefingReport;
import com.briefy.domain.briefing.repository.BriefingReportRepository;
import com.briefy.domain.delivery.entity.DeliveryLog;
import com.briefy.domain.delivery.entity.DeliveryStatus;
import com.briefy.domain.user.entity.User;
import com.briefy.domain.user.repository.UserRepository;
import com.briefy.global.email.EmailSendResult;
import com.briefy.global.email.EmailSender;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 재시도 / 멱등성 / 경계 케이스 테스트.
 *
 * <p>재시도 시 Thread.sleep을 건너뛰기 위해 EMAIL_RETRY_BACKOFF_MS를 0으로 설정.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmailDeliveryRetryTest {

  @Mock private DeliveryLogPersistenceService deliveryLogPersistenceService;
  @Mock private BriefingReportRepository briefingReportRepository;
  @Mock private UserRepository userRepository;
  @Mock private EmailSender emailSender;

  private EmailDeliveryService emailDeliveryService;

  private BriefingReport report;
  private User user;
  private DeliveryLog pendingLog;

  private static final Long REPORT_ID = 10L;
  private static final Long USER_ID = 1L;
  private static final Long LOG_ID = 100L;

  @BeforeEach
  void setUp() {
    emailDeliveryService =
        new EmailDeliveryService(
            deliveryLogPersistenceService, briefingReportRepository, userRepository, emailSender);
    // 재시도 대기시간 0으로 설정 (테스트 속도 향상)
    ReflectionTestUtils.setField(emailDeliveryService, "emailRetryBackoffMs", 0L);

    report = mock(BriefingReport.class);
    when(report.getId()).thenReturn(REPORT_ID);
    when(report.getUserId()).thenReturn(USER_ID);
    lenient().when(report.getTitle()).thenReturn("오늘의 채용 브리핑");
    lenient().when(report.getContent()).thenReturn("## 내용");
    when(briefingReportRepository.findById(REPORT_ID)).thenReturn(Optional.of(report));

    user = mock(User.class);
    lenient().when(user.getId()).thenReturn(USER_ID);
    lenient().when(user.getEmail()).thenReturn("user@example.com");
    lenient().when(user.isBriefingEmailEnabled()).thenReturn(true);
    when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

    pendingLog = mock(DeliveryLog.class);
    when(pendingLog.getId()).thenReturn(LOG_ID);
    when(pendingLog.getStatus()).thenReturn(DeliveryStatus.PENDING);
    when(pendingLog.getToEmail()).thenReturn("user@example.com");
  }

  // ── 일시 오류 후 재시도 성공 ──────────────────────────────────────────────

  @Test
  void transientError_retried_success() {
    when(deliveryLogPersistenceService.createOrGet(anyLong(), anyLong(), anyString(), anyString()))
        .thenReturn(pendingLog);
    when(deliveryLogPersistenceService.claimForSending(LOG_ID)).thenReturn(true);
    // 1회 일시 오류 후 성공
    when(emailSender.send(any()))
        .thenReturn(EmailSendResult.transientFail("throttling"))
        .thenReturn(EmailSendResult.ok("msg-ok"));

    DeliveryLog sentLog = mock(DeliveryLog.class);
    when(sentLog.getStatus()).thenReturn(DeliveryStatus.SENT);
    when(deliveryLogPersistenceService.findByReportId(REPORT_ID)).thenReturn(Optional.of(sentLog));

    DeliveryLog result = emailDeliveryService.deliverBriefingReport(REPORT_ID);

    assertThat(result.getStatus()).isEqualTo(DeliveryStatus.SENT);
    verify(emailSender, times(2)).send(any());
    // retry_count 1 증가
    verify(deliveryLogPersistenceService, times(1)).incrementRetry(LOG_ID);
    verify(deliveryLogPersistenceService).markSent(LOG_ID, "msg-ok");
  }

  // ── 재시도 소진 → FAILED ──────────────────────────────────────────────────

  @Test
  void transientError_exhausted_failed() {
    when(deliveryLogPersistenceService.createOrGet(anyLong(), anyLong(), anyString(), anyString()))
        .thenReturn(pendingLog);
    when(deliveryLogPersistenceService.claimForSending(LOG_ID)).thenReturn(true);
    // 3회 모두 일시 오류 (최초 1회 + 재시도 2회 = EMAIL_MAX_RETRIES + 1 = 3)
    when(emailSender.send(any())).thenReturn(EmailSendResult.transientFail("throttling"));

    DeliveryLog failedLog = mock(DeliveryLog.class);
    when(failedLog.getStatus()).thenReturn(DeliveryStatus.FAILED);
    when(deliveryLogPersistenceService.findByReportId(REPORT_ID))
        .thenReturn(Optional.of(failedLog));

    DeliveryLog result = emailDeliveryService.deliverBriefingReport(REPORT_ID);

    assertThat(result.getStatus()).isEqualTo(DeliveryStatus.FAILED);
    // 최대 (EMAIL_MAX_RETRIES + 1) = 3회 시도
    verify(emailSender, times(EmailDeliveryService.EMAIL_MAX_RETRIES + 1)).send(any());
    // retry_count는 재시도 횟수만큼 증가 (첫 시도 제외)
    verify(deliveryLogPersistenceService, times(EmailDeliveryService.EMAIL_MAX_RETRIES))
        .incrementRetry(LOG_ID);
    verify(deliveryLogPersistenceService).markFailed(anyLong(), anyString());
  }

  // ── 영구 오류 → 추가 시도 없이 FAILED ─────────────────────────────────────

  @Test
  void permanentError_noRetry_failed() {
    when(deliveryLogPersistenceService.createOrGet(anyLong(), anyLong(), anyString(), anyString()))
        .thenReturn(pendingLog);
    when(deliveryLogPersistenceService.claimForSending(LOG_ID)).thenReturn(true);
    when(emailSender.send(any())).thenReturn(EmailSendResult.fail("ses_permanent:MessageRejected"));

    DeliveryLog failedLog = mock(DeliveryLog.class);
    when(failedLog.getStatus()).thenReturn(DeliveryStatus.FAILED);
    when(deliveryLogPersistenceService.findByReportId(REPORT_ID))
        .thenReturn(Optional.of(failedLog));

    DeliveryLog result = emailDeliveryService.deliverBriefingReport(REPORT_ID);

    assertThat(result.getStatus()).isEqualTo(DeliveryStatus.FAILED);
    // 영구 오류 → 재시도 없음 (1회만 호출)
    verify(emailSender, times(1)).send(any());
    verify(deliveryLogPersistenceService, never()).incrementRetry(anyLong());
    verify(deliveryLogPersistenceService).markFailed(anyLong(), anyString());
  }

  // ── SENT 재호출 → sender 호출 없음 ──────────────────────────────────────

  @Test
  void sentRedelivery_senderNotCalled() {
    DeliveryLog sentLog = mock(DeliveryLog.class);
    when(sentLog.getId()).thenReturn(LOG_ID);
    when(sentLog.getStatus()).thenReturn(DeliveryStatus.SENT);
    when(deliveryLogPersistenceService.createOrGet(anyLong(), anyLong(), anyString(), anyString()))
        .thenReturn(sentLog);

    DeliveryLog result = emailDeliveryService.deliverBriefingReport(REPORT_ID);

    verify(emailSender, never()).send(any());
    assertThat(result.getStatus()).isEqualTo(DeliveryStatus.SENT);
  }

  // ── FAILED 관리자 재시도 → 동일 DeliveryLog 사용 ────────────────────────

  @Test
  void adminRetry_reusesSameDeliveryLog() {
    DeliveryLog failedLog = mock(DeliveryLog.class);
    when(failedLog.getId()).thenReturn(LOG_ID);
    when(failedLog.getStatus()).thenReturn(DeliveryStatus.FAILED);
    when(failedLog.getToEmail()).thenReturn("user@example.com");
    when(deliveryLogPersistenceService.createOrGet(anyLong(), anyLong(), anyString(), anyString()))
        .thenReturn(failedLog);
    when(deliveryLogPersistenceService.claimForSending(LOG_ID)).thenReturn(true);
    when(emailSender.send(any())).thenReturn(EmailSendResult.ok("msg-retry"));

    DeliveryLog sentLog = mock(DeliveryLog.class);
    when(sentLog.getStatus()).thenReturn(DeliveryStatus.SENT);
    when(deliveryLogPersistenceService.findByReportId(REPORT_ID)).thenReturn(Optional.of(sentLog));

    DeliveryLog result = emailDeliveryService.deliverBriefingReport(REPORT_ID);

    assertThat(result.getStatus()).isEqualTo(DeliveryStatus.SENT);
    // 새 DeliveryLog가 아닌 기존 LOG_ID에 대해 markSent 호출
    verify(deliveryLogPersistenceService).markSent(LOG_ID, "msg-retry");
  }

  // ── 발송 직전 구독 해지 → 발송 없음 ─────────────────────────────────────

  @Test
  void subscriptionCancelledBeforeSend_skipped() {
    when(deliveryLogPersistenceService.findByReportId(REPORT_ID))
        .thenReturn(Optional.of(pendingLog));
    // 스케줄러 경로에서 구독 비활성화 확인 후 건너뜀
    when(user.isBriefingEmailEnabled()).thenReturn(false);

    DeliveryLog result = emailDeliveryService.autoDeliverBriefingReport(REPORT_ID);

    assertThat(result).isNull();
    verify(emailSender, never()).send(any());
  }

  // ── 이메일 실패 후 Report와 BriefingJob 성공 상태 유지 ────────────────────

  @Test
  void emailFailure_reportAndJobPreserved() {
    when(deliveryLogPersistenceService.createOrGet(anyLong(), anyLong(), anyString(), anyString()))
        .thenReturn(pendingLog);
    when(deliveryLogPersistenceService.claimForSending(LOG_ID)).thenReturn(true);
    when(emailSender.send(any())).thenReturn(EmailSendResult.fail("ses_permanent:error"));

    DeliveryLog failedLog = mock(DeliveryLog.class);
    when(failedLog.getStatus()).thenReturn(DeliveryStatus.FAILED);
    when(deliveryLogPersistenceService.findByReportId(REPORT_ID))
        .thenReturn(Optional.of(failedLog));

    DeliveryLog result = emailDeliveryService.deliverBriefingReport(REPORT_ID);

    assertThat(result.getStatus()).isEqualTo(DeliveryStatus.FAILED);
    // BriefingReport는 이메일 실패와 무관하게 변경되지 않는다
    verify(briefingReportRepository, never()).delete(any());
    verify(briefingReportRepository, never()).save(any());
  }

  // ── provider_message_id, sent_at, retry_count, last_attempt_at 저장 검증 ─

  @Test
  void sentMetadataPersistedCorrectly() {
    when(deliveryLogPersistenceService.createOrGet(anyLong(), anyLong(), anyString(), anyString()))
        .thenReturn(pendingLog);
    when(deliveryLogPersistenceService.claimForSending(LOG_ID)).thenReturn(true);
    when(emailSender.send(any())).thenReturn(EmailSendResult.ok("ses-msg-12345"));

    DeliveryLog sentLog = mock(DeliveryLog.class);
    when(sentLog.getStatus()).thenReturn(DeliveryStatus.SENT);
    when(sentLog.getProviderMessageId()).thenReturn("ses-msg-12345");
    when(deliveryLogPersistenceService.findByReportId(REPORT_ID)).thenReturn(Optional.of(sentLog));

    DeliveryLog result = emailDeliveryService.deliverBriefingReport(REPORT_ID);

    assertThat(result.getStatus()).isEqualTo(DeliveryStatus.SENT);
    assertThat(result.getProviderMessageId()).isEqualTo("ses-msg-12345");
    // markSent가 provider_message_id와 함께 호출되었는지 확인
    verify(deliveryLogPersistenceService).markSent(LOG_ID, "ses-msg-12345");
  }
}
