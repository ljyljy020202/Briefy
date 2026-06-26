package com.briefy.domain.briefing.client.dto;

import java.util.List;

public record AgentBriefingResponse(
    String title,
    String summary,
    String content,
    List<AgentArticle> articles,
    TokenUsage tokenUsage) {

  public record AgentArticle(
      String title,
      String source,
      String url,
      String summary,
      String whyItMatters,
      String publishedAt) {}

  public record TokenUsage(Integer inputTokens, Integer outputTokens) {}
}
