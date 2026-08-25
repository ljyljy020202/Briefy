package com.briefy.domain.briefing.recommendation;

/**
 * Result of {@link RecommendationFilter#evaluate}.
 *
 * <p>When {@code eligible} is {@code true}, {@code reason} is {@code null}. When {@code eligible}
 * is {@code false}, {@code reason} carries the authoritative rejection cause.
 */
public record FilterResult(boolean eligible, FilterReason reason) {

  public static FilterResult pass() {
    return new FilterResult(true, null);
  }

  public static FilterResult exclude(FilterReason reason) {
    return new FilterResult(false, reason);
  }
}
