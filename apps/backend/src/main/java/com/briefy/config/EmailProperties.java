package com.briefy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "briefy.email")
public record EmailProperties(
    String mode, boolean autoSendEnabled, String from, String fakeOutputDir, String awsRegion) {}
