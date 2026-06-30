package com.briefy.domain.briefing.client.dto;

import java.util.List;

public record AgentCollectionResponse(
    Long collectionJobId,
    String collectDate,
    List<AgentCollectedJobPosting> jobPostings,
    List<Object> companyIssues,
    List<Object> industryIssues,
    AgentCollectionStats stats,
    List<String> warnings) {}
