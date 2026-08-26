package com.briefy.domain.collection.entity;

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
    name = "collection_jobs",
    indexes = {
      @Index(name = "idx_collection_jobs_date", columnList = "collection_date"),
      @Index(name = "idx_collection_jobs_status", columnList = "status"),
      @Index(name = "idx_collection_jobs_date_status", columnList = "collection_date, status")
    },
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uq_collection_jobs_date",
          columnNames = {"collection_date"})
    })
public class CollectionJob extends BaseTimeEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "collection_date", nullable = false)
  private LocalDate collectionDate;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 30)
  private CollectionJobStatus status;

  @Enumerated(EnumType.STRING)
  @Column(name = "trigger_type", nullable = false, length = 30)
  private CollectionTriggerType triggerType;

  @Column(columnDefinition = "TEXT")
  private String categories;

  @Column(name = "started_at")
  private LocalDateTime startedAt;

  @Column(name = "completed_at")
  private LocalDateTime completedAt;

  @Column(name = "collected_count", nullable = false)
  private int collectedCount = 0;

  @Column(name = "saved_count", nullable = false)
  private int savedCount = 0;

  @Column(name = "deduplicated_count", nullable = false)
  private int deduplicatedCount = 0;

  @Column(name = "error_message", columnDefinition = "TEXT")
  private String errorMessage;

  @Column(name = "retry_count", nullable = false)
  private int retryCount = 0;

  protected CollectionJob() {}

  private CollectionJob(
      LocalDate collectionDate, String categories, CollectionTriggerType triggerType) {
    this.collectionDate = collectionDate;
    this.categories = categories;
    this.triggerType = triggerType;
    this.status = CollectionJobStatus.PENDING;
    this.collectedCount = 0;
    this.savedCount = 0;
    this.deduplicatedCount = 0;
    this.retryCount = 0;
  }

  public static CollectionJob createPending(
      LocalDate collectionDate, String categories, CollectionTriggerType triggerType) {
    return new CollectionJob(collectionDate, categories, triggerType);
  }

  public void startProcessing() {
    this.status = CollectionJobStatus.PROCESSING;
    this.startedAt = LocalDateTime.now();
  }

  public void complete(int collectedCount, int savedCount, int deduplicatedCount) {
    this.status = CollectionJobStatus.COMPLETED;
    this.completedAt = LocalDateTime.now();
    this.collectedCount = collectedCount;
    this.savedCount = savedCount;
    this.deduplicatedCount = deduplicatedCount;
  }

  public void fail(String errorMessage) {
    this.status = CollectionJobStatus.FAILED;
    this.completedAt = LocalDateTime.now();
    this.errorMessage = errorMessage;
    this.retryCount++;
  }

  public Long getId() {
    return id;
  }

  public LocalDate getCollectionDate() {
    return collectionDate;
  }

  public CollectionJobStatus getStatus() {
    return status;
  }

  public CollectionTriggerType getTriggerType() {
    return triggerType;
  }

  public String getCategories() {
    return categories;
  }

  public LocalDateTime getStartedAt() {
    return startedAt;
  }

  public LocalDateTime getCompletedAt() {
    return completedAt;
  }

  public int getCollectedCount() {
    return collectedCount;
  }

  public int getSavedCount() {
    return savedCount;
  }

  public int getDeduplicatedCount() {
    return deduplicatedCount;
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public int getRetryCount() {
    return retryCount;
  }
}
