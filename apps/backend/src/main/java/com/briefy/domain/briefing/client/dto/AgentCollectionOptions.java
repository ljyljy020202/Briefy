package com.briefy.domain.briefing.client.dto;

public record AgentCollectionOptions(
    int lookbackDays,
    int discoveryLimitPerSource,
    int detailFetchLimitPerSource,
    int maxResultsPerSource,
    int maxTotalResults) {}
