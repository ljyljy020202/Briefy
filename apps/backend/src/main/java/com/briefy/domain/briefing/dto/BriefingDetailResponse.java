package com.briefy.domain.briefing.dto;

import com.briefy.domain.briefing.entity.BriefingReport;
import java.util.List;

public record BriefingDetailResponse(
    Long id,
    String title,
    String summary,
    String content,
    String reportDate,
    List<BriefingArticleResponse> articles) {

  public static BriefingDetailResponse from(BriefingReport report) {
    List<BriefingArticleResponse> articleResponses =
        report.getArticles().stream().map(BriefingArticleResponse::from).toList();
    return new BriefingDetailResponse(
        report.getId(),
        report.getTitle(),
        report.getSummary(),
        report.getContent(),
        report.getReportDate().toString(),
        articleResponses);
  }
}
