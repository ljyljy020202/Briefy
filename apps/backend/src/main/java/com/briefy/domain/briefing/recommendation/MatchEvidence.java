package com.briefy.domain.briefing.recommendation;

import java.util.List;

/**
 * Values that actually matched during relevance scoring.
 *
 * <p>Only matched values are included; unmatched or absent fields use empty lists. This object is
 * forwarded to the Agent so it can explain recommendations without hallucinating evidence.
 */
public record MatchEvidence(
    List<String> matchedRoles,
    List<String> matchedCompanies,
    List<String> matchedSkills,
    List<String> matchedIndustries,
    List<String> matchedLocations,
    List<String> matchedExperienceLevels,
    List<String> matchedEmploymentTypes,
    List<String> matchedCompanySizes) {

  public static MatchEvidence empty() {
    return new MatchEvidence(
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
  }
}
