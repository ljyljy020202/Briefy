package com.briefy.infra.agent.dto;

import java.util.Map;

public record AgentBriefingRequest(
    Long userId,
    String category,
    Map<String, Object> preference,
    String briefingDate,
    String tone,
    AgentCandidatePool candidatePool) {}
