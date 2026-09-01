package com.briefy.domain.candidatepool.entity.analysis;

public enum ExperienceRequirementType {
  /** 필수 경력 ("N년 이상 필수"). */
  REQUIRED,
  /** 우대 경력 ("N년 이상 우대"). 필수 경력 필드(minRequiredYears 등)에 저장하지 않는다. */
  PREFERRED,
  /** 경력 무관. */
  NONE,
  /** 판별 불가. */
  UNKNOWN,
}
