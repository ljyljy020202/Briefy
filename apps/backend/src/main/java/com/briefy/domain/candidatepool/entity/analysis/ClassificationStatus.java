package com.briefy.domain.candidatepool.entity.analysis;

public enum ClassificationStatus {
  /** 분류 대기 중. 분석 행이 생성된 초기 상태. */
  PENDING,
  /** 작업자가 클레임한 후 분류 진행 중. */
  PROCESSING,
  /** LLM 또는 규칙 기반으로 분류 완료. */
  SUCCEEDED,
  /** LLM 실패 후 규칙 기반 폴백으로 분류 완료. */
  FALLBACK,
  /** 분류 실패. attemptCount, nextRetryAt에 따라 재시도 가능. */
  FAILED,
  /** analysisInputHash 불일치 등 수동 재해결이 필요한 충돌 상태. */
  CONFLICT,
}
