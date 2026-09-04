package com.briefy.domain.briefing.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import com.briefy.domain.candidatepool.entity.JobPosting;
import com.briefy.domain.candidatepool.entity.analysis.PostingScope;
import com.briefy.domain.candidatepool.entity.analysis.RecruitmentType;
import com.briefy.domain.company.entity.Company;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RelevanceScorerTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 8, 25);

  // ---------------------------------------------------------------------------
  // relevanceScore does NOT include recency or urgency
  // ---------------------------------------------------------------------------

  @Test
  void relevanceScore_doesNotIncludeRecentCollectionBonus() {
    // collectedDate = today → old code would add +5 (SCORE_RECENT)
    JobPosting recent = posting("개발자", "회사A", "https://e.com/r", null, null, TODAY);
    // collectedDate = 30 days ago
    JobPosting old = posting("개발자", "회사A", "https://e.com/o", null, null, TODAY.minusDays(30));

    RelevanceScorer.ScoringResult resultRecent = RelevanceScorer.score(recent, Map.of());
    RelevanceScorer.ScoringResult resultOld = RelevanceScorer.score(old, Map.of());

    assertThat(resultRecent.breakdown().relevanceScore())
        .isEqualTo(resultOld.breakdown().relevanceScore());
  }

  @Test
  void relevanceScore_doesNotIncludeDeadlineUrgencyBonus() {
    // deadline tomorrow → old code would add urgency bonus
    JobPosting urgent = posting("개발자", "회사A", "https://e.com/u", TODAY.plusDays(1), null, TODAY);
    // deadline in 60 days
    JobPosting calm = posting("개발자", "회사A", "https://e.com/c", TODAY.plusDays(60), null, TODAY);

    Map<String, Object> pref = Map.of();
    RelevanceScorer.ScoringResult resultUrgent = RelevanceScorer.score(urgent, pref);
    RelevanceScorer.ScoringResult resultCalm = RelevanceScorer.score(calm, pref);

    assertThat(resultUrgent.breakdown().relevanceScore())
        .isEqualTo(resultCalm.breakdown().relevanceScore());
  }

  // ---------------------------------------------------------------------------
  // adjustedScore = relevanceScore - exposurePenalty
  // ---------------------------------------------------------------------------

  @Test
  void adjustedScore_equalsRelevanceMinusPenalty() {
    JobPosting p = posting("백엔드 개발자", "토스", "https://e.com/1", null, null, TODAY);
    Map<String, Object> pref = Map.of("roles", List.of("백엔드 개발자"));

    RelevanceScorer.ScoringResult result = RelevanceScorer.score(p, pref);
    int relevance = result.breakdown().relevanceScore();

    ScoreBreakdown withPenalty = result.breakdown().withExposurePenalty(15);
    assertThat(withPenalty.adjustedScore()).isEqualTo(relevance - 15);
    assertThat(withPenalty.exposurePenalty()).isEqualTo(15);
    assertThat(withPenalty.relevanceScore()).isEqualTo(relevance);
  }

  @Test
  void adjustedScore_noPenalty_equalsRelevanceScore() {
    JobPosting p = posting("개발자", "회사A", "https://e.com/1", null, null, TODAY);
    RelevanceScorer.ScoringResult result = RelevanceScorer.score(p, Map.of());
    assertThat(result.breakdown().adjustedScore()).isEqualTo(result.breakdown().relevanceScore());
    assertThat(result.breakdown().exposurePenalty()).isEqualTo(0);
  }

  // ---------------------------------------------------------------------------
  // scoreBreakdown sum = relevanceScore
  // ---------------------------------------------------------------------------

  @Test
  void breakdownSumEqualsRelevanceScore() {
    JobPosting p = posting("백엔드 개발자", "토스", "https://e.com/1", null, null, TODAY);
    Map<String, Object> pref =
        Map.of(
            "roles", List.of("백엔드 개발자"),
            "companies", List.of("토스"),
            "skills", List.of("Java", "Spring"),
            "locations", List.of("서울"),
            "experienceLevels", List.of("신입"),
            "employmentTypes", List.of("정규직"));

    JobPosting withEmpAndExp =
        postingWithMetadata("백엔드 개발자", "토스", "서울", "정규직", "신입", "Java,Spring", null);

    RelevanceScorer.ScoringResult result = RelevanceScorer.score(withEmpAndExp, pref);
    ScoreBreakdown bd = result.breakdown();

    int sum =
        bd.roleScore()
            + bd.companyScore()
            + bd.skillScore()
            + bd.experienceScore()
            + bd.industryScore()
            + bd.locationScore()
            + bd.employmentTypeScore()
            + bd.companySizeScore()
            + bd.openRecruitmentScore()
            + bd.sourceScore();

    assertThat(bd.relevanceScore()).isEqualTo(sum);
  }

  // ---------------------------------------------------------------------------
  // Individual score components
  // ---------------------------------------------------------------------------

  @Test
  void roleMatch_addsRoleScore() {
    JobPosting p = postingWithRoles("백엔드 개발자", "[\"백엔드\"]");
    RelevanceScorer.ScoringResult result =
        RelevanceScorer.score(p, Map.of("roles", List.of("백엔드 개발자")));
    assertThat(result.breakdown().roleScore()).isEqualTo(RelevanceScorer.SCORE_ROLE_MATCH);
  }

  @Test
  void roleAmbiguous_noRoleScore() {
    // Generic title; no role signal → AMBIGUOUS → no bonus
    JobPosting p = postingWithRoles("개발자", null);
    RelevanceScorer.ScoringResult result =
        RelevanceScorer.score(p, Map.of("roles", List.of("백엔드 개발자")));
    assertThat(result.breakdown().roleScore()).isEqualTo(0);
  }

  @Test
  void companyMatch_addsCompanyScore() {
    JobPosting p = posting("개발자", "토스인컴", "https://e.com/1", null, null, TODAY);
    // prefix match: "토스" is prefix of "토스인컴"
    RelevanceScorer.ScoringResult result =
        RelevanceScorer.score(p, Map.of("companies", List.of("토스")));
    assertThat(result.breakdown().companyScore()).isEqualTo(RelevanceScorer.SCORE_TARGET_COMPANY);
  }

  @Test
  void companyNoMatch_noCompanyScore() {
    JobPosting p = posting("개발자", "카카오", "https://e.com/1", null, null, TODAY);
    RelevanceScorer.ScoringResult result =
        RelevanceScorer.score(p, Map.of("companies", List.of("토스")));
    assertThat(result.breakdown().companyScore()).isEqualTo(0);
  }

  @Test
  void skillsMatch_cappedAtMax() {
    // 6 matching skills → capped at SCORE_SKILLS_MAX (5 × SCORE_SKILL)
    JobPosting p = postingWithSkills("Java,Python,Go,Rust,Kotlin,TypeScript");
    RelevanceScorer.ScoringResult result =
        RelevanceScorer.score(
            p, Map.of("skills", List.of("Java", "Python", "Go", "Rust", "Kotlin", "TypeScript")));
    assertThat(result.breakdown().skillScore()).isEqualTo(RelevanceScorer.SCORE_SKILLS_MAX);
  }

  @Test
  void skillsPartialMatch_notCapped() {
    // 2 matching skills
    JobPosting p = postingWithSkills("Java,Python");
    RelevanceScorer.ScoringResult result =
        RelevanceScorer.score(p, Map.of("skills", List.of("Java", "Python", "Go")));
    assertThat(result.breakdown().skillScore()).isEqualTo(RelevanceScorer.SCORE_SKILL * 2);
  }

  @Test
  void experiencePassFull_addsExperienceScore() {
    // "신입" user, "신입" posting → PASS_FULL
    JobPosting p = postingWithMetadata("개발자", "회사A", null, null, "신입", null, null);
    RelevanceScorer.ScoringResult result =
        RelevanceScorer.score(p, Map.of("experienceLevels", List.of("신입")));
    assertThat(result.breakdown().experienceScore()).isEqualTo(RelevanceScorer.SCORE_EXPERIENCE);
  }

  @Test
  void experiencePassPartial_noExperienceScore() {
    // "신입" user, "1~2년" posting → PASS_PARTIAL
    JobPosting p = postingWithMetadata("개발자", "회사A", null, null, "1~2년", null, null);
    RelevanceScorer.ScoringResult result =
        RelevanceScorer.score(p, Map.of("experienceLevels", List.of("신입")));
    assertThat(result.breakdown().experienceScore()).isEqualTo(0);
  }

  @Test
  void locationMatch_addsLocationScore() {
    JobPosting p = postingWithMetadata("개발자", "회사A", "서울 강남", null, null, null, null);
    RelevanceScorer.ScoringResult result =
        RelevanceScorer.score(p, Map.of("locations", List.of("서울")));
    assertThat(result.breakdown().locationScore()).isEqualTo(RelevanceScorer.SCORE_LOCATION);
  }

  @Test
  void locationNoMatch_noLocationScore() {
    JobPosting p = postingWithMetadata("개발자", "회사A", "부산", null, null, null, null);
    RelevanceScorer.ScoringResult result =
        RelevanceScorer.score(p, Map.of("locations", List.of("서울")));
    assertThat(result.breakdown().locationScore()).isEqualTo(0);
  }

  @Test
  void employmentTypeMatch_addsEmpTypeScore() {
    JobPosting p = postingWithMetadata("개발자", "회사A", null, "정규직", null, null, null);
    RelevanceScorer.ScoringResult result =
        RelevanceScorer.score(p, Map.of("employmentTypes", List.of("정규직")));
    assertThat(result.breakdown().employmentTypeScore())
        .isEqualTo(RelevanceScorer.SCORE_EMPLOYMENT_TYPE);
  }

  @Test
  void companySizeMatch_addsCompanySizeScore() {
    Company linkedCo = company("대기업", null);
    JobPosting p = postingWithLinkedCompany("개발자", "회사A", linkedCo);
    RelevanceScorer.ScoringResult result =
        RelevanceScorer.score(p, Map.of("companySizes", List.of("대기업")));
    assertThat(result.breakdown().companySizeScore()).isEqualTo(RelevanceScorer.SCORE_COMPANY_SIZE);
  }

  @Test
  void industryMatch_addsIndustryScore() {
    Company linkedCo = company(null, "IT");
    JobPosting p = postingWithLinkedCompany("개발자", "회사A", linkedCo);
    RelevanceScorer.ScoringResult result =
        RelevanceScorer.score(p, Map.of("industries", List.of("IT")));
    assertThat(result.breakdown().industryScore()).isEqualTo(RelevanceScorer.SCORE_INDUSTRY);
  }

  // ---------------------------------------------------------------------------
  // matchEvidence
  // ---------------------------------------------------------------------------

  @Test
  void matchEvidence_onlyContainsActualMatches() {
    JobPosting p = postingWithMetadata("백엔드 개발자", "토스", "서울", "정규직", "신입", "Java,Spring", null);
    Map<String, Object> pref =
        Map.of(
            "roles", List.of("백엔드 개발자"),
            "companies", List.of("토스"),
            "skills", List.of("Java", "Spring", "Kotlin"),
            "locations", List.of("서울"),
            "experienceLevels", List.of("신입"),
            "employmentTypes", List.of("정규직"));

    RelevanceScorer.ScoringResult result = RelevanceScorer.score(p, pref);
    MatchEvidence ev = result.evidence();

    // Kotlin is in pref but NOT in posting skills → not in matchedSkills
    assertThat(ev.matchedSkills()).containsExactlyInAnyOrder("Java", "Spring");
    assertThat(ev.matchedSkills()).doesNotContain("Kotlin");
    assertThat(ev.matchedCompanies()).containsExactly("토스");
    assertThat(ev.matchedLocations()).containsExactly("서울");
    assertThat(ev.matchedEmploymentTypes()).containsExactly("정규직");
  }

  @Test
  void matchEvidence_noMatches_allEmpty() {
    JobPosting p = posting("마케터", "비관련회사", "https://e.com/1", null, null, TODAY);
    Map<String, Object> pref = Map.of("companies", List.of("토스"), "skills", List.of("Java"));

    RelevanceScorer.ScoringResult result = RelevanceScorer.score(p, pref);
    MatchEvidence ev = result.evidence();

    assertThat(ev.matchedCompanies()).isEmpty();
    assertThat(ev.matchedSkills()).isEmpty();
    assertThat(ev.matchedRoles()).isEmpty();
  }

  // ---------------------------------------------------------------------------
  // exposurePenalty
  // ---------------------------------------------------------------------------

  @Test
  void exposurePenalty_exposedYesterday_returnsYesterdayPenalty() {
    String url = "https://e.com/1";
    Map<String, LocalDate> map = Map.of("https://e.com/1", TODAY.minusDays(1));
    assertThat(RelevanceScorer.computeExposurePenalty(url, map, TODAY))
        .isEqualTo(RelevanceScorer.EXPOSURE_PENALTY_YESTERDAY);
  }

  @Test
  void exposurePenalty_exposedThreeDaysAgo_returnsRecentPenalty() {
    String url = "https://e.com/2";
    Map<String, LocalDate> map = Map.of("https://e.com/2", TODAY.minusDays(3));
    assertThat(RelevanceScorer.computeExposurePenalty(url, map, TODAY))
        .isEqualTo(RelevanceScorer.EXPOSURE_PENALTY_RECENT);
  }

  @Test
  void exposurePenalty_exposedSixDaysAgo_returns10() {
    String url = "https://e.com/3";
    Map<String, LocalDate> map = Map.of("https://e.com/3", TODAY.minusDays(6));
    assertThat(RelevanceScorer.computeExposurePenalty(url, map, TODAY)).isEqualTo(10);
  }

  @Test
  void exposurePenalty_exposedSevenDaysAgo_returnsZero() {
    String url = "https://e.com/4";
    Map<String, LocalDate> map = Map.of("https://e.com/4", TODAY.minusDays(7));
    assertThat(RelevanceScorer.computeExposurePenalty(url, map, TODAY)).isEqualTo(0);
  }

  @Test
  void exposurePenalty_urlWithQueryStripped_matchesCanonical() {
    String baseUrl = "https://e.com/jobs/42";
    // Canonical map key (no query)
    Map<String, LocalDate> map = Map.of("https://e.com/jobs/42", TODAY.minusDays(2));
    // URL with query/fragment should canonicalize to same key
    assertThat(
            RelevanceScorer.computeExposurePenalty(
                "https://e.com/jobs/42?ref=email#top", map, TODAY))
        .isEqualTo(RelevanceScorer.EXPOSURE_PENALTY_RECENT);
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  // ---------------------------------------------------------------------------
  // 대기업 공채 가산점 (openRecruitmentScore) — 분류 경로 전용, 신입 게이팅
  // ---------------------------------------------------------------------------

  @Test
  void keywordOnlyScore_hasZeroOpenRecruitmentBonus() {
    JobPosting p = posting("개발자", "회사A", "https://e.com/1", null, null, TODAY);
    RelevanceScorer.ScoringResult result = RelevanceScorer.score(p, Map.of());
    assertThat(result.breakdown().openRecruitmentScore()).isEqualTo(0);
  }

  @Test
  void openRecruitmentScope_getsBonus_forAnyUser() {
    JobPosting p = posting("개발자", "회사A", "https://e.com/1", null, null, TODAY);
    AnalysisEligibility e =
        eligibility(
            RoleMatchType.BROAD_IT_MATCH,
            ExperienceMatchType.FULL,
            PostingScope.OPEN_RECRUITMENT,
            RecruitmentType.OPEN_HIRE);

    RelevanceScorer.ScoringResult result = RelevanceScorer.score(p, Map.of(), e);

    assertThat(result.breakdown().openRecruitmentScore())
        .isEqualTo(RelevanceScorer.SCORE_OPEN_RECRUITMENT);
  }

  @Test
  void newGradHire_getsBonus_onlyForNewGradUser() {
    JobPosting p = posting("개발자", "회사A", "https://e.com/1", null, null, TODAY);
    AnalysisEligibility e =
        eligibility(
            RoleMatchType.BROAD_IT_MATCH,
            ExperienceMatchType.FULL,
            PostingScope.ROLE_SPECIFIC,
            RecruitmentType.NEW_GRAD_HIRE);

    Map<String, Object> newGradPref = Map.of("experienceLevels", List.of("신입"));
    RelevanceScorer.ScoringResult result = RelevanceScorer.score(p, newGradPref, e);

    assertThat(result.breakdown().openRecruitmentScore())
        .isEqualTo(RelevanceScorer.SCORE_NEW_GRAD_HIRE);
  }

  @Test
  void newGradHire_noBonus_forExperiencedUser() {
    JobPosting p = posting("개발자", "회사A", "https://e.com/1", null, null, TODAY);
    AnalysisEligibility e =
        eligibility(
            RoleMatchType.BROAD_IT_MATCH,
            ExperienceMatchType.FULL,
            PostingScope.ROLE_SPECIFIC,
            RecruitmentType.NEW_GRAD_HIRE);

    Map<String, Object> experiencedPref = Map.of("experienceLevels", List.of("경력"));
    RelevanceScorer.ScoringResult result = RelevanceScorer.score(p, experiencedPref, e);

    assertThat(result.breakdown().openRecruitmentScore()).isEqualTo(0);
  }

  @Test
  void openRecruitmentAndNewGradHire_newGradUser_isCapped() {
    JobPosting p = posting("개발자", "회사A", "https://e.com/1", null, null, TODAY);
    AnalysisEligibility e =
        eligibility(
            RoleMatchType.BROAD_IT_MATCH,
            ExperienceMatchType.FULL,
            PostingScope.OPEN_RECRUITMENT,
            RecruitmentType.NEW_GRAD_HIRE);

    Map<String, Object> newGradPref = Map.of("experienceLevels", List.of("신입"));
    RelevanceScorer.ScoringResult result = RelevanceScorer.score(p, newGradPref, e);

    // 10 + 5 = 15 → capped to 12
    assertThat(result.breakdown().openRecruitmentScore())
        .isEqualTo(RelevanceScorer.SCORE_OPEN_RECRUITMENT_MAX);
  }

  @Test
  void roleSpecificExperiencedHire_hasNoBonus() {
    JobPosting p = posting("개발자", "회사A", "https://e.com/1", null, null, TODAY);
    AnalysisEligibility e =
        eligibility(
            RoleMatchType.DIRECT_MATCH,
            ExperienceMatchType.FULL,
            PostingScope.ROLE_SPECIFIC,
            RecruitmentType.EXPERIENCED_HIRE);

    RelevanceScorer.ScoringResult result = RelevanceScorer.score(p, Map.of(), e);

    assertThat(result.breakdown().openRecruitmentScore()).isEqualTo(0);
  }

  @Test
  void openRecruitmentBonus_isIncludedInRelevanceScore() {
    JobPosting p = posting("개발자", "회사A", "https://e.com/1", null, null, TODAY);
    AnalysisEligibility e =
        eligibility(
            RoleMatchType.BROAD_IT_MATCH,
            ExperienceMatchType.UNKNOWN,
            PostingScope.OPEN_RECRUITMENT,
            RecruitmentType.OPEN_HIRE);

    RelevanceScorer.ScoringResult result = RelevanceScorer.score(p, Map.of(), e);
    ScoreBreakdown b = result.breakdown();

    // relevanceScore = roleScore(15) + openRecruitmentScore(10) + sourceScore (다른 항목 0)
    assertThat(b.openRecruitmentScore()).isEqualTo(RelevanceScorer.SCORE_OPEN_RECRUITMENT);
    assertThat(b.relevanceScore())
        .isEqualTo(b.roleScore() + b.openRecruitmentScore() + b.sourceScore());
  }

  // ---------------------------------------------------------------------------
  // 공식 채용 사이트 소스 가산점 (sourceScore)
  // ---------------------------------------------------------------------------

  @Test
  void officialSource_getsSourceBonus() {
    // posting(...) 헬퍼는 source="원티드" — 애그리게이터가 아니므로 공식으로 취급
    JobPosting p = posting("개발자", "회사A", "https://e.com/1", null, null, TODAY);
    RelevanceScorer.ScoringResult result = RelevanceScorer.score(p, Map.of());
    assertThat(result.breakdown().sourceScore()).isEqualTo(RelevanceScorer.SCORE_OFFICIAL_SOURCE);
  }

  @Test
  void aggregatorSource_getsNoSourceBonus() {
    JobPosting jaso = postingWithSource("jasoseol");
    JobPosting saramin = postingWithSource("saramin");
    assertThat(RelevanceScorer.score(jaso, Map.of()).breakdown().sourceScore()).isEqualTo(0);
    assertThat(RelevanceScorer.score(saramin, Map.of()).breakdown().sourceScore()).isEqualTo(0);
  }

  @Test
  void officialCareerSource_getsSourceBonus() {
    JobPosting naver = postingWithSource("naver_careers");
    assertThat(RelevanceScorer.score(naver, Map.of()).breakdown().sourceScore())
        .isEqualTo(RelevanceScorer.SCORE_OFFICIAL_SOURCE);
  }

  @Test
  void isOfficialSource_classifiesSources() {
    assertThat(RelevanceScorer.isOfficialSource("jasoseol")).isFalse();
    assertThat(RelevanceScorer.isOfficialSource("saramin")).isFalse();
    assertThat(RelevanceScorer.isOfficialSource("fixture")).isFalse();
    assertThat(RelevanceScorer.isOfficialSource(null)).isFalse();
    assertThat(RelevanceScorer.isOfficialSource("")).isFalse();
    assertThat(RelevanceScorer.isOfficialSource("toss_careers")).isTrue();
    assertThat(RelevanceScorer.isOfficialSource("company_5_greenhouse")).isTrue();
  }

  private static AnalysisEligibility eligibility(
      RoleMatchType role,
      ExperienceMatchType exp,
      PostingScope scope,
      RecruitmentType recruitmentType) {
    return new AnalysisEligibility(
        true, role, exp, null, List.of("test"), "evidence", "hash", "1.0", scope, recruitmentType);
  }

  private static JobPosting posting(
      String title,
      String company,
      String url,
      LocalDate deadline,
      LocalDateTime publishedAt,
      LocalDate collectedDate) {
    return JobPosting.create(
        title,
        company,
        "원티드",
        url,
        null,
        deadline,
        null,
        null,
        null,
        null,
        null,
        "hash",
        collectedDate != null ? collectedDate : TODAY,
        publishedAt);
  }

  private static JobPosting postingWithSource(String source) {
    return JobPosting.create(
        "개발자",
        "회사A",
        source,
        "https://e.com/" + source,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        "hash",
        TODAY,
        null);
  }

  private static JobPosting postingWithRoles(String title, String rolesJson) {
    return JobPosting.create(
        title,
        "회사A",
        "원티드",
        "https://e.com/1",
        null,
        null,
        null,
        rolesJson,
        null,
        null,
        null,
        "hash",
        TODAY,
        null);
  }

  private static JobPosting postingWithSkills(String skills) {
    return JobPosting.create(
        "개발자",
        "회사A",
        "원티드",
        "https://e.com/1",
        null,
        null,
        null,
        null,
        skills,
        null,
        null,
        "hash",
        TODAY,
        null);
  }

  private static JobPosting postingWithMetadata(
      String title,
      String company,
      String location,
      String empType,
      String expLevel,
      String skills,
      LocalDate deadline) {
    return JobPosting.create(
        title,
        company,
        "원티드",
        "https://e.com/1",
        location,
        deadline,
        null,
        null,
        skills,
        empType,
        expLevel,
        "hash",
        TODAY,
        null);
  }

  private static JobPosting postingWithLinkedCompany(
      String title, String company, Company linkedCo) {
    JobPosting p =
        JobPosting.create(
            title,
            company,
            "원티드",
            "https://e.com/1",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            "hash",
            TODAY,
            null);
    p.linkCompany(linkedCo);
    return p;
  }

  private static Company company(String companySize, String industryCodes) {
    return Company.create("테스트", "테스트", companySize, industryCodes);
  }
}
