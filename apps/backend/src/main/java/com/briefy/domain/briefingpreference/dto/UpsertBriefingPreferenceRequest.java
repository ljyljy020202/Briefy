package com.briefy.domain.briefingpreference.dto;

import jakarta.validation.constraints.NotNull;
import java.util.Map;

public record UpsertBriefingPreferenceRequest(
    @NotNull Long categoryId, @NotNull Map<String, Object> preference) {}
