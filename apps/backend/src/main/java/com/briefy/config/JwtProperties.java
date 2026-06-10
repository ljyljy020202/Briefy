package com.briefy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(
    String secret,
    long expirationSeconds,
    String cookieName,
    boolean cookieSecure,
    String cookieSameSite) {}
