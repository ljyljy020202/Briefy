package com.briefy.domain.collection.dto;

import java.time.LocalDate;

public record DailyCollectionResult(
    Long collectionJobId,
    String status,
    LocalDate collectDate,
    int collectedCount,
    int savedCount,
    int deduplicatedCount,
    String errorMessage) {}
