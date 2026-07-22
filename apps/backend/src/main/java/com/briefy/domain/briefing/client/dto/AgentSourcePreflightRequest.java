package com.briefy.domain.briefing.client.dto;

public record AgentSourcePreflightRequest(
    Long sourceId,
    Long companyId,
    String companyName,
    String sourceType,
    String sourceUrl,
    String adapterType,
    String configJson) {}
