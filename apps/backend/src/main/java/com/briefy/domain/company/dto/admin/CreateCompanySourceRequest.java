package com.briefy.domain.company.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

public record CreateCompanySourceRequest(
    @NotNull Long companyId,
    @NotBlank String sourceType,
    String sourceUrl,
    @NotBlank String adapterType,
    @NotBlank String status,
    Map<String, Object> config) {}
