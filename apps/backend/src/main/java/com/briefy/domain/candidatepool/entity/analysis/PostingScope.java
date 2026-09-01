package com.briefy.domain.candidatepool.entity.analysis;

public enum PostingScope {
  /** 단일 직무 공고. */
  ROLE_SPECIFIC,
  /** 여러 직무를 트랙별로 모집하는 공고. */
  MULTI_ROLE,
  /** 공개채용 (트랙 구분 없거나 전 직군 모집). */
  OPEN_RECRUITMENT,
  /** 판별 불가. */
  UNKNOWN,
}
