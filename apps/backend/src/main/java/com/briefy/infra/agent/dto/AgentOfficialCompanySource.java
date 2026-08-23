package com.briefy.infra.agent.dto;

public record AgentOfficialCompanySource(
    Long companyId, String sourceType, String sourceUrl, String adapterType, String configJson) {}
