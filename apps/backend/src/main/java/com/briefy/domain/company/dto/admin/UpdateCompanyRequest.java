package com.briefy.domain.company.dto.admin;

import java.util.List;

public record UpdateCompanyRequest(
    String canonicalName, String companySize, List<String> industryCodes) {}
