package com.briefy.domain.preference.dto;

import jakarta.validation.constraints.NotNull;
import java.util.Map;

public record UpsertBriefingPreferenceRequest(
    @NotNull Long categoryId, @NotNull Map<String, Object> preference) {}
