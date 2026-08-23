package com.briefy.infra.agent.dto;

public record AgentCollectionOptions(
    int lookbackDays,
    int discoveryLimitPerSource,
    int detailFetchLimitPerSource,
    int maxResultsPerSource,
    int maxTotalResults) {}
