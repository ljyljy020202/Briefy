package com.briefy.domain.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

public record UpdateOnboardingRequest(
    @Size(max = 100) String nickname, @Email @Size(max = 255) String reportEmail) {}
