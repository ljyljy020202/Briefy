package com.briefy.domain.briefing.client.dto;

import java.util.List;

public record AgentCompanyProfile(
    Long id,
    String canonicalName,
    String normalizedName,
    String companySize,
    List<String> industryCodes) {}
