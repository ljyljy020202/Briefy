package com.briefy.domain.briefing.recommendation;

import java.util.List;

/**
 * Values that actually matched during relevance scoring.
 *
 * <p>Only matched values are included; unmatched or absent fields use empty lists. This object is
 * forwarded to the Agent so it can explain recommendations without hallucinating evidence.
 *
 * <p>{@code analysisRoleMatch} is non-null only when classification-aware scoring was applied
 * (SHADOW or ENFORCE mode with a valid analysis). The Agent uses it to choose the correct
 * match-type language for the briefing summary.
 */
public record MatchEvidence(
    List<String> matchedRoles,
    List<String> matchedCompanies,
    List<String> matchedSkills,
    List<String> matchedIndustries,
    List<String> matchedLocations,
    List<String> matchedExperienceLevels,
    List<String> matchedEmploymentTypes,
    List<String> matchedCompanySizes,
    /** null when no valid analysis was used. */
    String analysisRoleMatch) {

  public static MatchEvidence empty() {
    return new MatchEvidence(
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
        null);
  }

  /** Convenience constructor for callers that do not supply analysis context. */
  public static MatchEvidence of(
      List<String> matchedRoles,
      List<String> matchedCompanies,
      List<String> matchedSkills,
      List<String> matchedIndustries,
      List<String> matchedLocations,
      List<String> matchedExperienceLevels,
      List<String> matchedEmploymentTypes,
      List<String> matchedCompanySizes) {
    return new MatchEvidence(
        matchedRoles,
        matchedCompanies,
        matchedSkills,
        matchedIndustries,
        matchedLocations,
        matchedExperienceLevels,
        matchedEmploymentTypes,
        matchedCompanySizes,
        null);
  }
}
