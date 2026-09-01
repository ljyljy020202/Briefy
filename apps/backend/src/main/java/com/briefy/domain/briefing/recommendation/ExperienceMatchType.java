package com.briefy.domain.briefing.recommendation;

/**
 * 분류 분석 기반 경력 일치 유형.
 *
 * <p>roleMatch가 결정된 track 또는 posting 수준에서 평가한다. 다직무 공고에서는 roleMatch track의 경력 조건을 사용하며, 다른 track의
 * 값과 섞지 않는다.
 */
public enum ExperienceMatchType {
  /**
   * 사용자 경력 조건이 해당 직무 track의 조건과 완전히 일치.
   *
   * <p>경력 점수 보너스(+15)를 부여한다.
   */
  FULL,

  /**
   * 지원은 가능하나 경력 조건이 완전히 일치하지 않음.
   *
   * <p>우대 경력만 존재하거나, 경력 정보 자체가 없는 경우. 경력 점수 보너스 없음.
   */
  PARTIAL,

  /**
   * 해당 track의 필수 경력 조건이 사용자를 제외함.
   *
   * <p>신입 사용자가 필수 경력 1년 이상 track을 만난 경우 등.
   */
  EXCLUDED,

  /**
   * 경력 정보를 판별할 수 없음.
   *
   * <p>분석 자체가 UNKNOWN이거나 track 경력 정보가 누락된 경우. 점수 보너스 없음.
   */
  UNKNOWN,
}
