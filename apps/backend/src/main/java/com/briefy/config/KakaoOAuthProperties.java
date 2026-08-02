package com.briefy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "kakao")
public record KakaoOAuthProperties(String clientId, String clientSecret, String redirectUri) {}
