package com.briefy.domain.delivery.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.briefy.domain.briefing.entity.BriefingReport;
import com.briefy.domain.briefing.repository.BriefingReportRepository;
import com.briefy.domain.delivery.entity.DeliveryLog;
import com.briefy.domain.delivery.entity.DeliveryStatus;
import com.briefy.domain.delivery.repository.DeliveryLogRepository;
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

  @Mock private DeliveryLogRepository deliveryLogRepository;
  @Mock private BriefingReportRepository briefingReportRepository;
  @Mock private UserRepository userRepository;
  @Mock private EmailSender emailSender;

  private EmailDeliveryService emailDeliveryService;

  private BriefingReport report;
  private User user;

  private static final Long REPORT_ID = 10L;

  @BeforeEach
  void setUp() {
    emailDeliveryService =
        new EmailDeliveryService(
            deliveryLogRepository, briefingReportRepository, userRepository, emailSender);

    report = mock(BriefingReport.class);
    when(report.getUserId()).thenReturn(1L);
    lenient().when(report.getTitle()).thenReturn("오늘의 채용 브리핑");
    lenient().when(report.getContent()).thenReturn("<html>content</html>");
    lenient().when(briefingReportRepository.findById(REPORT_ID)).thenReturn(Optional.of(report));

    user = mock(User.class);
    lenient().when(user.getEmail()).thenReturn("user@example.com");
    lenient().when(user.isBriefingEmailEnabled()).thenReturn(true);
    lenient().when(userRepository.findById(1L)).thenReturn(Optional.of(user));

    // 기본: 아직 발송된 이메일 없음
    when(deliveryLogRepository.existsByBriefingReportIdAndStatus(REPORT_ID, DeliveryStatus.SENT))
        .thenReturn(false);
  }

  // ── 중복 발송 방지 ─────────────────────────────────────────────────────────

  @Test
  void deliverBriefingReport_alreadySent_doesNotCallEmailSender() {
    DeliveryLog existingLog = mock(DeliveryLog.class);
    when(existingLog.getStatus()).thenReturn(DeliveryStatus.SENT);
    when(deliveryLogRepository.existsByBriefingReportIdAndStatus(REPORT_ID, DeliveryStatus.SENT))
        .thenReturn(true);
    when(deliveryLogRepository.findByBriefingReportId(REPORT_ID))
        .thenReturn(Optional.of(existingLog));

    DeliveryLog result = emailDeliveryService.deliverBriefingReport(REPORT_ID);

    verify(emailSender, never()).send(any());
    assertThat(result).isEqualTo(existingLog);
  }

  @Test
  void deliverBriefingReport_notYetSent_callsEmailSender() {
    when(emailSender.send(any())).thenReturn(EmailSendResult.ok("msg-123"));
    when(deliveryLogRepository.save(any(DeliveryLog.class))).thenAnswer(inv -> inv.getArgument(0));

    DeliveryLog result = emailDeliveryService.deliverBriefingReport(REPORT_ID);

    verify(emailSender).send(any());
    assertThat(result.getStatus()).isEqualTo(DeliveryStatus.SENT);
  }

  @Test
  void deliverBriefingReport_notYetSent_senderFails_marksLogFailed() {
    when(emailSender.send(any())).thenReturn(EmailSendResult.fail("Timeout"));
    when(deliveryLogRepository.save(any(DeliveryLog.class))).thenAnswer(inv -> inv.getArgument(0));

    DeliveryLog result = emailDeliveryService.deliverBriefingReport(REPORT_ID);

    assertThat(result.getStatus()).isEqualTo(DeliveryStatus.FAILED);
    assertThat(result.getErrorMessage()).isEqualTo("Timeout");
  }

  @Test
  void deliverBriefingReport_notYetSent_senderThrows_marksLogFailed() {
    when(emailSender.send(any())).thenThrow(new RuntimeException("SES down"));
    when(deliveryLogRepository.save(any(DeliveryLog.class))).thenAnswer(inv -> inv.getArgument(0));

    DeliveryLog result = emailDeliveryService.deliverBriefingReport(REPORT_ID);

    assertThat(result.getStatus()).isEqualTo(DeliveryStatus.FAILED);
  }

  // ── idempotency: 두 번 호출 시 두 번째는 스킵 ─────────────────────────────────

  @Test
  void deliverBriefingReport_calledTwice_secondCallSkipsEmailSend() {
    // First call: not yet sent
    when(emailSender.send(any())).thenReturn(EmailSendResult.ok("msg-first"));
    when(deliveryLogRepository.save(any(DeliveryLog.class))).thenAnswer(inv -> inv.getArgument(0));

    emailDeliveryService.deliverBriefingReport(REPORT_ID);

    // Second call: already sent
    DeliveryLog sentLog = mock(DeliveryLog.class);
    when(sentLog.getStatus()).thenReturn(DeliveryStatus.SENT);
    when(deliveryLogRepository.existsByBriefingReportIdAndStatus(REPORT_ID, DeliveryStatus.SENT))
        .thenReturn(true);
    when(deliveryLogRepository.findByBriefingReportId(REPORT_ID)).thenReturn(Optional.of(sentLog));

    emailDeliveryService.deliverBriefingReport(REPORT_ID);

    // emailSender.send() should have been called exactly once total
    verify(emailSender).send(any());
  }
}
