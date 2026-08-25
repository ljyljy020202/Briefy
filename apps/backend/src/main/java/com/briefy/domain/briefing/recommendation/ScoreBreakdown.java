package com.briefy.domain.briefing.recommendation;

/**
 * Typed breakdown of how a posting's score was computed.
 *
 * <p>{@code relevanceScore} = sum of the eight preference-based components. {@code adjustedScore} =
 * relevanceScore − exposurePenalty (always an exact subtraction — no other adjustments here).
 *
 * <p>Time-based or editorial signals (recency bonus, urgency bonus) are intentionally absent. Those
 * are computed downstream in the selector layer and must never be added here.
 */
public record ScoreBreakdown(
    int roleScore,
    int companyScore,
    int skillScore,
    int experienceScore,
    int industryScore,
    int locationScore,
    int employmentTypeScore,
    int companySizeScore,
    int relevanceScore,
    int exposurePenalty,
    int adjustedScore) {

  /**
   * Creates a breakdown that contains pure relevance only (no exposure penalty applied yet).
   * exposurePenalty = 0, adjustedScore = relevanceScore.
   */
  public static ScoreBreakdown ofRelevance(
      int roleScore,
      int companyScore,
      int skillScore,
      int experienceScore,
      int industryScore,
      int locationScore,
      int employmentTypeScore,
      int companySizeScore) {
    int relevance =
        roleScore
            + companyScore
            + skillScore
            + experienceScore
            + industryScore
            + locationScore
            + employmentTypeScore
            + companySizeScore;
    return new ScoreBreakdown(
        roleScore,
        companyScore,
        skillScore,
        experienceScore,
        industryScore,
        locationScore,
        employmentTypeScore,
        companySizeScore,
        relevance,
        0,
        relevance);
  }

  /** Returns a new breakdown with the given exposure penalty applied. */
  public ScoreBreakdown withExposurePenalty(int penalty) {
    return new ScoreBreakdown(
        roleScore,
        companyScore,
        skillScore,
        experienceScore,
        industryScore,
        locationScore,
        employmentTypeScore,
        companySizeScore,
        relevanceScore,
        penalty,
        relevanceScore - penalty);
  }
}
