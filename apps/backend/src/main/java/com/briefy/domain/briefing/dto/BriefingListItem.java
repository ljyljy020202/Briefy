package com.briefy.domain.briefing.dto;

import com.briefy.domain.briefing.entity.BriefingReport;

public record BriefingListItem(
    Long id, String title, String summary, String reportDate, int articleCount, String createdAt) {

  public static BriefingListItem from(BriefingReport report) {
    return new BriefingListItem(
        report.getId(),
        report.getTitle(),
        report.getSummary(),
        report.getReportDate().toString(),
        report.getArticleCount() != null ? report.getArticleCount() : 0,
        report.getCreatedAt() != null ? report.getCreatedAt().toString() : null);
  }
}
