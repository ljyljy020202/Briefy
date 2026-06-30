package com.briefy.domain.candidatepool.dto;

public record CandidatePoolUpsertResult(int collectedCount, int savedCount, int duplicateCount) {}
