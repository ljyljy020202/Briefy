package com.briefy.domain.preference.dto;

import jakarta.validation.constraints.NotNull;
import java.util.Map;

public record PatchBriefingPreferenceRequest(@NotNull Map<String, Object> preference) {}
