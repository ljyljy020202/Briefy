package com.briefy.infra.agent.dto;

import com.briefy.domain.briefing.recommendation.MatchEvidence;
import java.util.List;

public record AgentMatchEvidence(
    List<String> matchedRoles,
    List<String> matchedCompanies,
    List<String> matchedSkills,
    List<String> matchedIndustries,
    List<String> matchedLocations,
    List<String> matchedExperienceLevels,
    List<String> matchedEmploymentTypes,
    List<String> matchedCompanySizes) {

  public static AgentMatchEvidence from(MatchEvidence ev) {
    return new AgentMatchEvidence(
        ev.matchedRoles(),
        ev.matchedCompanies(),
        ev.matchedSkills(),
        ev.matchedIndustries(),
        ev.matchedLocations(),
        ev.matchedExperienceLevels(),
        ev.matchedEmploymentTypes(),
        ev.matchedCompanySizes());
  }
}
