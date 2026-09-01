package com.briefy.domain.candidatepool.entity.analysis;

public enum ClassificationMethod {
  /** LLM 기반 분류. */
  LLM,
  /** 규칙(키워드·정규식) 기반 분류. LLM 실패 시 폴백으로 사용. */
  RULE_BASED,
  /** 수동 분류. */
  MANUAL,
}
