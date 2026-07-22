package com.briefy.domain.company.dto.admin;

import java.util.Map;

public record UpdateCompanySourceRequest(
    String sourceType,
    String sourceUrl,
    String adapterType,
    String status,
    Map<String, Object> config) {}
