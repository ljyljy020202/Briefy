package com.briefy.infra.agent.dto;

import java.util.List;

public record AgentCollectionResponse(
    Long collectionJobId,
    String collectDate,
    List<AgentCollectedJobPosting> jobPostings,
    List<Object> companyIssues,
    List<Object> industryIssues,
    AgentCollectionStats stats,
    List<AgentSourceOutcome> sourceOutcomes,
    List<String> warnings) {}
