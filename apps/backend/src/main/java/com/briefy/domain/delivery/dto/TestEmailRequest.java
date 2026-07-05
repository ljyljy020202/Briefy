package com.briefy.domain.delivery.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record TestEmailRequest(
    @NotBlank @Email String to, @NotBlank String subject, @NotBlank String content) {}
