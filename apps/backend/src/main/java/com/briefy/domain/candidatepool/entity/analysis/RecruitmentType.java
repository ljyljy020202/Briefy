package com.briefy.domain.candidatepool.entity.analysis;

/**
 * 채용 유형. 고용 형태(정규직/계약직 등 {@code employmentType})와 혼합하지 않는다.
 *
 * <ul>
 *   <li>{@code EXPERIENCED_HIRE} — 경력직 수시채용
 *   <li>{@code NEW_GRAD_HIRE} — 신입 한정 공개채용
 *   <li>{@code OPEN_HIRE} — 신입/경력 모두 지원 가능
 *   <li>{@code INTERNSHIP} — 인턴십
 *   <li>{@code UNKNOWN} — 판별 불가
 * </ul>
 */
public enum RecruitmentType {
  EXPERIENCED_HIRE,
  NEW_GRAD_HIRE,
  OPEN_HIRE,
  INTERNSHIP,
  UNKNOWN,
}
