package com.briefy.infra.agent.dto;

import com.briefy.domain.briefing.recommendation.ScoreBreakdown;

public record AgentScoreBreakdown(
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

  public static AgentScoreBreakdown from(ScoreBreakdown bd) {
    return new AgentScoreBreakdown(
        bd.roleScore(),
        bd.companyScore(),
        bd.skillScore(),
        bd.experienceScore(),
        bd.industryScore(),
        bd.locationScore(),
        bd.employmentTypeScore(),
        bd.companySizeScore(),
        bd.relevanceScore(),
        bd.exposurePenalty(),
        bd.adjustedScore());
  }
}
