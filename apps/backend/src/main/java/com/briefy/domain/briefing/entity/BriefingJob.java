package com.briefy.domain.briefing.entity;

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
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(
    name = "briefing_jobs",
    indexes = {
      @Index(name = "idx_briefing_jobs_user", columnList = "user_id"),
      @Index(name = "idx_briefing_jobs_status", columnList = "status"),
      @Index(name = "idx_briefing_jobs_user_date", columnList = "user_id, briefing_date")
    },
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uq_briefing_jobs_user_date",
          columnNames = {"user_id", "briefing_date"})
    })
public class BriefingJob extends BaseTimeEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 30)
  private BriefingJobStatus status;

  @Enumerated(EnumType.STRING)
  @Column(name = "trigger_type", nullable = false, length = 30)
  private BriefingTriggerType triggerType;

  @Column(name = "scheduled_at")
  private LocalDateTime scheduledAt;

  @Column(name = "started_at")
  private LocalDateTime startedAt;

  @Column(name = "completed_at")
  private LocalDateTime completedAt;

  @Column(name = "error_message", columnDefinition = "TEXT")
  private String errorMessage;

  @Column(name = "retry_count", nullable = false)
  private int retryCount = 0;

  @Column(name = "briefing_date", nullable = false)
  private LocalDate briefingDate;

  @Column(name = "generation_mode", length = 30)
  private String generationMode;

  @Column(name = "fallback_reason", length = 255)
  private String fallbackReason;

  protected BriefingJob() {}

  private BriefingJob(Long userId, BriefingTriggerType triggerType) {
    this.userId = userId;
    this.triggerType = triggerType;
    this.status = BriefingJobStatus.PENDING;
    this.retryCount = 0;
  }

  public static BriefingJob createManual(Long userId, LocalDate briefingDate) {
    BriefingJob job = new BriefingJob(userId, BriefingTriggerType.MANUAL);
    job.briefingDate = briefingDate;
    return job;
  }

  public static BriefingJob createScheduled(Long userId, LocalDate briefingDate) {
    BriefingJob job = new BriefingJob(userId, BriefingTriggerType.SCHEDULED);
    job.briefingDate = briefingDate;
    return job;
  }

  public void startProcessing() {
    this.status = BriefingJobStatus.PROCESSING;
    this.startedAt = LocalDateTime.now();
  }

  public void complete() {
    this.status = BriefingJobStatus.COMPLETED;
    this.completedAt = LocalDateTime.now();
  }

  /**
   * Complete the job and record the generation mode and fallback reason from the Agent response.
   */
  public void completeWithMode(String generationMode, String fallbackReason) {
    this.status = BriefingJobStatus.COMPLETED;
    this.completedAt = LocalDateTime.now();
    this.generationMode = generationMode;
    this.fallbackReason = fallbackReason;
  }

  /** Reset a FAILED job to PROCESSING for a retry attempt. */
  public void resetForRetry() {
    this.status = BriefingJobStatus.PROCESSING;
    this.startedAt = LocalDateTime.now();
    this.completedAt = null;
    this.errorMessage = null;
    this.retryCount++;
  }

  public void fail(String errorMessage) {
    this.status = BriefingJobStatus.FAILED;
    this.completedAt = LocalDateTime.now();
    this.errorMessage = errorMessage;
  }

  public void recordFallback(String reason) {
    this.generationMode = "FALLBACK";
    this.fallbackReason = reason;
  }

  public Long getId() {
    return id;
  }

  public Long getUserId() {
    return userId;
  }

  public BriefingJobStatus getStatus() {
    return status;
  }

  public BriefingTriggerType getTriggerType() {
    return triggerType;
  }

  public LocalDateTime getScheduledAt() {
    return scheduledAt;
  }

  public LocalDateTime getStartedAt() {
    return startedAt;
  }

  public LocalDateTime getCompletedAt() {
    return completedAt;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public int getRetryCount() {
    return retryCount;
  }

  public LocalDate getBriefingDate() {
    return briefingDate;
  }

  public String getGenerationMode() {
    return generationMode;
  }

  public String getFallbackReason() {
    return fallbackReason;
  }
}
