package com.briefy.domain.candidatepool.dto;

import java.util.Map;

/** 분류 분석 상태 집계 응답. */
public record AnalysisStatsResponse(
    /** 분석 행이 있는 공고 수 (전체 job_posting_analyses 행 수). */
    long totalAnalysisRows,

    /** 상태별 건수. key = ClassificationStatus 이름. */
    Map<String, Long> byStatus,

    /** 분류 대기 건수 (PENDING). */
    long pendingCount,

    /** 처리 중 건수 (PROCESSING). */
    long processingCount,

    /** LLM 분류 성공 건수 (SUCCEEDED). */
    long succeededCount,

    /** 규칙 기반 폴백 완료 건수 (FALLBACK). */
    long fallbackCount,

    /** 실패 건수 (FAILED). */
    long failedCount,

    /** 충돌 건수 (CONFLICT). */
    long conflictCount) {}
