package com.briefy.domain.briefing.client.dto;

public record AgentCollectionStats(
    int collectedCount,
    int deduplicatedCount,
    int jobPostingCount,
    int companyIssueCount,
    int industryIssueCount) {}
