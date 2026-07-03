package com.briefy.domain.delivery.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.briefy.domain.delivery.entity.DeliveryLog;
import com.briefy.domain.delivery.entity.DeliveryStatus;
import com.briefy.domain.delivery.service.EmailDeliveryService;
import com.briefy.global.exception.BusinessException;
import com.briefy.global.exception.ErrorCode;
import com.briefy.global.exception.GlobalExceptionHandler;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class AdminDeliveryControllerTest {

  @Mock private EmailDeliveryService emailDeliveryService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.standaloneSetup(new AdminDeliveryController(emailDeliveryService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  void deliverEmail_callsServiceAndReturnsDeliveryResult() throws Exception {
    DeliveryLog log = mock(DeliveryLog.class);
    when(log.getId()).thenReturn(10L);
    when(log.getToEmail()).thenReturn("user@example.com");
    when(log.getSubject()).thenReturn("오늘의 채용 브리핑");
    when(log.getStatus()).thenReturn(DeliveryStatus.SENT);
    when(log.getProviderMessageId()).thenReturn("FAKE-abc-123");
    when(log.getSentAt()).thenReturn(LocalDateTime.of(2026, 7, 3, 10, 0));
    when(emailDeliveryService.deliverBriefingReport(1L)).thenReturn(log);

    mockMvc
        .perform(post("/api/admin/briefing-reports/1/deliver-email"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.reportId").value(1))
        .andExpect(jsonPath("$.data.deliveryLogId").value(10))
        .andExpect(jsonPath("$.data.recipientEmail").value("user@example.com"))
        .andExpect(jsonPath("$.data.subject").value("오늘의 채용 브리핑"))
        .andExpect(jsonPath("$.data.providerMessageId").value("FAKE-abc-123"));

    verify(emailDeliveryService).deliverBriefingReport(1L);
  }

  @Test
  void deliverEmail_success_statusIsSent() throws Exception {
    DeliveryLog log = mock(DeliveryLog.class);
    when(log.getId()).thenReturn(10L);
    when(log.getToEmail()).thenReturn("user@example.com");
    when(log.getSubject()).thenReturn("오늘의 채용 브리핑");
    when(log.getStatus()).thenReturn(DeliveryStatus.SENT);
    when(log.getSentAt()).thenReturn(LocalDateTime.of(2026, 7, 3, 10, 0));
    when(emailDeliveryService.deliverBriefingReport(1L)).thenReturn(log);

    mockMvc
        .perform(post("/api/admin/briefing-reports/1/deliver-email"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("SENT"));
  }

  @Test
  void deliverEmail_reportNotFound_returns404() throws Exception {
    when(emailDeliveryService.deliverBriefingReport(999L))
        .thenThrow(new BusinessException(ErrorCode.BRIEFING_REPORT_NOT_FOUND));

    mockMvc
        .perform(post("/api/admin/briefing-reports/999/deliver-email"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.error.code").value("BRIEFING_REPORT_NOT_FOUND"));
  }
}
