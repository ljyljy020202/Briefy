package com.briefy.domain.company.dto.admin;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record CreateCompanyRequest(
    @NotBlank String canonicalName, String companySize, List<String> industryCodes) {}
