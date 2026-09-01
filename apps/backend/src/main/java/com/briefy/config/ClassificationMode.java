package com.briefy.config;

/**
 * 분류 작업자 실행 모드.
 *
 * <ul>
 *   <li>{@code OFF} — 자동 분류와 새 추천 정책 비활성. 기존 키워드 기반 로직 유지. backfill API로 PENDING 등록은 가능하나 작업자가 처리하지
 *       않음.
 *   <li>{@code SHADOW} — 분석 실행·저장. 실제 추천은 기존 정책 유지 (A/B 비교용).
 *   <li>{@code ENFORCE} — 분석 실행·저장. 분류 기반 새 적합성 정책 적용.
 * </ul>
 */
public enum ClassificationMode {
  OFF,
  SHADOW,
  ENFORCE,
}
