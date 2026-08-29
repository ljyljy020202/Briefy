package com.briefy.domain.delivery.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EmailDeliveryServiceTest {

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

  // ── deliverBriefingReport (관리자 API 경로) ──────────────────────────────

  @Test
  void deliverBriefingReport_success_marksLogAsSent() {
    when(deliveryLogPersistenceService.createOrGet(anyLong(), anyLong(), anyString(), anyString()))
        .thenReturn(pendingLog);
    when(deliveryLogPersistenceService.claimForSending(LOG_ID)).thenReturn(true);
    when(emailSender.send(any())).thenReturn(EmailSendResult.ok("msg-abc-123"));

    DeliveryLog sentLog = mock(DeliveryLog.class);
    when(sentLog.getStatus()).thenReturn(DeliveryStatus.SENT);
    when(sentLog.getProviderMessageId()).thenReturn("msg-abc-123");
    when(deliveryLogPersistenceService.findByReportId(REPORT_ID)).thenReturn(Optional.of(sentLog));

    DeliveryLog result = emailDeliveryService.deliverBriefingReport(REPORT_ID);

    assertThat(result.getStatus()).isEqualTo(DeliveryStatus.SENT);
    verify(deliveryLogPersistenceService).markSent(LOG_ID, "msg-abc-123");
  }

  @Test
  void deliverBriefingReport_senderReturnsFail_marksLogAsFailed() {
    when(deliveryLogPersistenceService.createOrGet(anyLong(), anyLong(), anyString(), anyString()))
        .thenReturn(pendingLog);
    when(deliveryLogPersistenceService.claimForSending(LOG_ID)).thenReturn(true);
    when(emailSender.send(any())).thenReturn(EmailSendResult.fail("Connection refused"));

    DeliveryLog failedLog = mock(DeliveryLog.class);
    when(failedLog.getStatus()).thenReturn(DeliveryStatus.FAILED);
    when(failedLog.getErrorMessage()).thenReturn("Connection refused");
    when(deliveryLogPersistenceService.findByReportId(REPORT_ID))
        .thenReturn(Optional.of(failedLog));

    DeliveryLog result = emailDeliveryService.deliverBriefingReport(REPORT_ID);

    assertThat(result.getStatus()).isEqualTo(DeliveryStatus.FAILED);
    verify(deliveryLogPersistenceService).markFailed(LOG_ID, "Connection refused");
  }

  @Test
  void deliverBriefingReport_senderThrows_marksLogAsFailed() {
    when(deliveryLogPersistenceService.createOrGet(anyLong(), anyLong(), anyString(), anyString()))
        .thenReturn(pendingLog);
    when(deliveryLogPersistenceService.claimForSending(LOG_ID)).thenReturn(true);
    when(emailSender.send(any())).thenThrow(new RuntimeException("Network error"));

    DeliveryLog failedLog = mock(DeliveryLog.class);
    when(failedLog.getStatus()).thenReturn(DeliveryStatus.FAILED);
    when(deliveryLogPersistenceService.findByReportId(REPORT_ID))
        .thenReturn(Optional.of(failedLog));

    DeliveryLog result = emailDeliveryService.deliverBriefingReport(REPORT_ID);

    assertThat(result.getStatus()).isEqualTo(DeliveryStatus.FAILED);
    verify(deliveryLogPersistenceService).markFailed(anyLong(), anyString());
  }

  @Test
  void deliverBriefingReport_usesReportEmailOverLoginEmail_whenSet() {
    when(user.getReportEmail()).thenReturn("report@example.com");
    when(deliveryLogPersistenceService.createOrGet(anyLong(), anyLong(), anyString(), anyString()))
        .thenAnswer(
            inv -> {
              // 세 번째 파라미터(toEmail)가 report@example.com인지 확인
              String toEmail = inv.getArgument(2);
              assertThat(toEmail).isEqualTo("report@example.com");
              return pendingLog;
            });
    when(deliveryLogPersistenceService.claimForSending(LOG_ID)).thenReturn(true);
    when(pendingLog.getToEmail()).thenReturn("report@example.com");
    when(emailSender.send(any())).thenReturn(EmailSendResult.ok("msg-xyz"));

    DeliveryLog sentLog = mock(DeliveryLog.class);
    when(sentLog.getStatus()).thenReturn(DeliveryStatus.SENT);
    when(deliveryLogPersistenceService.findByReportId(REPORT_ID)).thenReturn(Optional.of(sentLog));

    DeliveryLog result = emailDeliveryService.deliverBriefingReport(REPORT_ID);

    assertThat(result.getStatus()).isEqualTo(DeliveryStatus.SENT);
  }

  @Test
  void deliverBriefingReport_briefingSuccess_doesNotAffectReport() {
    when(deliveryLogPersistenceService.createOrGet(anyLong(), anyLong(), anyString(), anyString()))
        .thenReturn(pendingLog);
    when(deliveryLogPersistenceService.claimForSending(LOG_ID)).thenReturn(true);
    when(emailSender.send(any())).thenThrow(new RuntimeException("SES down"));

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

  // ── autoDeliverBriefingReport (스케줄러 경로) ────────────────────────────

  @Test
  void autoDeliverBriefingReport_sendsEmail_whenSubscriptionEnabled() {
    when(deliveryLogPersistenceService.createOrGet(anyLong(), anyLong(), anyString(), anyString()))
        .thenReturn(pendingLog);
    when(deliveryLogPersistenceService.claimForSending(LOG_ID)).thenReturn(true);
    when(emailSender.send(any())).thenReturn(EmailSendResult.ok("msg-auto-123"));

    DeliveryLog sentLog = mock(DeliveryLog.class);
    when(sentLog.getStatus()).thenReturn(DeliveryStatus.SENT);
    when(deliveryLogPersistenceService.findByReportId(REPORT_ID)).thenReturn(Optional.of(sentLog));

    DeliveryLog result = emailDeliveryService.autoDeliverBriefingReport(REPORT_ID);

    assertThat(result.getStatus()).isEqualTo(DeliveryStatus.SENT);
  }

  @Test
  void autoDeliverBriefingReport_skipsEmail_whenSubscriptionDisabled() {
    when(user.isBriefingEmailEnabled()).thenReturn(false);

    DeliveryLog result = emailDeliveryService.autoDeliverBriefingReport(REPORT_ID);

    assertThat(result).isNull();
    verify(emailSender, never()).send(any());
  }

  @Test
  void autoDeliverBriefingReport_noExistingLog_createsLogAndSends() {
    // DeliveryLog가 없을 때 createOrGet이 새 PENDING 로그를 만들고 발송까지 진행한다
    when(deliveryLogPersistenceService.createOrGet(anyLong(), anyLong(), anyString(), anyString()))
        .thenReturn(pendingLog);
    when(deliveryLogPersistenceService.claimForSending(LOG_ID)).thenReturn(true);
    when(emailSender.send(any())).thenReturn(EmailSendResult.ok("msg-created-123"));

    DeliveryLog sentLog = mock(DeliveryLog.class);
    when(sentLog.getStatus()).thenReturn(DeliveryStatus.SENT);
    when(deliveryLogPersistenceService.findByReportId(REPORT_ID)).thenReturn(Optional.of(sentLog));

    DeliveryLog result = emailDeliveryService.autoDeliverBriefingReport(REPORT_ID);

    verify(deliveryLogPersistenceService)
        .createOrGet(anyLong(), anyLong(), anyString(), anyString());
    verify(emailSender).send(any());
    assertThat(result.getStatus()).isEqualTo(DeliveryStatus.SENT);
  }
}
