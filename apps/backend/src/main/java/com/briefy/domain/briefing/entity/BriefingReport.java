package com.briefy.domain.briefing.entity;

import com.briefy.global.entity.BaseTimeEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
    name = "briefing_reports",
    indexes = {
      @Index(name = "idx_briefing_reports_user", columnList = "user_id"),
      @Index(name = "idx_briefing_reports_user_date", columnList = "user_id, report_date")
    })
public class BriefingReport extends BaseTimeEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  @OneToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "briefing_job_id", unique = true, nullable = false)
  private BriefingJob briefingJob;

  @Column(nullable = false, length = 255)
  private String title;

  @Column(length = 1000)
  private String summary;

  @Column(nullable = false, columnDefinition = "MEDIUMTEXT")
  private String content;

  @Column(name = "report_date", nullable = false)
  private LocalDate reportDate;

  @Column(length = 30)
  private String tone;

  @Column(name = "article_count")
  private Integer articleCount;

  @Column(name = "token_input")
  private Integer tokenInput;

  @Column(name = "token_output")
  private Integer tokenOutput;

  @OneToMany(mappedBy = "briefingReport", cascade = CascadeType.ALL, orphanRemoval = true)
  @OrderBy("displayOrder ASC")
  private List<BriefingArticle> articles = new ArrayList<>();

  protected BriefingReport() {}

  public static BriefingReport create(
      Long userId,
      BriefingJob briefingJob,
      String title,
      String summary,
      String content,
      LocalDate reportDate,
      String tone,
      Integer tokenInput,
      Integer tokenOutput,
      int articleCount) {
    BriefingReport report = new BriefingReport();
    report.userId = userId;
    report.briefingJob = briefingJob;
    report.title = title;
    report.summary = summary;
    report.content = content;
    report.reportDate = reportDate;
    report.tone = tone;
    report.tokenInput = tokenInput;
    report.tokenOutput = tokenOutput;
    report.articleCount = articleCount;
    return report;
  }

  public void addArticle(BriefingArticle article) {
    articles.add(article);
  }

  public Long getId() {
    return id;
  }

  public Long getUserId() {
    return userId;
  }

  public BriefingJob getBriefingJob() {
    return briefingJob;
  }

  public String getTitle() {
    return title;
  }

  public String getSummary() {
    return summary;
  }

  public String getContent() {
    return content;
  }

  public LocalDate getReportDate() {
    return reportDate;
  }

  public String getTone() {
    return tone;
  }

  public Integer getArticleCount() {
    return articleCount;
  }

  public Integer getTokenInput() {
    return tokenInput;
  }

  public Integer getTokenOutput() {
    return tokenOutput;
  }

  public List<BriefingArticle> getArticles() {
    return articles;
  }
}
