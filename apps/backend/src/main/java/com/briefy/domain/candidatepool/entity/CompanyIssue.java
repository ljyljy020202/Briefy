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
    name = "company_issues",
    uniqueConstraints = {@UniqueConstraint(name = "uq_company_issues_url", columnNames = "url")},
    indexes = {
      @Index(name = "idx_company_issues_collected_date", columnList = "collected_date"),
      @Index(name = "idx_company_issues_company", columnList = "company")
    })
public class CompanyIssue extends BaseTimeEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 100)
  private String company;

  @Column(nullable = false, length = 255)
  private String title;

  @Column(nullable = false, length = 1000)
  private String url;

  @Column(columnDefinition = "TEXT")
  private String summary;

  @Column(name = "published_at")
  private LocalDateTime publishedAt;

  @Column(name = "content_hash", length = 64)
  private String contentHash;

  @Column(name = "collected_date", nullable = false)
  private LocalDate collectedDate;

  protected CompanyIssue() {}

  public Long getId() {
    return id;
  }

  public String getCompany() {
    return company;
  }

  public String getTitle() {
    return title;
  }

  public String getUrl() {
    return url;
  }

  public String getSummary() {
    return summary;
  }

  public LocalDateTime getPublishedAt() {
    return publishedAt;
  }

  public String getContentHash() {
    return contentHash;
  }

  public LocalDate getCollectedDate() {
    return collectedDate;
  }
}
