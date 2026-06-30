package com.briefy.domain.briefing.client.dto;

public record AgentCollectionOptions(
    int lookbackDays, int deadlineWithinDays, int maxItemsPerSource) {}
