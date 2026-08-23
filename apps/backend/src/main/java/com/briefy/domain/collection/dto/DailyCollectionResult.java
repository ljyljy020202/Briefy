package com.briefy.domain.collection.dto;

import com.briefy.infra.agent.dto.AgentCollectionStats;
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
