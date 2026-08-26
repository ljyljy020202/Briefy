package com.briefy.infra.agent.dto;

public record AgentSourceOutcome(
    String sourceName, boolean success, int postingCount, String errorSummary) {}
