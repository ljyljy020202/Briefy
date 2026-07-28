package com.briefy.domain.briefing.policy;

public enum ExperienceCategory {
  /** 신입, 신입사원, 졸업예정자, 인턴 (experience-level context). */
  NEW_GRAD,
  /** 경력 무관, 무관, 0~1년. */
  ENTRY,
  /** 1년+~2년 이하, 주니어. */
  JUNIOR,
  /** 3년 이상, 시니어, 명시적 고경력. */
  EXPERIENCED,
  /** 신입/경력 혼합. */
  MIXED,
  /** 판정 불가. */
  UNKNOWN,
}
