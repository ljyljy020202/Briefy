package com.briefy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "briefy.collection")
public record CollectionProperties(
    int lookbackDays,
    int discoveryLimitPerSource,
    int detailFetchLimitPerSource,
    int maxResultsPerSource,
    int maxTotalResults) {

  public CollectionProperties {
    if (lookbackDays < 1
        || discoveryLimitPerSource < 1
        || detailFetchLimitPerSource < 1
        || maxResultsPerSource < 1
        || maxTotalResults < 1) {
      throw new IllegalArgumentException("All briefy.collection limits must be >= 1");
    }
  }
}
