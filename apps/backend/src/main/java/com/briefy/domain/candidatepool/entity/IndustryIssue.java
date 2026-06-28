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
    name = "industry_issues",
    uniqueConstraints = {@UniqueConstraint(name = "uq_industry_issues_url", columnNames = "url")},
    indexes = {
      @Index(name = "idx_industry_issues_collected_date", columnList = "collected_date"),
      @Index(name = "idx_industry_issues_category", columnList = "category")
    })
public class IndustryIssue extends BaseTimeEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 100)
  private String category;

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

  protected IndustryIssue() {}

  public Long getId() {
    return id;
  }

  public String getCategory() {
    return category;
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
