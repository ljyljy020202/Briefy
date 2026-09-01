package com.briefy.domain.briefing.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import com.briefy.config.ClassificationMode;
import com.briefy.domain.candidatepool.entity.JobPostingAnalysis;
import com.briefy.domain.candidatepool.entity.analysis.ClassificationStatus;
import com.briefy.domain.candidatepool.entity.analysis.ExperienceRequirementType;
import com.briefy.domain.candidatepool.entity.analysis.JobDomain;
import com.briefy.domain.candidatepool.entity.analysis.PostingScope;
import com.briefy.domain.candidatepool.entity.analysis.PostingTrack;
import com.briefy.domain.candidatepool.entity.analysis.RecruitmentType;
import com.briefy.domain.candidatepool.entity.analysis.RoleGroupTag;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link ClassificationAnalyzer} 단위 테스트.
 *
 * <p>스펙 명세된 회귀 시나리오를 포함한다. 실제 LLM 분류 품질이 아닌 분류 결과를 추천에 적용하는 로직만 검증한다.
 */
class ClassificationAnalyzerTest {

  // ── 헬퍼 ──────────────────────────────────────────────────────────────────────

  private static JobPostingAnalysis analysis(
      JobDomain domain,
      PostingScope scope,
      List<RoleGroupTag> roleGroups,
      Boolean acceptsNewGrad,
      Integer minRequiredYears,
      ExperienceRequirementType reqType,
      RecruitmentType recruitmentType,
      List<PostingTrack> tracks) {
    JobPostingAnalysis a = JobPostingAnalysis.pending(1L, "hash", "1.0");
    a.applyResult(
        domain,
        scope,
        roleGroups,
        recruitmentType,
        tracks,
        acceptsNewGrad,
        minRequiredYears,
        null,
        reqType,
        null, // preferredExperience
        com.briefy.domain.candidatepool.entity.analysis.ClassificationMethod.LLM,
        ClassificationStatus.SUCCEEDED,
        "evidence",
        null,
        0.9,
        0.8,
        false,
        "test-model",
        LocalDateTime.now());
    return a;
  }

  private static PostingTrack track(
      String label,
      List<RoleGroupTag> roleGroups,
      Boolean acceptsNewGrad,
      Integer minRequiredYears,
      ExperienceRequirementType reqType,
      RecruitmentType recruitmentType,
      boolean unknown) {
    return new PostingTrack(
        label,
        null,
        roleGroups,
        acceptsNewGrad,
        minRequiredYears,
        null,
        reqType,
        recruitmentType,
        null,
        "track evidence",
        unknown);
  }

  private static Map<String, Object> backendPref() {
    return Map.of("roles", List.of("백엔드 개발자"), "experienceLevels", List.of());
  }

  private static Map<String, Object> backendNewGradPref() {
    return Map.of("roles", List.of("백엔드 개발자"), "experienceLevels", List.of("신입"));
  }

  private static Map<String, Object> newGradPref() {
    return Map.of("roles", List.of(), "experienceLevels", List.of("신입"));
  }

  // ═══════════════════════════════════════════════════════════════════════
  // 1. NON_IT 직군 → 선호 기업이어도 제외
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  void nonIt_isExcluded_regardlessOfCompanyPreference() {
    JobPostingAnalysis a =
        analysis(
            JobDomain.NON_IT,
            PostingScope.ROLE_SPECIFIC,
            List.of(RoleGroupTag.NON_DEV),
            null,
            null,
            null,
            null,
            List.of());

    AnalysisEligibility result =
        ClassificationAnalyzer.derive(a, backendPref(), ClassificationMode.ENFORCE);

    assertThat(result.eligible()).isFalse();
    assertThat(result.roleMatch()).isEqualTo(RoleMatchType.MISMATCH);
    assertThat(result.isNonItExclusion()).isTrue();
  }

  // ═══════════════════════════════════════════════════════════════════════
  // 2. 같은 기업 Backend 공고는 통과
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  void backend_posting_passes_for_backend_user() {
    JobPostingAnalysis a =
        analysis(
            JobDomain.IT,
            PostingScope.ROLE_SPECIFIC,
            List.of(RoleGroupTag.BACKEND),
            null,
            null,
            ExperienceRequirementType.NONE,
            RecruitmentType.EXPERIENCED_HIRE,
            List.of());

    AnalysisEligibility result =
        ClassificationAnalyzer.derive(a, backendPref(), ClassificationMode.ENFORCE);

    assertThat(result.eligible()).isTrue();
    assertThat(result.roleMatch()).isEqualTo(RoleMatchType.DIRECT_MATCH);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // 3. AML Manager(NON_IT) 제외, AML Backend Developer(IT/BACKEND) 통과
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  void amlManager_nonIt_excluded() {
    JobPostingAnalysis a =
        analysis(
            JobDomain.NON_IT,
            PostingScope.ROLE_SPECIFIC,
            List.of(RoleGroupTag.NON_DEV),
            null,
            null,
            null,
            null,
            List.of());

    AnalysisEligibility result =
        ClassificationAnalyzer.derive(a, backendPref(), ClassificationMode.ENFORCE);

    assertThat(result.eligible()).isFalse();
    assertThat(result.isNonItExclusion()).isTrue();
  }

  @Test
  void amlBackendDeveloper_it_backend_passes() {
    JobPostingAnalysis a =
        analysis(
            JobDomain.IT,
            PostingScope.ROLE_SPECIFIC,
            List.of(RoleGroupTag.BACKEND),
            null,
            null,
            ExperienceRequirementType.REQUIRED,
            RecruitmentType.EXPERIENCED_HIRE,
            List.of());

    AnalysisEligibility result =
        ClassificationAnalyzer.derive(a, backendPref(), ClassificationMode.ENFORCE);

    assertThat(result.eligible()).isTrue();
    assertThat(result.roleMatch()).isEqualTo(RoleMatchType.DIRECT_MATCH);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // 4. OTHER_IT를 Backend로 추천하지 않음
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  void otherIt_notRecommendedForBackendUser() {
    JobPostingAnalysis a =
        analysis(
            JobDomain.IT,
            PostingScope.ROLE_SPECIFIC,
            List.of(RoleGroupTag.OTHER_IT),
            null,
            null,
            ExperienceRequirementType.NONE,
            RecruitmentType.EXPERIENCED_HIRE,
            List.of());

    AnalysisEligibility result =
        ClassificationAnalyzer.derive(a, backendPref(), ClassificationMode.ENFORCE);

    assertThat(result.eligible()).isFalse();
    assertThat(result.roleMatch()).isEqualTo(RoleMatchType.MISMATCH);
    assertThat(result.isNonItExclusion()).isFalse();
  }

  // ═══════════════════════════════════════════════════════════════════════
  // 5. 필수 경력 1년·2년 신입 제외, 우대 경력은 허용
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  void newGrad_excludedWhenRequiredYearsOne() {
    JobPostingAnalysis a =
        analysis(
            JobDomain.IT,
            PostingScope.ROLE_SPECIFIC,
            List.of(RoleGroupTag.BACKEND),
            null,
            1,
            ExperienceRequirementType.REQUIRED,
            RecruitmentType.EXPERIENCED_HIRE,
            List.of());

    AnalysisEligibility result =
        ClassificationAnalyzer.derive(a, backendNewGradPref(), ClassificationMode.ENFORCE);

    assertThat(result.eligible()).isFalse();
    assertThat(result.roleMatch()).isEqualTo(RoleMatchType.DIRECT_MATCH);
    assertThat(result.experienceMatch()).isEqualTo(ExperienceMatchType.EXCLUDED);
  }

  @Test
  void newGrad_allowedWhenPreferredExperienceOnly() {
    JobPostingAnalysis a =
        analysis(
            JobDomain.IT,
            PostingScope.ROLE_SPECIFIC,
            List.of(RoleGroupTag.BACKEND),
            null,
            null,
            ExperienceRequirementType.PREFERRED,
            RecruitmentType.OPEN_HIRE,
            List.of());

    AnalysisEligibility result =
        ClassificationAnalyzer.derive(a, backendNewGradPref(), ClassificationMode.ENFORCE);

    assertThat(result.eligible()).isTrue();
    assertThat(result.experienceMatch()).isEqualTo(ExperienceMatchType.PARTIAL);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // 6. IT 포함 신입 공채 통과
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  void openRecruitmentWithItTrack_passesForNewGrad() {
    PostingTrack itTrack =
        track(
            "IT 계열",
            List.of(RoleGroupTag.BACKEND, RoleGroupTag.DATA),
            true,
            0,
            ExperienceRequirementType.NONE,
            RecruitmentType.OPEN_HIRE,
            false);

    JobPostingAnalysis a =
        analysis(
            JobDomain.IT,
            PostingScope.OPEN_RECRUITMENT,
            List.of(),
            null,
            null,
            null,
            RecruitmentType.OPEN_HIRE,
            List.of(itTrack));

    AnalysisEligibility result =
        ClassificationAnalyzer.derive(a, backendNewGradPref(), ClassificationMode.ENFORCE);

    assertThat(result.eligible()).isTrue();
    assertThat(result.roleMatch()).isEqualTo(RoleMatchType.DIRECT_MATCH);
    assertThat(result.experienceMatch()).isEqualTo(ExperienceMatchType.FULL);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // 7. 비IT만 모집하는 공채 제외
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  void openRecruitmentWithOnlyNonDevTracks_excluded() {
    PostingTrack nonItTrack =
        track("영업/마케팅", List.of(RoleGroupTag.NON_DEV), null, null, null, null, false);

    JobPostingAnalysis a =
        analysis(
            JobDomain.NON_IT,
            PostingScope.OPEN_RECRUITMENT,
            List.of(),
            null,
            null,
            null,
            null,
            List.of(nonItTrack));

    AnalysisEligibility result =
        ClassificationAnalyzer.derive(a, backendNewGradPref(), ClassificationMode.ENFORCE);

    assertThat(result.eligible()).isFalse();
    assertThat(result.isNonItExclusion()).isTrue();
  }

  // ═══════════════════════════════════════════════════════════════════════
  // 8. 서로 다른 track의 직무와 경력을 결합하지 않음
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  void doesNotCombineRoleFromOneTrackWithExpFromAnother() {
    // BACKEND 트랙: 필수 3년 (신입 제외)
    PostingTrack backendTrack =
        track(
            "서버",
            List.of(RoleGroupTag.BACKEND),
            false,
            3,
            ExperienceRequirementType.REQUIRED,
            RecruitmentType.EXPERIENCED_HIRE,
            false);
    // DATA 트랙: 경력무관
    PostingTrack dataTrack =
        track(
            "데이터",
            List.of(RoleGroupTag.DATA),
            true,
            0,
            ExperienceRequirementType.NONE,
            RecruitmentType.OPEN_HIRE,
            false);

    JobPostingAnalysis a =
        analysis(
            JobDomain.IT,
            PostingScope.MULTI_ROLE,
            List.of(),
            null,
            null,
            null,
            null,
            List.of(backendTrack, dataTrack));

    // 백엔드 신입 사용자: BACKEND 트랙은 경력 조건 EXCLUDED, DATA 트랙의 경력을 빌려와서는 안 됨
    AnalysisEligibility result =
        ClassificationAnalyzer.derive(a, backendNewGradPref(), ClassificationMode.ENFORCE);

    // BACKEND 역할 매칭 → DIRECT_MATCH, 경력 EXCLUDED → 전체 제외
    assertThat(result.eligible()).isFalse();
    assertThat(result.roleMatch()).isEqualTo(RoleMatchType.DIRECT_MATCH);
    assertThat(result.experienceMatch()).isEqualTo(ExperienceMatchType.EXCLUDED);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // 9. 경력 UNKNOWN에 +15 미부여
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  void experienceUnknown_noFullBonus() {
    JobPostingAnalysis a =
        analysis(
            JobDomain.IT,
            PostingScope.ROLE_SPECIFIC,
            List.of(RoleGroupTag.BACKEND),
            null,
            null,
            ExperienceRequirementType.UNKNOWN,
            RecruitmentType.UNKNOWN,
            List.of());

    AnalysisEligibility result =
        ClassificationAnalyzer.derive(a, backendPref(), ClassificationMode.ENFORCE);

    assertThat(result.eligible()).isTrue();
    assertThat(result.experienceMatch()).isEqualTo(ExperienceMatchType.PARTIAL);
    assertThat(result.experienceMatch()).isNotEqualTo(ExperienceMatchType.FULL);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // 10. stale/PENDING 분석은 deferred 반환
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  void pendingAnalysis_returnsDeferred() {
    JobPostingAnalysis a = JobPostingAnalysis.pending(1L, "hash", "1.0");
    // status = PENDING (not SUCCEEDED)

    AnalysisEligibility result =
        ClassificationAnalyzer.derive(a, backendPref(), ClassificationMode.ENFORCE);

    assertThat(result.isDeferred()).isTrue();
    assertThat(result.eligible()).isFalse();
  }

  @Test
  void nullAnalysis_returnsDeferred() {
    AnalysisEligibility result =
        ClassificationAnalyzer.derive(null, backendPref(), ClassificationMode.ENFORCE);

    assertThat(result.isDeferred()).isTrue();
  }

  // ═══════════════════════════════════════════════════════════════════════
  // 11. BROAD_IT_MATCH: 공개채용 IT 트랙 있음, 선호 직무와 직접 불일치
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  void openRecruitment_broadItMatch_whenNoDirectTrackMatch() {
    // GENERAL_IT 트랙만 있고 경력 무관 → 백엔드 사용자에게 BROAD_IT_MATCH
    PostingTrack itTrack =
        track(
            "IT 개발 전반",
            List.of(RoleGroupTag.GENERAL_IT),
            true,
            0,
            ExperienceRequirementType.NONE,
            RecruitmentType.OPEN_HIRE,
            false);

    JobPostingAnalysis a =
        analysis(
            JobDomain.IT,
            PostingScope.OPEN_RECRUITMENT,
            List.of(),
            null,
            null,
            null,
            RecruitmentType.OPEN_HIRE,
            List.of(itTrack));

    AnalysisEligibility result =
        ClassificationAnalyzer.derive(a, backendPref(), ClassificationMode.ENFORCE);

    assertThat(result.eligible()).isTrue();
    assertThat(result.roleMatch()).isEqualTo(RoleMatchType.BROAD_IT_MATCH);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // 12. shadow 모드는 분류 결과로 실제 결과를 변경하지 않음 (UNKNOWN 반환)
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  void offMode_returnsDeferred_always() {
    JobPostingAnalysis a =
        analysis(
            JobDomain.IT,
            PostingScope.ROLE_SPECIFIC,
            List.of(RoleGroupTag.BACKEND),
            null,
            null,
            ExperienceRequirementType.NONE,
            RecruitmentType.EXPERIENCED_HIRE,
            List.of());

    AnalysisEligibility result =
        ClassificationAnalyzer.derive(a, backendPref(), ClassificationMode.OFF);

    assertThat(result.isDeferred()).isTrue();
  }

  // ═══════════════════════════════════════════════════════════════════════
  // 13. FULLSTACK 공고 → Backend 사용자 DIRECT_MATCH (호환 그룹)
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  void fullstackPosting_directMatchForBackendUser() {
    JobPostingAnalysis a =
        analysis(
            JobDomain.IT,
            PostingScope.ROLE_SPECIFIC,
            List.of(RoleGroupTag.FULLSTACK),
            null,
            null,
            ExperienceRequirementType.NONE,
            RecruitmentType.EXPERIENCED_HIRE,
            List.of());

    AnalysisEligibility result =
        ClassificationAnalyzer.derive(a, backendPref(), ClassificationMode.ENFORCE);

    assertThat(result.eligible()).isTrue();
    assertThat(result.roleMatch()).isEqualTo(RoleMatchType.DIRECT_MATCH);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // 14. GENERAL_IT 단일 직무 공고 → Backend 사용자 MISMATCH
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  void generalIt_singleRole_mismatchForBackendUser() {
    JobPostingAnalysis a =
        analysis(
            JobDomain.IT,
            PostingScope.ROLE_SPECIFIC,
            List.of(RoleGroupTag.GENERAL_IT),
            null,
            null,
            ExperienceRequirementType.NONE,
            RecruitmentType.EXPERIENCED_HIRE,
            List.of());

    AnalysisEligibility result =
        ClassificationAnalyzer.derive(a, backendPref(), ClassificationMode.ENFORCE);

    assertThat(result.eligible()).isFalse();
    assertThat(result.roleMatch()).isEqualTo(RoleMatchType.MISMATCH);
    assertThat(result.isNonItExclusion()).isFalse();
  }

  // ═══════════════════════════════════════════════════════════════════════
  // 15. MULTI_ROLE: 직접 일치 트랙이 있으면 BROAD_IT_MATCH보다 우선
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  void multiRole_directMatchTakePriorityOverBroadIt() {
    PostingTrack backendTrack =
        track(
            "백엔드",
            List.of(RoleGroupTag.BACKEND),
            true,
            0,
            ExperienceRequirementType.NONE,
            RecruitmentType.OPEN_HIRE,
            false);
    PostingTrack generalTrack =
        track(
            "IT",
            List.of(RoleGroupTag.GENERAL_IT),
            true,
            0,
            ExperienceRequirementType.NONE,
            RecruitmentType.OPEN_HIRE,
            false);

    JobPostingAnalysis a =
        analysis(
            JobDomain.IT,
            PostingScope.MULTI_ROLE,
            List.of(),
            null,
            null,
            null,
            null,
            List.of(backendTrack, generalTrack));

    AnalysisEligibility result =
        ClassificationAnalyzer.derive(a, backendPref(), ClassificationMode.ENFORCE);

    assertThat(result.eligible()).isTrue();
    assertThat(result.roleMatch()).isEqualTo(RoleMatchType.DIRECT_MATCH);
    assertThat(result.matchedTrack()).isEqualTo(backendTrack);
  }

  // ═══════════════════════════════════════════════════════════════════════
  // 16. 경력 요건 NONE → 신입도 FULL
  // ═══════════════════════════════════════════════════════════════════════

  @Test
  void experienceNone_newGrad_getsFullMatch() {
    JobPostingAnalysis a =
        analysis(
            JobDomain.IT,
            PostingScope.ROLE_SPECIFIC,
            List.of(RoleGroupTag.BACKEND),
            true,
            0,
            ExperienceRequirementType.NONE,
            RecruitmentType.OPEN_HIRE,
            List.of());

    AnalysisEligibility result =
        ClassificationAnalyzer.derive(a, backendNewGradPref(), ClassificationMode.ENFORCE);

    assertThat(result.eligible()).isTrue();
    assertThat(result.experienceMatch()).isEqualTo(ExperienceMatchType.FULL);
  }
}
