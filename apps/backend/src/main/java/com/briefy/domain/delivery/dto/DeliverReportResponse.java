package com.briefy.domain.delivery.dto;

import com.briefy.domain.delivery.entity.DeliveryLog;
import com.briefy.domain.delivery.entity.DeliveryStatus;
import java.time.LocalDateTime;

public record DeliverReportResponse(
    Long reportId,
    Long deliveryLogId,
    String recipientEmail,
    String subject,
    DeliveryStatus status,
    String providerMessageId,
    String errorMessage,
    LocalDateTime sentAt) {

  public static DeliverReportResponse from(Long reportId, DeliveryLog log) {
    return new DeliverReportResponse(
        reportId,
        log.getId(),
        log.getToEmail(),
        log.getSubject(),
        log.getStatus(),
        log.getProviderMessageId(),
        log.getErrorMessage(),
        log.getSentAt());
  }
}
