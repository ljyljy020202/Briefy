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
import com.briefy.email.EmailSendResult;
import com.briefy.email.EmailSender;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EmailDeliveryServiceTest {

  @Mock private DeliveryLogRepository deliveryLogRepository;
  @Mock private BriefingReportRepository briefingReportRepository;
  @Mock private UserRepository userRepository;
  @Mock private EmailSender emailSender;

  @InjectMocks private EmailDeliveryService emailDeliveryService;

  private BriefingReport report;
  private User user;

  @BeforeEach
  void setUp() {
    report = mock(BriefingReport.class);
    when(report.getUserId()).thenReturn(1L);
    lenient().when(report.getTitle()).thenReturn("오늘의 채용 브리핑");
    lenient().when(report.getContent()).thenReturn("<html>content</html>");
    when(briefingReportRepository.findById(10L)).thenReturn(Optional.of(report));

    user = mock(User.class);
    lenient().when(user.getEmail()).thenReturn("user@example.com");
    lenient().when(user.isBriefingEmailEnabled()).thenReturn(true);
    when(userRepository.findById(1L)).thenReturn(Optional.of(user));
  }

  @Test
  void deliverBriefingReport_success_marksLogAsSent() {
    when(emailSender.send(any())).thenReturn(EmailSendResult.ok("msg-abc-123"));

    DeliveryLog result = emailDeliveryService.deliverBriefingReport(10L);

    assertThat(result.getStatus()).isEqualTo(DeliveryStatus.SENT);
    assertThat(result.getProviderMessageId()).isEqualTo("msg-abc-123");
    assertThat(result.getErrorMessage()).isNull();
    verify(deliveryLogRepository).save(any(DeliveryLog.class));
  }

  @Test
  void deliverBriefingReport_senderReturnsFail_marksLogAsFailed() {
    when(emailSender.send(any())).thenReturn(EmailSendResult.fail("Connection refused"));

    DeliveryLog result = emailDeliveryService.deliverBriefingReport(10L);

    assertThat(result.getStatus()).isEqualTo(DeliveryStatus.FAILED);
    assertThat(result.getErrorMessage()).isEqualTo("Connection refused");
    assertThat(result.getProviderMessageId()).isNull();
  }

  @Test
  void deliverBriefingReport_senderThrows_marksLogAsFailed() {
    when(emailSender.send(any())).thenThrow(new RuntimeException("Network error"));

    DeliveryLog result = emailDeliveryService.deliverBriefingReport(10L);

    assertThat(result.getStatus()).isEqualTo(DeliveryStatus.FAILED);
    assertThat(result.getErrorMessage()).isEqualTo("Network error");
  }

  @Test
  void deliverBriefingReport_usesReportEmailOverLoginEmail_whenSet() {
    when(user.getReportEmail()).thenReturn("report@example.com");
    when(emailSender.send(any())).thenReturn(EmailSendResult.ok("msg-xyz"));

    DeliveryLog result = emailDeliveryService.deliverBriefingReport(10L);

    assertThat(result.getToEmail()).isEqualTo("report@example.com");
    assertThat(result.getStatus()).isEqualTo(DeliveryStatus.SENT);
  }

  @Test
  void deliverBriefingReport_briefingSuccess_doesNotAffectReportOnEmailFailure() {
    when(emailSender.send(any())).thenThrow(new RuntimeException("SES down"));

    DeliveryLog result = emailDeliveryService.deliverBriefingReport(10L);

    assertThat(result.getStatus()).isEqualTo(DeliveryStatus.FAILED);
    verify(briefingReportRepository, never()).delete(any());
    verify(briefingReportRepository, never()).save(any());
  }

  @Test
  void autoDeliverBriefingReport_sendsEmail_whenSubscriptionEnabled() {
    when(emailSender.send(any())).thenReturn(EmailSendResult.ok("msg-auto-123"));

    DeliveryLog result = emailDeliveryService.autoDeliverBriefingReport(10L);

    assertThat(result.getStatus()).isEqualTo(DeliveryStatus.SENT);
  }

  @Test
  void autoDeliverBriefingReport_skipsEmail_whenSubscriptionDisabled() {
    when(user.isBriefingEmailEnabled()).thenReturn(false);

    DeliveryLog result = emailDeliveryService.autoDeliverBriefingReport(10L);

    assertThat(result).isNull();
    verify(emailSender, never()).send(any());
  }
}
