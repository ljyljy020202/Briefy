package com.briefy.infra.agent.dto;

import java.util.List;

public record AgentBriefingResponse(
    String title,
    String summary,
    String content,
    List<AgentArticle> articles,
    TokenUsage tokenUsage,
    String generationMode,
    Boolean usedFallback,
    String fallbackReason,
    Integer rewriteCount) {

  public record AgentArticle(
      String title,
      String source,
      String url,
      String summary,
      String whyItMatters,
      String publishedAt) {}

  public record TokenUsage(Integer inputTokens, Integer outputTokens) {}

  /** Returns generationMode, defaulting to "LLM" if null (e.g. older Agent version). */
  public String generationModeOrDefault() {
    return generationMode != null ? generationMode : "LLM";
  }

  /** Safe null-check for usedFallback. */
  public boolean isUsedFallback() {
    return Boolean.TRUE.equals(usedFallback);
  }

  /** Returns rewriteCount, defaulting to 0 if null. */
  public int rewriteCountOrZero() {
    return rewriteCount != null ? rewriteCount : 0;
  }
}
