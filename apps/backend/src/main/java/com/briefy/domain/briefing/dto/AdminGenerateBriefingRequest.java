package com.briefy.domain.briefing.dto;

import jakarta.validation.constraints.NotNull;

public record AdminGenerateBriefingRequest(@NotNull Long userId) {}
