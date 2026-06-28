package com.briefy.domain.briefingpreference.dto;

import jakarta.validation.constraints.NotNull;
import java.util.Map;

public record PatchBriefingPreferenceRequest(@NotNull Map<String, Object> preference) {}
