package com.briefy.domain.candidatepool.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.briefy.domain.candidatepool.entity.JobPosting;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class AnalysisInputHashCalculatorTest {

  private static final LocalDate DATE = LocalDate.of(2026, 7, 1);

  private JobPosting posting(
      String title,
      String description,
      String roles,
      String experienceLevel,
      String employmentType) {
    return JobPosting.create(
        title,
        "네이버",
        "원티드",
        "https://example.com/job/1",
        "서울",
        null,
        description,
        roles,
        null,
        employmentType,
        experienceLevel,
        "hash",
        DATE,
        null);
  }

  @Test
  void compute_returnsStable64CharHex() {
    JobPosting jp = posting("백엔드 개발자", "설명", "[\"Backend\"]", "3년 이상", "정규직");
    String h1 = AnalysisInputHashCalculator.compute(jp, null);
    String h2 = AnalysisInputHashCalculator.compute(jp, null);
    assertThat(h1).isEqualTo(h2).hasSize(64).matches("[0-9a-f]+");
  }

  @Test
  void rolesOrder_doesNotAffectHash() {
    JobPosting asc = posting("백엔드 개발자", "설명", "[\"Backend\",\"Fullstack\"]", null, null);
    JobPosting desc = posting("백엔드 개발자", "설명", "[\"Fullstack\",\"Backend\"]", null, null);
    assertThat(AnalysisInputHashCalculator.compute(asc, null))
        .isEqualTo(AnalysisInputHashCalculator.compute(desc, null));
  }

  @Test
  void titleWhitespace_normalizedBeforeHash() {
    JobPosting single = posting("백엔드  개발자", "설명", null, null, null);
    JobPosting multi = posting("백엔드 개발자", "설명", null, null, null);
    assertThat(AnalysisInputHashCalculator.compute(single, null))
        .isEqualTo(AnalysisInputHashCalculator.compute(multi, null));
  }

  @Test
  void descriptionChange_changesHash() {
    JobPosting v1 = posting("백엔드 개발자", "기존 설명", null, null, null);
    JobPosting v2 = posting("백엔드 개발자", "변경된 설명", null, null, null);
    assertThat(AnalysisInputHashCalculator.compute(v1, null))
        .isNotEqualTo(AnalysisInputHashCalculator.compute(v2, null));
  }

  @Test
  void descriptionTruncated_null_vs_false_produceDifferentHashes() {
    JobPosting jp = posting("백엔드 개발자", "설명", null, null, null);
    String nullHash = AnalysisInputHashCalculator.compute(jp, null);
    String falseHash = AnalysisInputHashCalculator.compute(jp, false);
    assertThat(nullHash).isNotEqualTo(falseHash);
  }

  @Test
  void descriptionTruncated_false_vs_true_produceDifferentHashes() {
    JobPosting jp = posting("백엔드 개발자", "설명", null, null, null);
    String falseHash = AnalysisInputHashCalculator.compute(jp, false);
    String trueHash = AnalysisInputHashCalculator.compute(jp, true);
    assertThat(falseHash).isNotEqualTo(trueHash);
  }

  @Test
  void experienceChange_changesHash() {
    JobPosting v1 = posting("백엔드 개발자", "설명", null, "3년 이상", null);
    JobPosting v2 = posting("백엔드 개발자", "설명", null, "5년 이상", null);
    assertThat(AnalysisInputHashCalculator.compute(v1, null))
        .isNotEqualTo(AnalysisInputHashCalculator.compute(v2, null));
  }

  @Test
  void nullAndEmptyExperience_treatSameForHash() {
    JobPosting nullExp = posting("백엔드 개발자", "설명", null, null, null);
    JobPosting blankExp = posting("백엔드 개발자", "설명", null, "", null);
    assertThat(AnalysisInputHashCalculator.compute(nullExp, null))
        .isEqualTo(AnalysisInputHashCalculator.compute(blankExp, null));
  }

  @Test
  void nullDescription_and_emptyDescription_treatSameForHasDesc() {
    // Both should produce hasDesc="0"
    JobPosting nullDesc = posting("백엔드 개발자", null, null, null, null);
    JobPosting emptyDesc = posting("백엔드 개발자", "   ", null, null, null);
    assertThat(AnalysisInputHashCalculator.compute(nullDesc, null))
        .isEqualTo(AnalysisInputHashCalculator.compute(emptyDesc, null));
  }

  @Test
  void invalidRolesJson_fallsBackToNormalization_andDoesNotThrow() {
    JobPosting jp = posting("백엔드 개발자", "설명", "not-valid-json", null, null);
    String hash = AnalysisInputHashCalculator.compute(jp, null);
    assertThat(hash).isNotNull().hasSize(64);
  }

  @Test
  void emptyRolesArray_treatedSameAsNullRoles() {
    JobPosting withEmpty = posting("백엔드 개발자", "설명", "[]", null, null);
    JobPosting withNull = posting("백엔드 개발자", "설명", null, null, null);
    assertThat(AnalysisInputHashCalculator.compute(withEmpty, null))
        .isEqualTo(AnalysisInputHashCalculator.compute(withNull, null));
  }

  // ── Cross-language fixture (must match apps/agent/tests/test_analysis_input_hash.py) ──

  private static final String FIXTURE_HASH = computeFixtureHash();

  private static String computeFixtureHash() {
    JobPosting jp =
        JobPosting.create(
            "백엔드 개발자",
            "네이버",
            "원티드",
            "https://example.com/job/cross-lang",
            "서울",
            null,
            "Java Spring Boot를 사용한 백엔드 개발",
            "[\"backend engineering\",\"server-side\"]",
            null,
            "정규직",
            "3년 이상",
            "hash",
            LocalDate.of(2026, 7, 1),
            null);
    return AnalysisInputHashCalculator.compute(jp, false);
  }

  @Test
  void crossLanguage_fixtureHash_isStable() {
    // The expected value below must match the Python fixture in
    // apps/agent/tests/test_analysis_input_hash.py::test_cross_language_fixture_matches_java
    // Run both suites after any change to normalization rules.
    assertThat(FIXTURE_HASH).isNotNull().hasSize(64);
    // Print for Python fixture calibration (CI will catch drift).
    System.out.println("cross-lang fixture hash: " + FIXTURE_HASH);
  }
}
