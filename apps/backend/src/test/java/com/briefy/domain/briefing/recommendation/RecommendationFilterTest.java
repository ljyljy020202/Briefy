package com.briefy.domain.briefing.recommendation;

import static org.assertj.core.api.Assertions.assertThat;

import com.briefy.domain.candidatepool.entity.JobPosting;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RecommendationFilterTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 8, 25);

  // ---------------------------------------------------------------------------
  // Missing required fields
  // ---------------------------------------------------------------------------

  @Test
  void missingTitle_excluded() {
    JobPosting p = posting(null, "회사A", "https://e.com/1", null);
    assertExcluded(p, Map.of(), FilterReason.MISSING_REQUIRED_FIELDS);
  }

  @Test
  void blankTitle_excluded() {
    JobPosting p = posting("  ", "회사A", "https://e.com/1", null);
    assertExcluded(p, Map.of(), FilterReason.MISSING_REQUIRED_FIELDS);
  }

  @Test
  void missingCompany_excluded() {
    JobPosting p = posting("개발자", null, "https://e.com/1", null);
    assertExcluded(p, Map.of(), FilterReason.MISSING_REQUIRED_FIELDS);
  }

  @Test
  void missingUrl_excluded() {
    JobPosting p = posting("개발자", "회사A", null, null);
    assertExcluded(p, Map.of(), FilterReason.MISSING_REQUIRED_FIELDS);
  }

  // ---------------------------------------------------------------------------
  // Expired posting
  // ---------------------------------------------------------------------------

  @Test
  void deadlineBeforeToday_excluded() {
    JobPosting p = posting("개발자", "회사A", "https://e.com/1", TODAY.minusDays(1));
    assertExcluded(p, Map.of(), FilterReason.EXPIRED);
  }

  @Test
  void deadlineEqualsToday_passes() {
    JobPosting p = posting("개발자", "회사A", "https://e.com/1", TODAY);
    assertPasses(p, Map.of());
  }

  @Test
  void deadlineAfterToday_passes() {
    JobPosting p = posting("개발자", "회사A", "https://e.com/1", TODAY.plusDays(7));
    assertPasses(p, Map.of());
  }

  @Test
  void nullDeadline_passes() {
    JobPosting p = posting("개발자", "회사A", "https://e.com/1", null);
    assertPasses(p, Map.of());
  }

  // ---------------------------------------------------------------------------
  // Role mismatch (hard filter only on MISMATCH; AMBIGUOUS passes)
  // ---------------------------------------------------------------------------

  @Test
  void roleMismatch_excluded() {
    // User wants backend; posting is clearly frontend
    JobPosting p = postingWithRoles("프론트엔드 개발자", "회사A", "[\"프론트엔드\"]", null);
    Map<String, Object> pref = Map.of("roles", List.of("백엔드 개발자"));
    assertExcluded(p, pref, FilterReason.ROLE_MISMATCH);
  }

  @Test
  void roleAmbiguous_passes() {
    // Title is generic "개발자" — AMBIGUOUS → should pass
    JobPosting p = postingWithRoles("개발자", "회사A", null, null);
    Map<String, Object> pref = Map.of("roles", List.of("백엔드 개발자"));
    assertPasses(p, pref);
  }

  @Test
  void roleMatch_passes() {
    JobPosting p = postingWithRoles("백엔드 개발자", "회사A", "[\"백엔드\"]", null);
    Map<String, Object> pref = Map.of("roles", List.of("백엔드 개발자"));
    assertPasses(p, pref);
  }

  @Test
  void noUserRolePreference_passes() {
    // No role preference — no filtering
    JobPosting p = postingWithRoles("마케터", "회사A", null, null);
    assertPasses(p, Map.of());
  }

  // ---------------------------------------------------------------------------
  // Experience excluded
  // ---------------------------------------------------------------------------

  @Test
  void newGradUser_experiencedPosting_excluded() {
    // User is "신입"; posting requires 5년 이상 → EXCLUDE
    JobPosting p = postingWithExperience("개발자", "5년 이상", null);
    Map<String, Object> pref = Map.of("experienceLevels", List.of("신입"));
    assertExcluded(p, pref, FilterReason.EXPERIENCE_EXCLUDED);
  }

  @Test
  void newGradUser_newGradPosting_passes() {
    JobPosting p = postingWithExperience("개발자", "신입", null);
    Map<String, Object> pref = Map.of("experienceLevels", List.of("신입"));
    assertPasses(p, pref);
  }

  @Test
  void newGradUser_entryPosting_passes() {
    // "경력 무관" is always compatible
    JobPosting p = postingWithExperience("개발자", "경력 무관", null);
    Map<String, Object> pref = Map.of("experienceLevels", List.of("신입"));
    assertPasses(p, pref);
  }

  @Test
  void noExperiencePreference_experiencedPosting_passes() {
    // No preference → no filtering
    JobPosting p = postingWithExperience("개발자", "5년 이상", null);
    assertPasses(p, Map.of());
  }

  // ---------------------------------------------------------------------------
  // Employment type mismatch
  // ---------------------------------------------------------------------------

  @Test
  void bothSidesExplicit_mismatch_excluded() {
    JobPosting p = postingWithEmpType("개발자", "계약직", null);
    Map<String, Object> pref = Map.of("employmentTypes", List.of("정규직"));
    assertExcluded(p, pref, FilterReason.EMPLOYMENT_TYPE_MISMATCH);
  }

  @Test
  void bothSidesExplicit_match_passes() {
    JobPosting p = postingWithEmpType("개발자", "정규직", null);
    Map<String, Object> pref = Map.of("employmentTypes", List.of("정규직"));
    assertPasses(p, pref);
  }

  @Test
  void postingEmpTypeNull_passes() {
    // Posting does not specify employment type → conservative pass
    JobPosting p = postingWithEmpType("개발자", null, null);
    Map<String, Object> pref = Map.of("employmentTypes", List.of("정규직"));
    assertPasses(p, pref);
  }

  @Test
  void postingEmpTypeBlank_passes() {
    JobPosting p = postingWithEmpType("개발자", "  ", null);
    Map<String, Object> pref = Map.of("employmentTypes", List.of("정규직"));
    assertPasses(p, pref);
  }

  @Test
  void userEmpTypePrefEmpty_passes() {
    // No employment type preference → no filtering regardless of posting value
    JobPosting p = postingWithEmpType("개발자", "계약직", null);
    assertPasses(p, Map.of());
  }

  @Test
  void empTypeCaseInsensitive_passes() {
    JobPosting p = postingWithEmpType("개발자", "정규직", null);
    Map<String, Object> pref = Map.of("employmentTypes", List.of("정규직"));
    assertPasses(p, pref);
  }

  // ---------------------------------------------------------------------------
  // Full-pass scenario
  // ---------------------------------------------------------------------------

  @Test
  void allFieldsValid_noPreference_passes() {
    JobPosting p = posting("백엔드 개발자", "토스", "https://e.com/toss/1", TODAY.plusDays(30));
    assertPasses(p, Map.of());
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static void assertPasses(JobPosting posting, Map<String, Object> pref) {
    FilterResult result = RecommendationFilter.evaluate(posting, pref, TODAY);
    assertThat(result.eligible()).isTrue();
    assertThat(result.reason()).isNull();
  }

  private static void assertExcluded(
      JobPosting posting, Map<String, Object> pref, FilterReason expected) {
    FilterResult result = RecommendationFilter.evaluate(posting, pref, TODAY);
    assertThat(result.eligible()).isFalse();
    assertThat(result.reason()).isEqualTo(expected);
  }

  private static JobPosting posting(
      String title, String company, String url, LocalDate deadline) {
    return JobPosting.create(
        title,
        company,
        "원티드",
        url,
        "서울",
        deadline,
        null,
        null,
        null,
        null,
        null,
        "hash",
        TODAY,
        null);
  }

  private static JobPosting postingWithRoles(
      String title, String company, String rolesJson, LocalDate deadline) {
    return JobPosting.create(
        title,
        company,
        "원티드",
        "https://e.com/1",
        "서울",
        deadline,
        null,
        rolesJson,
        null,
        null,
        null,
        "hash",
        TODAY,
        null);
  }

  private static JobPosting postingWithExperience(
      String title, String experienceLevel, LocalDate deadline) {
    return JobPosting.create(
        title,
        "회사A",
        "원티드",
        "https://e.com/1",
        "서울",
        deadline,
        null,
        null,
        null,
        null,
        experienceLevel,
        "hash",
        TODAY,
        null);
  }

  private static JobPosting postingWithEmpType(
      String title, String empType, LocalDate deadline) {
    return JobPosting.create(
        title,
        "회사A",
        "원티드",
        "https://e.com/1",
        "서울",
        deadline,
        null,
        null,
        null,
        empType,
        null,
        "hash",
        TODAY,
        null);
  }
}
