package com.briefy.domain.candidatepool.dto;

import java.util.List;

public record CandidatePoolUpsertResult(
    int collectedCount,
    int savedCount,
    int duplicateCount,
    List<Long> newIds,
    List<Long> updatedIds,
    List<Long> touchedIds) {

  /** 기존 호출 지점(테스트 mock stub)과의 하위 호환 팩토리. */
  public static CandidatePoolUpsertResult of(
      int collectedCount, int savedCount, int duplicateCount) {
    return new CandidatePoolUpsertResult(
        collectedCount, savedCount, duplicateCount, List.of(), List.of(), List.of());
  }
}
