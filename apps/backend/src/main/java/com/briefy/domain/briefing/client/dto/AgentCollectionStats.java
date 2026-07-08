package com.briefy.domain.briefing.client.dto;

public record AgentCollectionStats(
    int discoveredCount,
    int fetchedCount,
    int parsedCount,
    int duplicateCount,
    int filteredCount,
    int truncatedCount,
    int finalCount) {}
