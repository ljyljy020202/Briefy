package com.briefy.domain.company.dto.admin;

import jakarta.validation.constraints.NotBlank;

public record CreateAliasRequest(@NotBlank String alias) {}
