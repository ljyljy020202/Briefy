package com.briefy.domain.briefing.dto;

import com.briefy.domain.briefing.entity.BriefingArticle;

public record BriefingArticleResponse(
    String title,
    String source,
    String url,
    String summary,
    String whyItMatters,
    String publishedAt) {

  public static BriefingArticleResponse from(BriefingArticle article) {
    return new BriefingArticleResponse(
        article.getTitle(),
        article.getSource(),
        article.getUrl(),
        article.getSummary(),
        article.getWhyItMatters(),
        article.getPublishedAt() != null ? article.getPublishedAt().toString() : null);
  }
}
