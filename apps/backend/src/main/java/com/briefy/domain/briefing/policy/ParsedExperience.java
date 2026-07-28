package com.briefy.domain.briefing.policy;

/**
 * Parsed experience requirement of a job posting.
 *
 * @param category internal classification
 * @param minYears minimum required years; -1 when unknown
 * @param maxYears maximum accepted years; -1 when unknown or unbounded
 * @param explicitMinimumRequired true when a hard minimum (e.g. "3년 이상 필수") is stated
 * @param confidence 0.0–1.0; lower means the category was inferred heuristically
 */
public record ParsedExperience(
    ExperienceCategory category,
    int minYears,
    int maxYears,
    boolean explicitMinimumRequired,
    double confidence) {

  public static ParsedExperience unknown() {
    return new ParsedExperience(ExperienceCategory.UNKNOWN, -1, -1, false, 0.0);
  }
}
