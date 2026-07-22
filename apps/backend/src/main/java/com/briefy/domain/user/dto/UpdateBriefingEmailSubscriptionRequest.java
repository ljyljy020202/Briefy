package com.briefy.domain.user.dto;

import jakarta.validation.constraints.NotNull;

public record UpdateBriefingEmailSubscriptionRequest(@NotNull Boolean enabled) {}
