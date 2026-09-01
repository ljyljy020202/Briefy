package com.briefy.domain.candidatepool.entity.analysis;

/**
 * 분류 단계에서 사용하는 역할 그룹 태그.
 *
 * <p>기존 {@link com.briefy.domain.briefing.policy.RoleGroup} 정책 열거형과 대응하는 값(BACKEND~DEVOPS_INFRA)과
 * 분류 전용 추가 태그(GENERAL_IT, OTHER_IT, NON_DEV)를 포함한다.
 *
 * <p>{@code GENERAL_IT}는 개발 직무 전체와 자동 일치하는 wildcard가 아니다. 구체적인 역할을 파악할 수 없는 IT 직무(예: "IT 직군 모집")에만
 * 사용한다.
 */
public enum RoleGroupTag {
  // ── 기존 RoleGroup 대응 ─────────────────────────────────────────
  BACKEND,
  FRONTEND,
  FULLSTACK,
  DATA,
  AI_ML,
  MOBILE,
  DEVOPS_INFRA,
  // ── 분류 전용 추가 태그 ──────────────────────────────────────────
  /** 구체적 역할이 불명확한 IT 직무. */
  GENERAL_IT,
  /** 개발 외 IT 직무 (QA, 보안, 기술지원, 스크럼 마스터 등). */
  OTHER_IT,
  /** 비개발 직군 (마케팅, 영업, HR, 재무, 법무 등). */
  NON_DEV,
}
