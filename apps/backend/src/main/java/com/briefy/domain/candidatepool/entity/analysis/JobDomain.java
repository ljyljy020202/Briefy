package com.briefy.domain.candidatepool.entity.analysis;

public enum JobDomain {
  /** IT/개발/엔지니어링 직군. */
  IT,
  /** 비IT 직군 (영업, 마케팅, HR, 재무 등). */
  NON_IT,
  /** IT와 비IT 직군이 모두 포함된 공채. */
  MIXED,
  /** 제목·설명만으로 판별 불가. */
  UNKNOWN,
}
