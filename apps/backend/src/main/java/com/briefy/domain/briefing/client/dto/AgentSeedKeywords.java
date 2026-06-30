package com.briefy.domain.briefing.client.dto;

import java.util.List;

public record AgentSeedKeywords(
    List<String> roles,
    List<String> companies,
    List<String> skills,
    List<String> locations,
    List<String> experienceLevels,
    List<String> employmentTypes,
    List<String> industries,
    List<String> keywords) {}
