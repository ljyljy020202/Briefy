package com.briefy.domain.briefing.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(
    name = "briefing_articles",
    indexes = {@Index(name = "idx_briefing_articles_report", columnList = "briefing_report_id")})
public class BriefingArticle {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "briefing_report_id", nullable = false)
  private BriefingReport briefingReport;

  @Column(nullable = false, length = 500)
  private String title;

  @Column(length = 255)
  private String source;

  @Column(length = 1000)
  private String url;

  @Column(columnDefinition = "TEXT")
  private String summary;

  @Column(name = "why_it_matters", columnDefinition = "TEXT")
  private String whyItMatters;

  @Column(name = "published_at")
  private LocalDateTime publishedAt;

  @Column(name = "display_order")
  private Integer displayOrder;

  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  protected BriefingArticle() {}

  @PrePersist
  void prePersist() {
    this.createdAt = LocalDateTime.now();
  }

  public static BriefingArticle create(
      BriefingReport report,
      String title,
      String source,
      String url,
      String summary,
      String whyItMatters,
      LocalDateTime publishedAt,
      int displayOrder) {
    BriefingArticle article = new BriefingArticle();
    article.briefingReport = report;
    article.title = title;
    article.source = source;
    article.url = url;
    article.summary = summary;
    article.whyItMatters = whyItMatters;
    article.publishedAt = publishedAt;
    article.displayOrder = displayOrder;
    return article;
  }

  public Long getId() {
    return id;
  }

  public BriefingReport getBriefingReport() {
    return briefingReport;
  }

  public String getTitle() {
    return title;
  }

  public String getSource() {
    return source;
  }

  public String getUrl() {
    return url;
  }

  public String getSummary() {
    return summary;
  }

  public String getWhyItMatters() {
    return whyItMatters;
  }

  public LocalDateTime getPublishedAt() {
    return publishedAt;
  }

  public Integer getDisplayOrder() {
    return displayOrder;
  }

  public LocalDateTime getCreatedAt() {
    return createdAt;
  }
}
