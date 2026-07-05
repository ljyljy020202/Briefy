package com.briefy.domain.candidatepool.entity;

import com.briefy.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(
    name = "job_posting_sources",
    uniqueConstraints = {
      @UniqueConstraint(name = "uq_jps_source_record_key", columnNames = "source_record_key")
    },
    indexes = {
      @Index(name = "idx_jps_job_posting_id", columnList = "job_posting_id"),
      @Index(name = "idx_jps_source", columnList = "source"),
      @Index(name = "idx_jps_is_active", columnList = "is_active")
    })
public class JobPostingSource extends BaseTimeEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(
      name = "job_posting_id",
      nullable = false,
      foreignKey = @ForeignKey(name = "fk_jps_job_posting"))
  private JobPosting jobPosting;

  @Column(length = 100)
  private String source;

  @Column(name = "source_external_id", length = 500)
  private String sourceExternalId;

  @Column(name = "source_url", nullable = false, length = 1000)
  private String sourceUrl;

  @Column(name = "source_record_key", nullable = false, length = 64)
  private String sourceRecordKey;

  @Column(name = "source_content_hash", length = 64)
  private String sourceContentHash;

  @Column(name = "first_seen_at")
  private LocalDateTime firstSeenAt;

  @Column(name = "last_seen_at")
  private LocalDateTime lastSeenAt;

  @Column(name = "last_collected_at")
  private LocalDate lastCollectedAt;

  @Column(name = "is_active", nullable = false)
  private boolean active = true;

  protected JobPostingSource() {}

  public static JobPostingSource create(
      JobPosting jobPosting,
      String source,
      String sourceExternalId,
      String sourceUrl,
      String sourceRecordKey,
      String sourceContentHash,
      LocalDateTime now,
      LocalDate collectedDate) {
    JobPostingSource s = new JobPostingSource();
    s.jobPosting = jobPosting;
    s.source = source;
    s.sourceExternalId = sourceExternalId;
    s.sourceUrl = sourceUrl;
    s.sourceRecordKey = sourceRecordKey;
    s.sourceContentHash = sourceContentHash;
    s.firstSeenAt = now;
    s.lastSeenAt = now;
    s.lastCollectedAt = collectedDate;
    s.active = true;
    return s;
  }

  public void touch(LocalDateTime seenAt, LocalDate collectedAt, String newContentHash) {
    this.lastSeenAt = seenAt;
    this.lastCollectedAt = collectedAt;
    if (newContentHash != null) {
      this.sourceContentHash = newContentHash;
    }
  }

  public Long getId() {
    return id;
  }

  public JobPosting getJobPosting() {
    return jobPosting;
  }

  public String getSource() {
    return source;
  }

  public String getSourceExternalId() {
    return sourceExternalId;
  }

  public String getSourceUrl() {
    return sourceUrl;
  }

  public String getSourceRecordKey() {
    return sourceRecordKey;
  }

  public String getSourceContentHash() {
    return sourceContentHash;
  }

  public LocalDateTime getFirstSeenAt() {
    return firstSeenAt;
  }

  public LocalDateTime getLastSeenAt() {
    return lastSeenAt;
  }

  public LocalDate getLastCollectedAt() {
    return lastCollectedAt;
  }

  public boolean isActive() {
    return active;
  }
}
