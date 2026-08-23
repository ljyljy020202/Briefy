package com.briefy.infra.agent.dto;

import java.util.List;

public record AgentCompanyProfile(
    Long id,
    String canonicalName,
    String normalizedName,
    String companySize,
    List<String> industryCodes) {}
