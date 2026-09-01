package com.briefy.domain.candidatepool.entity;

import com.briefy.domain.company.entity.Company;
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
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(
    name = "job_postings",
    indexes = {
      @Index(name = "idx_job_postings_collected_date", columnList = "collected_date"),
      @Index(name = "idx_job_postings_content_hash", columnList = "content_hash"),
      @Index(name = "idx_job_postings_canonical_fingerprint", columnList = "canonical_fingerprint"),
      @Index(name = "idx_job_postings_company_id", columnList = "company_id")
    })
public class JobPosting extends BaseTimeEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 255)
  private String title;

  @Column(nullable = false, length = 100)
  private String company;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "company_id", foreignKey = @ForeignKey(name = "fk_job_postings_company"))
  private Company linkedCompany;

  @Column(length = 255)
  private String source;

  @Column(nullable = false, length = 1000)
  private String url;

  @Column(length = 100)
  private String location;

  private LocalDate deadline;

  @Column(columnDefinition = "TEXT")
  private String description;

  @Column(columnDefinition = "TEXT")
  private String roles;

  @Column(length = 1000)
  private String skills;

  @Column(name = "employment_type", length = 50)
  private String employmentType;

  @Column(name = "experience_level", length = 50)
  private String experienceLevel;

  @Column(name = "content_hash", length = 64)
  private String contentHash;

  @Column(name = "canonical_fingerprint", length = 64)
  private String canonicalFingerprint;

  @Column(name = "collected_date", nullable = false)
  private LocalDate collectedDate;

  @Column(name = "published_at")
  private LocalDateTime publishedAt;

  protected JobPosting() {}

  public static JobPosting create(
      String title,
      String company,
      String source,
      String url,
      String location,
      LocalDate deadline,
      String description,
      String roles,
      String skills,
      String employmentType,
      String experienceLevel,
      String contentHash,
      LocalDate collectedDate,
      LocalDateTime publishedAt) {
    JobPosting jp = new JobPosting();
    jp.title = title;
    jp.company = company;
    jp.source = source;
    jp.url = url;
    jp.location = location;
    jp.deadline = deadline;
    jp.description = description;
    jp.roles = roles;
    jp.skills = skills;
    jp.employmentType = employmentType;
    jp.experienceLevel = experienceLevel;
    jp.contentHash = contentHash;
    jp.canonicalFingerprint = contentHash;
    jp.collectedDate = collectedDate;
    jp.publishedAt = publishedAt;
    return jp;
  }

  /**
   * Refreshes mutable fields from a newly collected posting.
   *
   * <p>Metadata update rule: if the incoming value is non-null / non-blank it replaces the stored
   * value; if the incoming value is null / blank the existing value is preserved. This means a
   * re-collection that now carries structured metadata can enrich a previously null-metadata record
   * without ever overwriting a valid existing value with null.
   */
  public void refreshFrom(
      LocalDate newDeadline,
      String newDescription,
      String newContentHash,
      LocalDate collectedDate,
      String newRoles,
      String newSkills,
      String newExperienceLevel,
      String newEmploymentType,
      String newLocation,
      LocalDateTime newPublishedAt) {
    if (newDeadline != null) {
      this.deadline = newDeadline;
    }
    if (newDescription != null && !newDescription.isBlank()) {
      this.description = newDescription;
    }
    if (newContentHash != null) {
      this.contentHash = newContentHash;
    }
    this.collectedDate = collectedDate;
    // Metadata enrichment: only overwrite when incoming value is present AND current is absent.
    if (newRoles != null
        && !newRoles.isBlank()
        && (this.roles == null || this.roles.isBlank() || this.roles.equals("[]"))) {
      this.roles = newRoles;
    }
    if (newSkills != null
        && !newSkills.isBlank()
        && (this.skills == null || this.skills.isBlank() || this.skills.equals("[]"))) {
      this.skills = newSkills;
    }
    if (newExperienceLevel != null
        && !newExperienceLevel.isBlank()
        && (this.experienceLevel == null || this.experienceLevel.isBlank())) {
      this.experienceLevel = newExperienceLevel;
    }
    if (newEmploymentType != null
        && !newEmploymentType.isBlank()
        && (this.employmentType == null || this.employmentType.isBlank())) {
      this.employmentType = newEmploymentType;
    }
    if (newLocation != null
        && !newLocation.isBlank()
        && (this.location == null || this.location.isBlank())) {
      this.location = newLocation;
    }
    if (newPublishedAt != null && this.publishedAt == null) {
      this.publishedAt = newPublishedAt;
    }
  }

  /**
   * 동일 소스에서 내용이 변경된 공고를 갱신한다.
   *
   * <p>교차 소스의 {@link #refreshFrom} 과 달리, 기존 값이 채워져 있어도 새 값(non-null)으로 덮어쓴다. null/blank 로 들어오는 값은
   * 추출 실패로 간주하여 기존 값을 보존한다.
   */
  public void updateFromSameSource(
      LocalDate newDeadline,
      String newDescription,
      String newContentHash,
      LocalDate collectedDate,
      String newRoles,
      String newSkills,
      String newExperienceLevel,
      String newEmploymentType,
      String newLocation,
      LocalDateTime newPublishedAt) {
    if (newDeadline != null) {
      this.deadline = newDeadline;
    }
    if (newDescription != null && !newDescription.isBlank()) {
      this.description = newDescription;
    }
    if (newContentHash != null) {
      this.contentHash = newContentHash;
    }
    this.collectedDate = collectedDate;
    // 동일 소스: 새 값이 있으면 기존 값 덮어쓰기 (교차 소스와의 차이점)
    if (newRoles != null && !newRoles.isBlank()) {
      this.roles = newRoles;
    }
    if (newSkills != null && !newSkills.isBlank()) {
      this.skills = newSkills;
    }
    if (newExperienceLevel != null && !newExperienceLevel.isBlank()) {
      this.experienceLevel = newExperienceLevel;
    }
    if (newEmploymentType != null && !newEmploymentType.isBlank()) {
      this.employmentType = newEmploymentType;
    }
    if (newLocation != null && !newLocation.isBlank()) {
      this.location = newLocation;
    }
    if (newPublishedAt != null) {
      this.publishedAt = newPublishedAt;
    }
  }

  /**
   * @deprecated Use {@link #refreshFrom(LocalDate, String, String, LocalDate, String, String,
   *     String, String, String, LocalDateTime)} instead.
   */
  @Deprecated
  public void refreshFrom(
      LocalDate newDeadline,
      String newDescription,
      String newContentHash,
      LocalDate collectedDate) {
    refreshFrom(
        newDeadline,
        newDescription,
        newContentHash,
        collectedDate,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  public Long getId() {
    return id;
  }

  public String getTitle() {
    return title;
  }

  public String getCompany() {
    return company;
  }

  public Company getLinkedCompany() {
    return linkedCompany;
  }

  public void linkCompany(Company company) {
    this.linkedCompany = company;
  }

  public String getSource() {
    return source;
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

  public String getRoles() {
    return roles;
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

  public String getCanonicalFingerprint() {
    return canonicalFingerprint;
  }

  public LocalDate getCollectedDate() {
    return collectedDate;
  }

  public LocalDateTime getPublishedAt() {
    return publishedAt;
  }
}
