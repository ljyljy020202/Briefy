package com.briefy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "briefy.scheduler")
public record SchedulerProperties(
    boolean enabled, String dailyCollectionCron, String dailyBriefingCron, String zone) {}
