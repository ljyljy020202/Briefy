package com.briefy.domain.briefing.recommendation;

/**
 * Typed breakdown of how a posting's score was computed.
 *
 * <p>{@code relevanceScore} = sum of the preference-based components plus {@code
 * openRecruitmentScore} and {@code sourceScore}. {@code adjustedScore} = relevanceScore −
 * exposurePenalty (always an exact subtraction — no other adjustments here).
 *
 * <p>Time-based signals (recency bonus, urgency bonus) are intentionally absent — those are
 * computed downstream in the selector layer and must never be added here.
 *
 * <p>Editorial bonuses:
 *
 * <ul>
 *   <li>{@code openRecruitmentScore} — classification-derived bonus for large-enterprise open
 *       recruitment; populated only via the classification-aware scoring path in {@link
 *       RelevanceScorer} and is 0 for keyword-only scoring.
 *   <li>{@code sourceScore} — bonus for postings collected from an official company career site
 *       (i.e. not an aggregator such as jasoseol/saramin); applied in all scoring paths.
 * </ul>
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
    int openRecruitmentScore,
    int sourceScore,
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
    return ofRelevance(
        roleScore,
        companyScore,
        skillScore,
        experienceScore,
        industryScore,
        locationScore,
        employmentTypeScore,
        companySizeScore,
        0,
        0);
  }

  /** Creates a breakdown including the open-recruitment and official-source editorial bonuses. */
  public static ScoreBreakdown ofRelevance(
      int roleScore,
      int companyScore,
      int skillScore,
      int experienceScore,
      int industryScore,
      int locationScore,
      int employmentTypeScore,
      int companySizeScore,
      int openRecruitmentScore,
      int sourceScore) {
    int relevance =
        roleScore
            + companyScore
            + skillScore
            + experienceScore
            + industryScore
            + locationScore
            + employmentTypeScore
            + companySizeScore
            + openRecruitmentScore
            + sourceScore;
    return new ScoreBreakdown(
        roleScore,
        companyScore,
        skillScore,
        experienceScore,
        industryScore,
        locationScore,
        employmentTypeScore,
        companySizeScore,
        openRecruitmentScore,
        sourceScore,
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
        openRecruitmentScore,
        sourceScore,
        relevanceScore,
        penalty,
        relevanceScore - penalty);
  }
}
