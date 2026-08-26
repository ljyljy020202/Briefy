package com.briefy.domain.delivery.entity;

import com.briefy.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;

@Entity
@Table(
    name = "delivery_logs",
    indexes = {
      @Index(name = "idx_delivery_logs_user", columnList = "user_id"),
      @Index(name = "idx_delivery_logs_report", columnList = "briefing_report_id")
    },
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uq_delivery_logs_report",
          columnNames = {"briefing_report_id"})
    })
public class DeliveryLog extends BaseTimeEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  @Column(name = "briefing_report_id", nullable = false)
  private Long briefingReportId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private DeliveryStatus status;

  @Column(name = "to_email", nullable = false, length = 255)
  private String toEmail;

  @Column(length = 255)
  private String subject;

  @Column(name = "provider_message_id", length = 255)
  private String providerMessageId;

  @Column(name = "error_message", length = 1000)
  private String errorMessage;

  @Column(name = "sent_at")
  private LocalDateTime sentAt;

  @Column(name = "retry_count", nullable = false)
  private int retryCount = 0;

  @Column(name = "last_attempt_at")
  private LocalDateTime lastAttemptAt;

  protected DeliveryLog() {}

  public static DeliveryLog createPending(
      Long userId, Long briefingReportId, String toEmail, String subject) {
    DeliveryLog log = new DeliveryLog();
    log.userId = userId;
    log.briefingReportId = briefingReportId;
    log.status = DeliveryStatus.PENDING;
    log.toEmail = toEmail;
    log.subject = subject;
    return log;
  }

  public void markSent(String providerMessageId) {
    this.status = DeliveryStatus.SENT;
    this.providerMessageId = providerMessageId;
    this.sentAt = LocalDateTime.now();
  }

  public void markFailed(String errorMessage) {
    this.status = DeliveryStatus.FAILED;
    this.errorMessage = errorMessage;
  }

  public Long getId() {
    return id;
  }

  public Long getUserId() {
    return userId;
  }

  public Long getBriefingReportId() {
    return briefingReportId;
  }

  public DeliveryStatus getStatus() {
    return status;
  }

  public String getToEmail() {
    return toEmail;
  }

  public String getSubject() {
    return subject;
  }

  public String getProviderMessageId() {
    return providerMessageId;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public LocalDateTime getSentAt() {
    return sentAt;
  }

  public void incrementRetry() {
    this.retryCount++;
    this.lastAttemptAt = LocalDateTime.now();
  }

  public int getRetryCount() {
    return retryCount;
  }

  public LocalDateTime getLastAttemptAt() {
    return lastAttemptAt;
  }
}
