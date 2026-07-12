package com.briefy.domain.collection.dto;

import com.briefy.domain.briefing.client.dto.AgentCollectionStats;
import java.time.LocalDate;
import java.util.List;

public record DailyCollectionResult(
    Long collectionJobId,
    String status,
    LocalDate collectDate,
    AgentCollectionStats agentStats,
    int savedCount,
    int persistenceDuplicateCount,
    List<String> warnings,
    String errorMessage) {}
