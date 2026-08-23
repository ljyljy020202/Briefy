package com.briefy.infra.agent.dto;

public record AgentCollectionStats(
    int discoveredCount,
    int fetchedCount,
    int parsedCount,
    int duplicateCount,
    int filteredCount,
    int truncatedCount,
    int finalCount) {}
