package com.briefy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "briefy.briefing")
public record BriefingProperties(
    int agentRetryMaxAttempts, int agentRetryBackoffSeconds, int jobMaxRetryCount) {

  public BriefingProperties {
    if (agentRetryMaxAttempts < 0 || agentRetryBackoffSeconds < 0 || jobMaxRetryCount < 0) {
      throw new IllegalArgumentException("All briefy.briefing limits must be >= 0");
    }
  }
}
