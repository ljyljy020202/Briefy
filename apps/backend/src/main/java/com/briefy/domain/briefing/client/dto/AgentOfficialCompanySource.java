package com.briefy.domain.briefing.client.dto;

public record AgentOfficialCompanySource(
    Long companyId, String sourceType, String sourceUrl, String adapterType, String configJson) {}
