package com.briefy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "briefy.collection")
public record CollectionProperties(
    int lookbackDays,
    int discoveryLimitPerSource,
    int detailFetchLimitPerSource,
    int maxResultsPerSource,
    int maxTotalResults,
    int agentRetryMaxAttempts,
    int agentRetryBackoffSeconds,
    int jobMaxRetryCount) {

  public CollectionProperties {
    if (lookbackDays < 1
        || discoveryLimitPerSource < 1
        || detailFetchLimitPerSource < 1
        || maxResultsPerSource < 1
        || maxTotalResults < 1
        || agentRetryMaxAttempts < 0
        || agentRetryBackoffSeconds < 0
        || jobMaxRetryCount < 0) {
      throw new IllegalArgumentException(
          "All briefy.collection limits must be >= 0 (retry) or >= 1 (others)");
    }
  }
}
