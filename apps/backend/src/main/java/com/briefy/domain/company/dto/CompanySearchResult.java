package com.briefy.domain.company.dto;

import java.util.List;

public record CompanySearchResult(
    Long id, String canonicalName, String companySize, List<String> industryCodes) {}
