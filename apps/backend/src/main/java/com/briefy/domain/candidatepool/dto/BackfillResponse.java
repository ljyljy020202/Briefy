package com.briefy.domain.candidatepool.dto;

import java.util.Map;

/** 관리자 backfill 응답. */
public record BackfillResponse(
    /** 요청이 dryRun이었는지 여부. */
    boolean dryRun,

    /** 처리 대상으로 선택된 공고 수. */
    int targetCount,

    /** 실제로 PENDING으로 등록된 공고 수. dryRun이면 0. */
    int enqueuedCount,

    /** 건너뛴 공고 수 (이미 완료, 동일 hash+version). */
    int skippedCount,

    /** 처리 대상으로 선택된 이유별 건수. 예: {"NO_ANALYSIS_ROW": 5, "VERSION_CHANGED": 3} */
    Map<String, Integer> reasons) {}
