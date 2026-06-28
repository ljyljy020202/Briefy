package com.briefy.domain.candidatepool.entity;

import com.briefy.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
    name = "job_postings",
    uniqueConstraints = {@UniqueConstraint(name = "uq_job_postings_url", columnNames = "url")},
    indexes = {
      @Index(name = "idx_job_postings_collected_date", columnList = "collected_date"),
      @Index(name = "idx_job_postings_content_hash", columnList = "content_hash")
    })
public class JobPosting extends BaseTimeEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 255)
  private String title;

  @Column(nullable = false, length = 100)
  private String company;

  @Column(nullable = false, length = 1000)
  private String url;

  @Column(length = 100)
  private String location;

  private LocalDate deadline;

  @Column(columnDefinition = "TEXT")
  private String description;

  @Column(length = 1000)
  private String skills;

  @Column(name = "employment_type", length = 50)
  private String employmentType;

  @Column(name = "experience_level", length = 50)
  private String experienceLevel;

  @Column(name = "content_hash", length = 64)
  private String contentHash;

  @Column(name = "collected_date", nullable = false)
  private LocalDate collectedDate;

  @Column(name = "published_at")
  private LocalDateTime publishedAt;

  protected JobPosting() {}

  public Long getId() {
    return id;
  }

  public String getTitle() {
    return title;
  }

  public String getCompany() {
    return company;
  }

  public String getUrl() {
    return url;
  }

  public String getLocation() {
    return location;
  }

  public LocalDate getDeadline() {
    return deadline;
  }

  public String getDescription() {
    return description;
  }

  public String getSkills() {
    return skills;
  }

  public String getEmploymentType() {
    return employmentType;
  }

  public String getExperienceLevel() {
    return experienceLevel;
  }

  public String getContentHash() {
    return contentHash;
  }

  public LocalDate getCollectedDate() {
    return collectedDate;
  }

  public LocalDateTime getPublishedAt() {
    return publishedAt;
  }
}
