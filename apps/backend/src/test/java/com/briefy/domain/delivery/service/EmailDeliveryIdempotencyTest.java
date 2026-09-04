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

import com.briefy.config.AppProperties;
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
class EmailDeliveryIdempotencyTest {

  @Mock private DeliveryLogPersistenceService deliveryLogPersistenceService;
  @Mock private BriefingReportRepository briefingReportRepository;
  @Mock private UserRepository userRepository;
  @Mock private EmailSender emailSender;

  private EmailDeliveryService emailDeliveryService;

  private BriefingReport report;
  private User user;

  private static final Long REPORT_ID = 10L;
  private static final Long USER_ID = 1L;
  private static final Long LOG_ID = 100L;

  @BeforeEach
  void setUp() {
    emailDeliveryService =
        new EmailDeliveryService(
            deliveryLogPersistenceService,
            briefingReportRepository,
            userRepository,
            emailSender,
            new AppProperties("http://localhost:3000"));

    report = mock(BriefingReport.class);
    when(report.getId()).thenReturn(REPORT_ID);
    when(report.getUserId()).thenReturn(USER_ID);
    lenient().when(report.getTitle()).thenReturn("오늘의 채용 브리핑");
    lenient().when(report.getContent()).thenReturn("## 내용");
    lenient().when(briefingReportRepository.findById(REPORT_ID)).thenReturn(Optional.of(report));

    user = mock(User.class);
    lenient().when(user.getId()).thenReturn(USER_ID);
    lenient().when(user.getEmail()).thenReturn("user@example.com");
    lenient().when(user.isBriefingEmailEnabled()).thenReturn(true);
    lenient().when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
  }

  // ── SENT 중복 방지 ──────────────────────────────────────────────────────

  @Test
  void deliverBriefingReport_alreadySent_doesNotCallEmailSender() {
    DeliveryLog sentLog = mock(DeliveryLog.class);
    when(sentLog.getId()).thenReturn(LOG_ID);
    when(sentLog.getStatus()).thenReturn(DeliveryStatus.SENT);
    when(deliveryLogPersistenceService.createOrGet(anyLong(), anyLong(), anyString(), anyString()))
        .thenReturn(sentLog);

    DeliveryLog result = emailDeliveryService.deliverBriefingReport(REPORT_ID);

    verify(emailSender, never()).send(any());
    assertThat(result.getStatus()).isEqualTo(DeliveryStatus.SENT);
  }

  @Test
  void deliverBriefingReport_pendingLog_callsEmailSender() {
    DeliveryLog pendingLog = mock(DeliveryLog.class);
    when(pendingLog.getId()).thenReturn(LOG_ID);
    when(pendingLog.getStatus()).thenReturn(DeliveryStatus.PENDING);
    when(pendingLog.getToEmail()).thenReturn("user@example.com");
    when(deliveryLogPersistenceService.createOrGet(anyLong(), anyLong(), anyString(), anyString()))
        .thenReturn(pendingLog);
    when(deliveryLogPersistenceService.claimForSending(LOG_ID)).thenReturn(true);
    when(emailSender.send(any())).thenReturn(EmailSendResult.ok("msg-123"));

    DeliveryLog sentLog = mock(DeliveryLog.class);
    when(sentLog.getStatus()).thenReturn(DeliveryStatus.SENT);
    when(deliveryLogPersistenceService.findByReportId(REPORT_ID)).thenReturn(Optional.of(sentLog));

    DeliveryLog result = emailDeliveryService.deliverBriefingReport(REPORT_ID);

    verify(emailSender).send(any());
    assertThat(result.getStatus()).isEqualTo(DeliveryStatus.SENT);
  }

  @Test
  void deliverBriefingReport_pendingLog_senderFails_marksLogFailed() {
    DeliveryLog pendingLog = mock(DeliveryLog.class);
    when(pendingLog.getId()).thenReturn(LOG_ID);
    when(pendingLog.getStatus()).thenReturn(DeliveryStatus.PENDING);
    when(pendingLog.getToEmail()).thenReturn("user@example.com");
    when(deliveryLogPersistenceService.createOrGet(anyLong(), anyLong(), anyString(), anyString()))
        .thenReturn(pendingLog);
    when(deliveryLogPersistenceService.claimForSending(LOG_ID)).thenReturn(true);
    when(emailSender.send(any())).thenReturn(EmailSendResult.fail("Timeout"));

    DeliveryLog failedLog = mock(DeliveryLog.class);
    when(failedLog.getStatus()).thenReturn(DeliveryStatus.FAILED);
    when(failedLog.getErrorMessage()).thenReturn("Timeout");
    when(deliveryLogPersistenceService.findByReportId(REPORT_ID))
        .thenReturn(Optional.of(failedLog));

    DeliveryLog result = emailDeliveryService.deliverBriefingReport(REPORT_ID);

    assertThat(result.getStatus()).isEqualTo(DeliveryStatus.FAILED);
    verify(deliveryLogPersistenceService).markFailed(LOG_ID, "Timeout");
  }

  @Test
  void deliverBriefingReport_pendingLog_senderThrows_marksLogFailed() {
    DeliveryLog pendingLog = mock(DeliveryLog.class);
    when(pendingLog.getId()).thenReturn(LOG_ID);
    when(pendingLog.getStatus()).thenReturn(DeliveryStatus.PENDING);
    when(pendingLog.getToEmail()).thenReturn("user@example.com");
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
  }

  // ── SENDING 중 동시 요청 ─────────────────────────────────────────────────

  @Test
  void deliverBriefingReport_sendingLog_skipsEmailSend() {
    DeliveryLog sendingLog = mock(DeliveryLog.class);
    when(sendingLog.getId()).thenReturn(LOG_ID);
    when(sendingLog.getStatus()).thenReturn(DeliveryStatus.SENDING);
    when(deliveryLogPersistenceService.createOrGet(anyLong(), anyLong(), anyString(), anyString()))
        .thenReturn(sendingLog);

    DeliveryLog result = emailDeliveryService.deliverBriefingReport(REPORT_ID);

    verify(emailSender, never()).send(any());
    assertThat(result.getStatus()).isEqualTo(DeliveryStatus.SENDING);
  }

  @Test
  void deliverBriefingReport_claimFails_skipsEmailSend() {
    DeliveryLog pendingLog = mock(DeliveryLog.class);
    when(pendingLog.getId()).thenReturn(LOG_ID);
    when(pendingLog.getStatus()).thenReturn(DeliveryStatus.PENDING);
    when(pendingLog.getToEmail()).thenReturn("user@example.com");
    when(deliveryLogPersistenceService.createOrGet(anyLong(), anyLong(), anyString(), anyString()))
        .thenReturn(pendingLog);
    // 다른 요청이 먼저 선점
    when(deliveryLogPersistenceService.claimForSending(LOG_ID)).thenReturn(false);

    DeliveryLog sendingLog = mock(DeliveryLog.class);
    when(sendingLog.getStatus()).thenReturn(DeliveryStatus.SENDING);
    when(deliveryLogPersistenceService.findByReportId(REPORT_ID))
        .thenReturn(Optional.of(sendingLog));

    DeliveryLog result = emailDeliveryService.deliverBriefingReport(REPORT_ID);

    verify(emailSender, never()).send(any());
    assertThat(result.getStatus()).isEqualTo(DeliveryStatus.SENDING);
  }

  // ── FAILED 재시도 (관리자 경로) ───────────────────────────────────────────

  @Test
  void deliverBriefingReport_failedLog_retries() {
    DeliveryLog failedLog = mock(DeliveryLog.class);
    when(failedLog.getId()).thenReturn(LOG_ID);
    when(failedLog.getStatus()).thenReturn(DeliveryStatus.FAILED);
    when(failedLog.getToEmail()).thenReturn("user@example.com");
    when(deliveryLogPersistenceService.createOrGet(anyLong(), anyLong(), anyString(), anyString()))
        .thenReturn(failedLog);
    when(deliveryLogPersistenceService.claimForSending(LOG_ID)).thenReturn(true);
    when(emailSender.send(any())).thenReturn(EmailSendResult.ok("msg-retry-ok"));

    DeliveryLog sentLog = mock(DeliveryLog.class);
    when(sentLog.getStatus()).thenReturn(DeliveryStatus.SENT);
    when(deliveryLogPersistenceService.findByReportId(REPORT_ID)).thenReturn(Optional.of(sentLog));

    DeliveryLog result = emailDeliveryService.deliverBriefingReport(REPORT_ID);

    verify(emailSender).send(any());
    assertThat(result.getStatus()).isEqualTo(DeliveryStatus.SENT);
    verify(deliveryLogPersistenceService).markSent(LOG_ID, "msg-retry-ok");
  }

  // ── 두 번 호출 시 두 번째는 스킵 ──────────────────────────────────────────

  @Test
  void deliverBriefingReport_calledTwice_secondCallSkipsEmailSend() {
    // 첫 번째 호출: PENDING → 발송 성공
    DeliveryLog pendingLog = mock(DeliveryLog.class);
    when(pendingLog.getId()).thenReturn(LOG_ID);
    when(pendingLog.getStatus()).thenReturn(DeliveryStatus.PENDING);
    when(pendingLog.getToEmail()).thenReturn("user@example.com");

    DeliveryLog sentLog = mock(DeliveryLog.class);
    when(sentLog.getId()).thenReturn(LOG_ID);
    when(sentLog.getStatus()).thenReturn(DeliveryStatus.SENT);

    // 첫 번째 createOrGet → PENDING, 두 번째 → SENT
    when(deliveryLogPersistenceService.createOrGet(anyLong(), anyLong(), anyString(), anyString()))
        .thenReturn(pendingLog)
        .thenReturn(sentLog);
    when(deliveryLogPersistenceService.claimForSending(LOG_ID)).thenReturn(true);
    when(emailSender.send(any())).thenReturn(EmailSendResult.ok("msg-first"));
    when(deliveryLogPersistenceService.findByReportId(REPORT_ID)).thenReturn(Optional.of(sentLog));

    emailDeliveryService.deliverBriefingReport(REPORT_ID);
    emailDeliveryService.deliverBriefingReport(REPORT_ID);

    // emailSender.send()는 정확히 1회만 호출
    verify(emailSender, times(1)).send(any());
  }
}
