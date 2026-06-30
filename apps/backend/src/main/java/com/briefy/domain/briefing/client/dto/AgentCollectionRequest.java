package com.briefy.domain.briefing.client.dto;

import java.util.List;

public record AgentCollectionRequest(
    Long collectionJobId,
    String collectDate,
    List<String> categories,
    AgentSeedKeywords seedKeywords,
    AgentCollectionOptions options) {}
