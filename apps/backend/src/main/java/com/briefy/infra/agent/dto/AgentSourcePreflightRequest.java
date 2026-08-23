package com.briefy.infra.agent.dto;

public record AgentSourcePreflightRequest(
    Long sourceId,
    Long companyId,
    String companyName,
    String sourceType,
    String sourceUrl,
    String adapterType,
    String configJson) {}
