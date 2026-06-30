package com.briefy.domain.briefing.client.dto;

import java.util.Map;

public record AgentBriefingRequest(
    Long userId,
    String category,
    Map<String, Object> preference,
    String briefingDate,
    String tone,
    AgentCandidatePool candidatePool) {}
