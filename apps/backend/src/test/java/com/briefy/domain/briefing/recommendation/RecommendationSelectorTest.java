package com.briefy.domain.briefing.recommendation;

import static com.briefy.domain.briefing.recommendation.RecommendationSelector.MAX_PER_COMPANY;
import static com.briefy.domain.briefing.recommendation.RecommendationSelector.MAX_RECOMMENDATIONS;
import static com.briefy.domain.briefing.recommendation.RecommendationSelector.MIN_NEW;
import static com.briefy.domain.briefing.recommendation.RecommendationSelector.MIN_URGENT;
import static com.briefy.domain.briefing.recommendation.RecommendationSelector.NEW_DAYS;
import static com.briefy.domain.briefing.recommendation.RecommendationSelector.URGENT_DAYS;
import static org.assertj.core.api.Assertions.assertThat;

import com.briefy.domain.candidatepool.entity.JobPosting;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class RecommendationSelectorTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 8, 25);

  // ---------------------------------------------------------------------------
  // isNew / isUrgent flag computation
  // ---------------------------------------------------------------------------

  @Test
  void computeIsNew_publishedWithinNewDays_true() {
    JobPosting p = posting(1L, "회사A", null, TODAY.minusDays(NEW_DAYS), null);
    assertThat(RecommendationCandidate.computeIsNew(p, TODAY, NEW_DAYS)).isTrue();
  }

  @Test
  void computeIsNew_publishedExactlyAtBoundary_true() {
    JobPosting p = posting(2L, "회사A", null, TODAY.minusDays(NEW_DAYS), null);
    assertThat(RecommendationCandidate.computeIsNew(p, TODAY, NEW_DAYS)).isTrue();
  }

  @Test
  void computeIsNew_publishedBeyondBoundary_false() {
    JobPosting p = posting(3L, "회사A", null, TODAY.minusDays(NEW_DAYS + 1), null);
    assertThat(RecommendationCandidate.computeIsNew(p, TODAY, NEW_DAYS)).isFalse();
  }

  @Test
  void computeIsNew_usesPublishedAtWhenPresent() {
    // collectedDate is old, publishedAt is fresh → should be new
    LocalDateTime freshPublished = TODAY.minusDays(1).atStartOfDay();
    JobPosting p =
        JobPosting.create(
            "개발자",
            "회사A",
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
            TODAY.minusDays(30),
            freshPublished);
    assertThat(RecommendationCandidate.computeIsNew(p, TODAY, NEW_DAYS)).isTrue();
  }

  @Test
  void computeIsUrgent_deadlineTomorrow_true() {
    JobPosting p = posting(4L, "회사A", TODAY.plusDays(1), null, null);
    assertThat(RecommendationCandidate.computeIsUrgent(p, TODAY, URGENT_DAYS)).isTrue();
  }

  @Test
  void computeIsUrgent_deadlineExactlyAtBoundary_true() {
    JobPosting p = posting(5L, "회사A", TODAY.plusDays(URGENT_DAYS), null, null);
    assertThat(RecommendationCandidate.computeIsUrgent(p, TODAY, URGENT_DAYS)).isTrue();
  }

  @Test
  void computeIsUrgent_deadlineBeyondBoundary_false() {
    JobPosting p = posting(6L, "회사A", TODAY.plusDays(URGENT_DAYS + 1), null, null);
    assertThat(RecommendationCandidate.computeIsUrgent(p, TODAY, URGENT_DAYS)).isFalse();
  }

  @Test
  void computeIsUrgent_deadlineToday_true() {
    JobPosting p = posting(7L, "회사A", TODAY, null, null);
    assertThat(RecommendationCandidate.computeIsUrgent(p, TODAY, URGENT_DAYS)).isTrue();
  }

  @Test
  void computeIsUrgent_deadlinePast_false() {
    JobPosting p = posting(8L, "회사A", TODAY.minusDays(1), null, null);
    assertThat(RecommendationCandidate.computeIsUrgent(p, TODAY, URGENT_DAYS)).isFalse();
  }

  @Test
  void computeIsUrgent_noDeadline_false() {
    JobPosting p = posting(9L, "회사A", null, null, null);
    assertThat(RecommendationCandidate.computeIsUrgent(p, TODAY, URGENT_DAYS)).isFalse();
  }

  // ---------------------------------------------------------------------------
  // Minimum quotas
  // ---------------------------------------------------------------------------

  @Test
  void select_ensuresMinNew() {
    List<RecommendationCandidate> candidates = new ArrayList<>();
    // 3 new, 4 old
    for (int i = 0; i < 3; i++) {
      candidates.add(newOnly(100L + i, "회사" + i, 50 - i));
    }
    for (int i = 0; i < 4; i++) {
      candidates.add(neitherNewNorUrgent(200L + i, "회사다" + i, 30 - i));
    }

    List<RecommendationCandidate> result = RecommendationSelector.select(candidates);
    long newCount = result.stream().filter(RecommendationCandidate::isNew).count();
    assertThat(newCount).isGreaterThanOrEqualTo(MIN_NEW);
  }

  @Test
  void select_ensuresMinUrgent() {
    List<RecommendationCandidate> candidates = new ArrayList<>();
    // 2 urgent-only, 5 neither
    candidates.add(urgentOnly(300L, "회사가", 60));
    candidates.add(urgentOnly(301L, "회사나", 55));
    for (int i = 0; i < 5; i++) {
      candidates.add(neitherNewNorUrgent(400L + i, "회사다" + i, 30 - i));
    }

    List<RecommendationCandidate> result = RecommendationSelector.select(candidates);
    long urgentCount = result.stream().filter(RecommendationCandidate::isUrgent).count();
    assertThat(urgentCount).isGreaterThanOrEqualTo(MIN_URGENT);
  }

  @Test
  void select_resultNeverExceedsMax() {
    // 20 candidates — result must be ≤ 7
    List<RecommendationCandidate> candidates =
        IntStream.range(0, 20)
            .mapToObj(i -> neitherNewNorUrgent(500L + i, "회사" + (i % 5), 50 - i))
            .collect(Collectors.toList());

    List<RecommendationCandidate> result = RecommendationSelector.select(candidates);
    assertThat(result).hasSizeLessThanOrEqualTo(MAX_RECOMMENDATIONS);
  }

  // ---------------------------------------------------------------------------
  // NEW+URGENT posting: counts for both, selected once
  // ---------------------------------------------------------------------------

  @Test
  void select_newAndUrgent_countsBothQuotasSelectedOnce() {
    List<RecommendationCandidate> candidates = new ArrayList<>();
    // 1 new+urgent posting
    candidates.add(newAndUrgent(600L, "회사가", 90));
    // 1 more new (not urgent)
    candidates.add(newOnly(601L, "회사나", 70));
    // fill remaining
    for (int i = 0; i < 5; i++) {
      candidates.add(neitherNewNorUrgent(700L + i, "회사다" + i, 40 - i));
    }

    List<RecommendationCandidate> result = RecommendationSelector.select(candidates);

    // The new+urgent posting appears exactly once
    long timesId600Appears =
        result.stream().filter(c -> Long.valueOf(600L).equals(c.posting().getId())).count();
    assertThat(timesId600Appears).isEqualTo(1);

    // URGENT minimum satisfied
    assertThat(result.stream().filter(RecommendationCandidate::isUrgent).count())
        .isGreaterThanOrEqualTo(MIN_URGENT);
    // NEW minimum satisfied
    assertThat(result.stream().filter(RecommendationCandidate::isNew).count())
        .isGreaterThanOrEqualTo(MIN_NEW);
  }

  @Test
  void select_noDuplicatePostings() {
    List<RecommendationCandidate> candidates = new ArrayList<>();
    // Same id appears three times (same object added repeatedly) — should appear once
    RecommendationCandidate c = neitherNewNorUrgent(800L, "회사A", 70);
    candidates.add(c);
    candidates.add(c);
    candidates.add(c);
    for (int i = 1; i < 7; i++) {
      candidates.add(neitherNewNorUrgent(800L + i, "회사B" + i, 60 - i));
    }

    List<RecommendationCandidate> result = RecommendationSelector.select(candidates);
    long count800 =
        result.stream().filter(r -> Long.valueOf(800L).equals(r.posting().getId())).count();
    assertThat(count800).isEqualTo(1);
  }

  // ---------------------------------------------------------------------------
  // Per-company cap
  // ---------------------------------------------------------------------------

  @Test
  void select_maxPerCompanyCap_enforced() {
    // 5 postings from "토스", only 2 from other companies
    List<RecommendationCandidate> candidates = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      candidates.add(neitherNewNorUrgent(900L + i, "토스", 80 - i));
    }
    for (int i = 0; i < 2; i++) {
      candidates.add(neitherNewNorUrgent(950L + i, "카카오", 50 - i));
    }

    List<RecommendationCandidate> result = RecommendationSelector.select(candidates);

    long tossCount =
        result.stream().filter(c -> "토스".equalsIgnoreCase(c.posting().getCompany())).count();
    assertThat(tossCount).isLessThanOrEqualTo(MAX_PER_COMPANY);
  }

  @Test
  void select_companyCapNotViolatedForMinUrgent() {
    // 3 urgent postings from same company — cap still respected even for URGENT reserve
    List<RecommendationCandidate> candidates = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      candidates.add(urgentOnly(1000L + i, "단일회사", 70 - i));
    }
    // Enough others to fill
    for (int i = 0; i < 4; i++) {
      candidates.add(neitherNewNorUrgent(1100L + i, "기타회사" + i, 50 - i));
    }

    List<RecommendationCandidate> result = RecommendationSelector.select(candidates);
    long singleCompanyCount =
        result.stream().filter(c -> "단일회사".equalsIgnoreCase(c.posting().getCompany())).count();
    assertThat(singleCompanyCount).isLessThanOrEqualTo(MAX_PER_COMPANY);
  }

  // ---------------------------------------------------------------------------
  // Graceful degradation when quotas can't be met
  // ---------------------------------------------------------------------------

  @Test
  void select_insufficientNewCandidates_degradesGracefully() {
    // Only 1 new posting available when MIN_NEW = 2
    List<RecommendationCandidate> candidates = new ArrayList<>();
    candidates.add(newOnly(1200L, "회사A", 90));
    for (int i = 0; i < 6; i++) {
      candidates.add(neitherNewNorUrgent(1300L + i, "회사B" + i, 50 - i));
    }

    // Should not throw; returns best-effort result
    List<RecommendationCandidate> result = RecommendationSelector.select(candidates);
    assertThat(result).isNotNull();
    assertThat(result).hasSizeLessThanOrEqualTo(MAX_RECOMMENDATIONS);
  }

  @Test
  void select_emptyInput_returnsEmpty() {
    assertThat(RecommendationSelector.select(List.of())).isEmpty();
  }

  @Test
  void select_fewerCandidatesThanMax_returnsAll() {
    List<RecommendationCandidate> candidates = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      candidates.add(neitherNewNorUrgent(1400L + i, "회사" + i, 60 - i));
    }
    assertThat(RecommendationSelector.select(candidates)).hasSizeLessThanOrEqualTo(4);
  }

  // ---------------------------------------------------------------------------
  // Determinism
  // ---------------------------------------------------------------------------

  @Test
  void select_sameInputSameResult_deterministic() {
    List<RecommendationCandidate> candidates = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      candidates.add(neitherNewNorUrgent(1500L + i, "회사" + (i % 3), 60 - (i % 5)));
    }

    List<RecommendationCandidate> first = RecommendationSelector.select(candidates);
    List<RecommendationCandidate> second = RecommendationSelector.select(candidates);

    assertThat(first.stream().map(c -> c.posting().getId()).toList())
        .isEqualTo(second.stream().map(c -> c.posting().getId()).toList());
  }

  // ---------------------------------------------------------------------------
  // Helpers — candidate factories
  // ---------------------------------------------------------------------------

  /** NEW only (collectedDate = today, no deadline). */
  private static RecommendationCandidate newOnly(long id, String company, int score) {
    JobPosting p = posting(id, company, null, TODAY, null);
    return candidate(p, true, false, score);
  }

  /** URGENT only (deadline = today+3, collectedDate = old). */
  private static RecommendationCandidate urgentOnly(long id, String company, int score) {
    JobPosting p = posting(id, company, TODAY.plusDays(3), TODAY.minusDays(30), null);
    return candidate(p, false, true, score);
  }

  /** NEW and URGENT. */
  private static RecommendationCandidate newAndUrgent(long id, String company, int score) {
    JobPosting p = posting(id, company, TODAY.plusDays(3), TODAY, null);
    return candidate(p, true, true, score);
  }

  /** Neither NEW nor URGENT. */
  private static RecommendationCandidate neitherNewNorUrgent(long id, String company, int score) {
    JobPosting p = posting(id, company, TODAY.plusDays(30), TODAY.minusDays(30), null);
    return candidate(p, false, false, score);
  }

  private static RecommendationCandidate candidate(
      JobPosting posting, boolean isNew, boolean isUrgent, int adjustedScore) {
    ScoreBreakdown bd = ScoreBreakdown.ofRelevance(adjustedScore, 0, 0, 0, 0, 0, 0, 0);
    return new RecommendationCandidate(posting, isNew, isUrgent, bd, MatchEvidence.empty());
  }

  /**
   * Creates a JobPosting with a stable ID field via reflection so that candidateKey() can
   * distinguish postings in selection logic (which uses {@code getId()}).
   */
  private static JobPosting posting(
      Long id,
      String company,
      LocalDate deadline,
      LocalDate collectedDate,
      LocalDateTime publishedAt) {
    JobPosting p =
        JobPosting.create(
            "개발자",
            company,
            "원티드",
            "https://e.com/" + id,
            null,
            deadline,
            null,
            null,
            null,
            null,
            null,
            "hash" + id,
            collectedDate != null ? collectedDate : TODAY,
            publishedAt);
    // Set id via reflection — JobPosting is a JPA entity with no public id setter
    try {
      java.lang.reflect.Field f = p.getClass().getDeclaredField("id");
      f.setAccessible(true);
      f.set(p, id);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      throw new RuntimeException("Cannot set id for test", e);
    }
    return p;
  }
}
