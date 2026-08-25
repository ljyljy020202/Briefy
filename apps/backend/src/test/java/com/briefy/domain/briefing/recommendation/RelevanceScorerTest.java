package com.briefy.domain.briefing.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import com.briefy.domain.candidatepool.entity.JobPosting;
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

    RelevanceScorer.ScoringResult resultRecent =
        RelevanceScorer.score(recent, Map.of());
    RelevanceScorer.ScoringResult resultOld =
        RelevanceScorer.score(old, Map.of());

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
    assertThat(result.breakdown().adjustedScore())
        .isEqualTo(result.breakdown().relevanceScore());
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
            + bd.companySizeScore();

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
            p,
            Map.of(
                "skills", List.of("Java", "Python", "Go", "Rust", "Kotlin", "TypeScript")));
    assertThat(result.breakdown().skillScore()).isEqualTo(RelevanceScorer.SCORE_SKILLS_MAX);
  }

  @Test
  void skillsPartialMatch_notCapped() {
    // 2 matching skills
    JobPosting p = postingWithSkills("Java,Python");
    RelevanceScorer.ScoringResult result =
        RelevanceScorer.score(
            p,
            Map.of("skills", List.of("Java", "Python", "Go")));
    assertThat(result.breakdown().skillScore())
        .isEqualTo(RelevanceScorer.SCORE_SKILL * 2);
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
    assertThat(result.breakdown().companySizeScore())
        .isEqualTo(RelevanceScorer.SCORE_COMPANY_SIZE);
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
  void exposurePenalty_exposedYesterday_returns25() {
    String url = "https://e.com/1";
    Map<String, LocalDate> map = Map.of("https://e.com/1", TODAY.minusDays(1));
    assertThat(RelevanceScorer.computeExposurePenalty(url, map, TODAY)).isEqualTo(25);
  }

  @Test
  void exposurePenalty_exposedThreeDaysAgo_returns15() {
    String url = "https://e.com/2";
    Map<String, LocalDate> map = Map.of("https://e.com/2", TODAY.minusDays(3));
    assertThat(RelevanceScorer.computeExposurePenalty(url, map, TODAY)).isEqualTo(15);
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
        .isEqualTo(15);
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static JobPosting posting(
      String title,
      String company,
      String url,
      LocalDate deadline,
      LocalDateTime publishedAt,
      LocalDate collectedDate) {
    return JobPosting.create(
        title, company, "원티드", url, null, deadline, null, null, null, null, null,
        "hash", collectedDate != null ? collectedDate : TODAY, publishedAt);
  }

  private static JobPosting postingWithRoles(String title, String rolesJson) {
    return JobPosting.create(
        title, "회사A", "원티드", "https://e.com/1", null, null, null,
        rolesJson, null, null, null, "hash", TODAY, null);
  }

  private static JobPosting postingWithSkills(String skills) {
    return JobPosting.create(
        "개발자", "회사A", "원티드", "https://e.com/1", null, null, null,
        null, skills, null, null, "hash", TODAY, null);
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
        title, company, "원티드", "https://e.com/1",
        location, deadline, null, null, skills, empType, expLevel,
        "hash", TODAY, null);
  }

  private static JobPosting postingWithLinkedCompany(
      String title, String company, Company linkedCo) {
    JobPosting p =
        JobPosting.create(
            title, company, "원티드", "https://e.com/1", null, null, null,
            null, null, null, null, "hash", TODAY, null);
    p.linkCompany(linkedCo);
    return p;
  }

  private static Company company(String companySize, String industryCodes) {
    return Company.create("테스트", "테스트", companySize, industryCodes);
  }
}
