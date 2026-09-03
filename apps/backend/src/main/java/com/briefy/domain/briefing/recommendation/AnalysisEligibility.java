package com.briefy.domain.briefing.recommendation;

import com.briefy.domain.candidatepool.entity.analysis.PostingScope;
import com.briefy.domain.candidatepool.entity.analysis.PostingTrack;
import com.briefy.domain.candidatepool.entity.analysis.RecruitmentType;
import java.util.List;

/**
 * 분류 분석 결과로부터 도출한 공통 적합성 판정 객체.
 *
 * <p>{@link RecommendationFilter}와 {@link RelevanceScorer} 양쪽에서 동일한 인스턴스를 소비하여 판단 불일치를 방지한다. 후보마다
 * 분석 Repository를 호출하지 않도록 호출부에서 일괄 조회 후 전달한다.
 *
 * @param eligible true이면 필터 통과 (ENFORCE 모드 기준)
 * @param roleMatch 직무 일치 유형
 * @param experienceMatch 경력 일치 유형 (roleMatch가 결정된 track 기준)
 * @param matchedTrack 다직무 공고에서 매칭된 track (단일 직무이면 null)
 * @param reasons 판정 근거 요약 (진단용)
 * @param evidence 분석 evidence 원문 (Agent에 전달)
 * @param analysisInputHash 판정에 사용한 분석의 입력 hash
 * @param classifierVersion 판정에 사용한 분석의 분류 버전
 * @param postingScope 공고 모집 범위 (대기업 공채 가산점 판단용, deferred/nonIt이면 null)
 * @param recruitmentType 채용 유형 (신입 공채 가산점 판단용, deferred/nonIt이면 null)
 */
public record AnalysisEligibility(
    boolean eligible,
    RoleMatchType roleMatch,
    ExperienceMatchType experienceMatch,
    PostingTrack matchedTrack,
    List<String> reasons,
    String evidence,
    String analysisInputHash,
    String classifierVersion,
    PostingScope postingScope,
    RecruitmentType recruitmentType) {

  /**
   * 공고 컨텍스트(scope/recruitmentType) 없이 생성하는 편의 생성자. 판정 로직 내부에서 사용하며, 필요 시 {@link
   * #withPostingContext(PostingScope, RecruitmentType)}로 컨텍스트를 채운다.
   */
  public AnalysisEligibility(
      boolean eligible,
      RoleMatchType roleMatch,
      ExperienceMatchType experienceMatch,
      PostingTrack matchedTrack,
      List<String> reasons,
      String evidence,
      String analysisInputHash,
      String classifierVersion) {
    this(
        eligible,
        roleMatch,
        experienceMatch,
        matchedTrack,
        reasons,
        evidence,
        analysisInputHash,
        classifierVersion,
        null,
        null);
  }

  /** 판정 결과를 유지하면서 공고 모집 범위·채용 유형을 채운 새 인스턴스를 반환한다. */
  public AnalysisEligibility withPostingContext(PostingScope scope, RecruitmentType recruitment) {
    return new AnalysisEligibility(
        eligible,
        roleMatch,
        experienceMatch,
        matchedTrack,
        reasons,
        evidence,
        analysisInputHash,
        classifierVersion,
        scope,
        recruitment);
  }

  /** 분석이 없거나 유효하지 않아 판정 보류. ENFORCE 모드에서는 제외, 그 외에는 키워드 기반 폴백. */
  public static AnalysisEligibility deferred(String reason) {
    return new AnalysisEligibility(
        false,
        RoleMatchType.UNKNOWN,
        ExperienceMatchType.UNKNOWN,
        null,
        List.of(reason),
        null,
        null,
        null);
  }

  /** NON_IT 직군 → 제외. */
  public static AnalysisEligibility nonIt() {
    return new AnalysisEligibility(
        false,
        RoleMatchType.MISMATCH,
        ExperienceMatchType.UNKNOWN,
        null,
        List.of("NON_IT 직군"),
        null,
        null,
        null);
  }

  public boolean isDeferred() {
    return roleMatch == RoleMatchType.UNKNOWN;
  }

  /** True when this exclusion was triggered by a NON_IT domain classification. */
  public boolean isNonItExclusion() {
    return !eligible
        && roleMatch == RoleMatchType.MISMATCH
        && reasons != null
        && !reasons.isEmpty()
        && "NON_IT 직군".equals(reasons.get(0));
  }
}
