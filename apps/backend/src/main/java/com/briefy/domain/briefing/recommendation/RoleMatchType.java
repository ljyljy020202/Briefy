package com.briefy.domain.briefing.recommendation;

/**
 * 분류 분석 기반 직무 일치 유형.
 *
 * <p>키워드 기반 {@link com.briefy.domain.briefing.policy.JobRolePolicy.Verdict}와 별도로 유지한다.
 */
public enum RoleMatchType {
  /**
   * 사용자 선호 직무와 공고 직무가 직접 일치.
   *
   * <p>단일 직무 공고에서 roleGroups에 선호 그룹이 포함되거나, 다직무 공고에서 해당 track이 선호 직무를 포함하는 경우.
   */
  DIRECT_MATCH,

  /**
   * 공개채용 또는 실제 다직무 공고로, IT 모집 분야가 있고 사용자 조건에 맞는 track이 존재하나 직접 일치는 아닌 경우.
   *
   * <p>단순 공개채용 제목만으로는 부여되지 않는다. IT track 근거와 경력 적합 근거가 모두 있어야 한다.
   */
  BROAD_IT_MATCH,

  /**
   * IT 도메인이나 사용자 선호 직무와 다른 역할만 모집.
   *
   * <p>NON_IT 직무도 이 분류에 포함된다.
   */
  MISMATCH,

  /**
   * 분석 결과가 없거나 유효하지 않아 판별 불가.
   *
   * <p>ENFORCE 모드에서는 DEFER(제외), OFF/SHADOW 모드에서는 키워드 기반으로 폴백.
   */
  UNKNOWN,
}
