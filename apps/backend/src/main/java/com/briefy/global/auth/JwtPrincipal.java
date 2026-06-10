package com.briefy.global.auth;

public record JwtPrincipal(Long userId, String email, String role) {}
