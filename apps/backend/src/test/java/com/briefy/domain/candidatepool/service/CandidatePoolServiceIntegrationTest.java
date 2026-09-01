package com.briefy.domain.candidatepool.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.briefy.config.JpaConfig;
import com.briefy.domain.candidatepool.dto.CandidatePoolUpsertResult;
import com.briefy.domain.candidatepool.dto.CollectedJobPostingData;
import com.briefy.domain.candidatepool.entity.JobPosting;
import com.briefy.domain.candidatepool.entity.JobPostingAnalysis;
import com.briefy.domain.candidatepool.entity.JobPostingSource;
import com.briefy.domain.candidatepool.entity.analysis.ClassificationStatus;
import com.briefy.domain.candidatepool.repository.JobPostingAnalysisRepository;
import com.briefy.domain.candidatepool.repository.JobPostingRepository;
import com.briefy.domain.candidatepool.repository.JobPostingSourceRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest
@TestPropertySource(
    properties = {
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
      "briefy.classifier.version=1.0.0"
    })
@Import({CandidatePoolService.class, JpaConfig.class})
class CandidatePoolServiceIntegrationTest {

  @Autowired private CandidatePoolService candidatePoolService;
  @Autowired private JobPostingRepository jobPostingRepository;
  @Autowired private JobPostingSourceRepository jobPostingSourceRepository;
  @Autowired private JobPostingAnalysisRepository jobPostingAnalysisRepository;
  @Autowired private TestEntityManager em;

  private static final LocalDate DATE_1 = LocalDate.of(2026, 7, 1);
  private static final LocalDate DATE_2 = LocalDate.of(2026, 7, 2);

  // --- helpers ---

  private CollectedJobPostingData posting(
      String source, String url, String contentHash, String title) {
    return new CollectedJobPostingData(
        title,
        "네이버",
        source,
        url,
        "서울",
        null,
        "설명",
        null,
        null,
        null,
        null,
        contentHash,
        null,
        null,
        null,
        null,
        null); // descriptionTruncated
  }

  private CollectedJobPostingData posting(String source, String url, String contentHash) {
    return posting(source, url, contentHash, "백엔드 개발자");
  }

  private CollectedJobPostingData postingWithDeadline(
      String source, String url, String contentHash, LocalDate deadline) {
    return new CollectedJobPostingData(
        "백엔드 개발자",
        "네이버",
        source,
        url,
        "서울",
        deadline,
        "설명",
        null,
        null,
        null,
        null,
        contentHash,
        null,
        null,
        null,
        null,
        null); // descriptionTruncated
  }

  private void persist(CollectedJobPostingData data, LocalDate collectedDate) {
    candidatePoolService.upsertJobPostings(List.of(data), collectedDate);
    em.flush();
    em.clear();
  }

  private void deactivateAllSourcesFor(String url) {
    JobPosting jp = jobPostingRepository.findFirstByUrl(url).orElseThrow();
    jobPostingSourceRepository.findAllByJobPosting(jp).forEach(JobPostingSource::deactivate);
    em.flush();
    em.clear();
  }

  /** Deactivates the single source record identified by (source, url). */
  private void deactivateSourceFor(String source, String url) {
    String key = CandidatePoolService.buildSourceRecordKey(source, null, url);
    jobPostingSourceRepository
        .findBySourceRecordKey(key)
        .ifPresent(
            s -> {
              s.deactivate();
              em.flush();
              em.clear();
            });
  }

  // --- tests ---

  @Test
  void sameUrlRecollection_onlyOnePostingAndOneSourceRecordPersisted() {
    CollectedJobPostingData first = posting("원티드", "https://example.com/1", "hash1");
    candidatePoolService.upsertJobPostings(List.of(first), DATE_1);
    em.flush();
    em.clear();

    CollectedJobPostingData second = posting("원티드", "https://example.com/1", "hash1b");
    CandidatePoolUpsertResult result =
        candidatePoolService.upsertJobPostings(List.of(second), DATE_2);
    em.flush();
    em.clear();

    assertThat(result.savedCount()).isEqualTo(0);
    assertThat(result.duplicateCount()).isEqualTo(1);
    assertThat(jobPostingRepository.count()).isEqualTo(1);
    assertThat(jobPostingSourceRepository.count()).isEqualTo(1);
  }

  @Test
  void sameSourceRecordKey_updatesLastCollectedAtAndLastSeenAt() {
    CollectedJobPostingData first = posting("원티드", "https://example.com/2", "hashA");
    candidatePoolService.upsertJobPostings(List.of(first), DATE_1);
    em.flush();
    em.clear();

    CollectedJobPostingData second = posting("원티드", "https://example.com/2", "hashA-updated");
    candidatePoolService.upsertJobPostings(List.of(second), DATE_2);
    em.flush();
    em.clear();

    List<JobPostingSource> sources = jobPostingSourceRepository.findAll();
    assertThat(sources).hasSize(1);
    assertThat(sources.get(0).getLastCollectedAt()).isEqualTo(DATE_2);
    assertThat(sources.get(0).getSourceContentHash()).isEqualTo("hashA-updated");
    assertThat(sources.get(0).getFirstSeenAt()).isNotNull();
    assertThat(sources.get(0).getLastSeenAt()).isNotNull();
  }

  @Test
  void oneJobPostingWithMultipleSources_createsOnePostingAndTwoSourceRecords() {
    CollectedJobPostingData wantedPosting =
        posting("원티드", "https://wanted.co.kr/job/99", "fingerprint1");
    candidatePoolService.upsertJobPostings(List.of(wantedPosting), DATE_1);
    em.flush();
    em.clear();

    // Same content from a different source — different URL, but the Agent has computed
    // the same canonicalFingerprint, allowing Spring to attach a second source record.
    CollectedJobPostingData jumpitPosting =
        new CollectedJobPostingData(
            "백엔드 개발자",
            "네이버",
            "점핏",
            "https://jumpit.com/job/99",
            "서울",
            null,
            "설명",
            null,
            null,
            null,
            null,
            "fingerprint1",
            null,
            null,
            null,
            "fingerprint1", // canonicalFingerprint matches the existing DB row
            null); // descriptionTruncated
    CandidatePoolUpsertResult result =
        candidatePoolService.upsertJobPostings(List.of(jumpitPosting), DATE_2);
    em.flush();
    em.clear();

    assertThat(result.savedCount()).isEqualTo(0);
    assertThat(result.duplicateCount()).isEqualTo(1);

    // One canonical posting, two source records.
    assertThat(jobPostingRepository.count()).isEqualTo(1);
    assertThat(jobPostingSourceRepository.count()).isEqualTo(2);

    JobPosting canonical = jobPostingRepository.findAll().get(0);
    List<JobPostingSource> sources = jobPostingSourceRepository.findAllByJobPosting(canonical);
    assertThat(sources)
        .extracting(JobPostingSource::getSource)
        .containsExactlyInAnyOrder("원티드", "점핏");
  }

  @Test
  void unknownCompany_persistsWithNullLinkedCompanyAndFreeTextCompanyString() {
    CollectedJobPostingData unknown =
        new CollectedJobPostingData(
            "iOS 개발자",
            "알 수 없는 스타트업",
            "링크드인",
            "https://linkedin.com/jobs/view/999",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            "hash-unknown",
            null,
            null,
            null,
            null,
            null); // descriptionTruncated

    CandidatePoolUpsertResult result =
        candidatePoolService.upsertJobPostings(List.of(unknown), DATE_1);
    em.flush();
    em.clear();

    assertThat(result.savedCount()).isEqualTo(1);

    JobPosting saved = jobPostingRepository.findAll().get(0);
    assertThat(saved.getCompany()).isEqualTo("알 수 없는 스타트업");
    assertThat(saved.getLinkedCompany()).isNull();
    assertThat(saved.getCanonicalFingerprint()).isEqualTo("hash-unknown");
  }

  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void transactionRollback_nothingPersistedWhenBatchFails() {
    // The second posting has a null URL and a null title.
    // title is NOT NULL in the schema — Hibernate will throw on flush,
    // rolling back the entire @Transactional method including the first posting.
    CollectedJobPostingData good = posting("원티드", "https://example.com/rollback-good", "hashGood");
    CollectedJobPostingData bad =
        new CollectedJobPostingData(
            null, // NOT NULL title → constraint violation on flush
            "네이버", null, null, // null url → no source record, no url lookup, falls through to save
            null, null, null, null, null, null, null, null, null, null, null, null,
            null); // descriptionTruncated

    assertThatThrownBy(() -> candidatePoolService.upsertJobPostings(List.of(good, bad), DATE_1))
        .isInstanceOf(Exception.class);

    // Both the good posting and its source were rolled back.
    assertThat(jobPostingRepository.count()).isZero();
    assertThat(jobPostingSourceRepository.count()).isZero();
  }

  // ── findEligibleJobPostingsForBriefing ────────────────────────────────────

  @Test
  void eligible_posting_collected_10_days_ago_with_active_source_is_included() {
    LocalDate tenDaysAgo = DATE_1.minusDays(10);
    persist(
        postingWithDeadline("원티드", "https://example.com/old-but-active", "h1", DATE_1.plusDays(7)),
        tenDaysAgo);

    List<JobPosting> result = candidatePoolService.findEligibleJobPostingsForBriefing(DATE_1);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).getUrl()).isEqualTo("https://example.com/old-but-active");
  }

  @Test
  void eligible_posting_collected_today_is_included() {
    persist(
        postingWithDeadline("원티드", "https://example.com/fresh", "h2", DATE_1.plusDays(14)), DATE_1);

    List<JobPosting> result = candidatePoolService.findEligibleJobPostingsForBriefing(DATE_1);

    assertThat(result).hasSize(1);
  }

  @Test
  void eligible_posting_with_deadline_today_is_included() {
    persist(postingWithDeadline("원티드", "https://example.com/closes-today", "h3", DATE_1), DATE_1);

    List<JobPosting> result = candidatePoolService.findEligibleJobPostingsForBriefing(DATE_1);

    assertThat(result).hasSize(1);
  }

  @Test
  void eligible_posting_with_past_deadline_is_excluded() {
    persist(
        postingWithDeadline("원티드", "https://example.com/expired", "h4", DATE_1.minusDays(1)),
        DATE_1);

    List<JobPosting> result = candidatePoolService.findEligibleJobPostingsForBriefing(DATE_1);

    assertThat(result).isEmpty();
  }

  @Test
  void eligible_posting_with_all_inactive_sources_is_excluded() {
    String url = "https://example.com/all-inactive";
    persist(postingWithDeadline("원티드", url, "h5", DATE_1.plusDays(7)), DATE_1);
    deactivateAllSourcesFor(url);

    List<JobPosting> result = candidatePoolService.findEligibleJobPostingsForBriefing(DATE_1);

    assertThat(result).isEmpty();
  }

  @Test
  void eligible_posting_with_one_active_source_is_included() {
    // Create posting from source A, then add source B (same canonical fingerprint)
    // and deactivate source A — source B still active so posting stays eligible.
    // contentHash for src1 must equal fp so the DB canonical_fingerprint column is fp,
    // allowing src2's canonicalFingerprint lookup to find the same posting.
    String urlA = "https://wanted.co.kr/job/multi";
    String fp = "fp-multi-one-active";
    CollectedJobPostingData wantedData =
        new CollectedJobPostingData(
            "백엔드 개발자",
            "네이버",
            "원티드",
            urlA,
            "서울",
            DATE_1.plusDays(7),
            "설명",
            null,
            null,
            null,
            null,
            fp, // contentHash = fp → DB stores fp as canonical
            null,
            null,
            null,
            fp,
            null); // descriptionTruncated
    persist(wantedData, DATE_1);

    CollectedJobPostingData jumpitData =
        new CollectedJobPostingData(
            "백엔드 개발자",
            "네이버",
            "점핏",
            "https://jumpit.com/job/multi",
            "서울",
            DATE_1.plusDays(7),
            "설명",
            null,
            null,
            null,
            null,
            "fp-multi-one-active-b",
            null,
            null,
            null,
            fp, // canonicalFingerprint = fp → matches DB
            null); // descriptionTruncated
    persist(jumpitData, DATE_1);

    deactivateSourceFor("원티드", urlA); // deactivate only 원티드 source; 점핏 is still active

    List<JobPosting> result = candidatePoolService.findEligibleJobPostingsForBriefing(DATE_1);

    assertThat(result).hasSize(1);
  }

  @Test
  void eligible_posting_with_only_fixture_source_is_excluded() {
    persist(
        postingWithDeadline("fixture", "https://fixture.local/job/1", "h7", DATE_1.plusDays(7)),
        DATE_1);

    List<JobPosting> result = candidatePoolService.findEligibleJobPostingsForBriefing(DATE_1);

    assertThat(result).isEmpty();
  }

  @Test
  void eligible_posting_with_null_deadline_and_active_source_is_included() {
    // null deadline = no expiry → always eligible while source is active
    persist(posting("원티드", "https://example.com/no-deadline", "h8"), DATE_1);

    List<JobPosting> result = candidatePoolService.findEligibleJobPostingsForBriefing(DATE_1);

    assertThat(result).hasSize(1);
  }

  @Test
  void eligible_posting_with_multiple_sources_is_returned_exactly_once() {
    // contentHash for src1 must equal fp so the DB canonical_fingerprint is fp,
    // allowing src2's canonicalFingerprint lookup to merge into the same posting.
    String fp = "fp-multi-sources-once";
    CollectedJobPostingData src1 =
        new CollectedJobPostingData(
            "백엔드 개발자",
            "네이버",
            "원티드",
            "https://wanted.co.kr/job/once",
            "서울",
            DATE_1.plusDays(7),
            "설명",
            null,
            null,
            null,
            null,
            fp,
            null,
            null,
            null,
            fp,
            null); // descriptionTruncated
    CollectedJobPostingData src2 =
        new CollectedJobPostingData(
            "백엔드 개발자",
            "네이버",
            "점핏",
            "https://jumpit.com/job/once",
            "서울",
            DATE_1.plusDays(7),
            "설명",
            null,
            null,
            null,
            null,
            "fp-multi-sources-once-b",
            null,
            null,
            null,
            fp,
            null); // descriptionTruncated
    persist(src1, DATE_1);
    persist(src2, DATE_1);

    List<JobPosting> result = candidatePoolService.findEligibleJobPostingsForBriefing(DATE_1);

    assertThat(result).hasSize(1);
  }

  @Test
  void eligible_zero_today_collections_returns_previously_active_pool() {
    // Persist a posting 5 days ago — no collection today
    LocalDate fiveDaysAgo = DATE_1.minusDays(5);
    persist(
        postingWithDeadline(
            "원티드", "https://example.com/persisted-earlier", "h9", DATE_1.plusDays(10)),
        fiveDaysAgo);

    // Query on DATE_1 even though nothing was collected today
    List<JobPosting> result = candidatePoolService.findEligibleJobPostingsForBriefing(DATE_1);

    assertThat(result).hasSize(1);
  }

  @Test
  void canonical_url_is_not_changed_when_same_source_recollected() {
    String originalUrl = "https://example.com/stable-url";
    persist(posting("원티드", originalUrl, "hashV1"), DATE_1);

    // Re-collect: same source_record_key → touch, no URL update
    persist(posting("원티드", originalUrl, "hashV2"), DATE_2);

    JobPosting saved = jobPostingRepository.findAll().get(0);
    assertThat(saved.getUrl()).isEqualTo(originalUrl);
  }

  @Test
  void canonical_url_is_not_changed_when_cross_source_collected() {
    // contentHash for src1 = fp so the DB canonical_fingerprint column equals fp.
    // src2 provides the same canonicalFingerprint → merged into existing posting.
    // The canonical posting's URL must remain the original one.
    String originalUrl = "https://wanted.co.kr/job/stable";
    String fp = "fp-stable-canonical-url";
    CollectedJobPostingData src1 =
        new CollectedJobPostingData(
            "백엔드 개발자",
            "네이버",
            "원티드",
            originalUrl,
            "서울",
            null,
            "설명",
            null,
            null,
            null,
            null,
            fp, // contentHash = fp → DB canonical_fingerprint = fp
            null,
            null,
            null,
            fp,
            null); // descriptionTruncated
    persist(src1, DATE_1);

    // Same canonical fingerprint, different URL (new source) — must NOT change canonical URL
    CollectedJobPostingData src2 =
        new CollectedJobPostingData(
            "백엔드 개발자",
            "네이버",
            "점핏",
            "https://jumpit.com/job/stable",
            "서울",
            null,
            "설명",
            null,
            null,
            null,
            null,
            "fp-stable-canonical-url-b",
            null,
            null,
            null,
            fp, // canonicalFingerprint = fp → finds existing
            null); // descriptionTruncated
    persist(src2, DATE_2);

    JobPosting canonical = jobPostingRepository.findFirstByCanonicalFingerprint(fp).orElseThrow();
    assertThat(canonical.getUrl()).isEqualTo(originalUrl);
  }

  // ── Stage 2: PENDING 분석 행 자동 등록 ─────────────────────────────────────

  @Test
  void newPosting_registeredWithPendingAnalysisRow() {
    persist(posting("원티드", "https://example.com/analysis-new", "hashA1"), DATE_1);

    JobPosting jp =
        jobPostingRepository.findFirstByUrl("https://example.com/analysis-new").orElseThrow();
    Optional<JobPostingAnalysis> analysis =
        jobPostingAnalysisRepository.findByJobPostingId(jp.getId());

    assertThat(analysis).isPresent();
    assertThat(analysis.get().getClassificationStatus()).isEqualTo(ClassificationStatus.PENDING);
    assertThat(analysis.get().getAnalysisInputHash()).isNotNull().hasSize(64);
    assertThat(analysis.get().getClassifierVersion()).isEqualTo("1.0.0");
    assertThat(analysis.get().getClaimToken()).isNull();
  }

  @Test
  void sameSource_contentHashChanged_triggersAnalysisReset() {
    String url = "https://example.com/analysis-update";
    persist(posting("원티드", url, "hashB1"), DATE_1);

    // 두 번째 수집: contentHash 변경 + 새 경력 정보 → analysisInputHash 변경 예상
    CollectedJobPostingData second =
        new CollectedJobPostingData(
            "백엔드 개발자",
            "네이버",
            "원티드",
            url,
            "서울",
            null,
            "설명",
            "[\"Backend\"]",
            null,
            "정규직",
            "5년 이상",
            "hashB2",
            null,
            null,
            null,
            null,
            null);
    candidatePoolService.upsertJobPostings(List.of(second), DATE_2);
    em.flush();
    em.clear();

    JobPosting jp = jobPostingRepository.findFirstByUrl(url).orElseThrow();
    Optional<JobPostingAnalysis> analysis =
        jobPostingAnalysisRepository.findByJobPostingId(jp.getId());

    assertThat(analysis).isPresent();
    // hash 변경 → PENDING 상태 유지 (재분류 대상)
    assertThat(analysis.get().getClassificationStatus()).isEqualTo(ClassificationStatus.PENDING);
  }

  @Test
  void sameSource_contentHashUnchanged_noExtraAnalysisRow() {
    String url = "https://example.com/analysis-same";
    persist(posting("원티드", url, "hashC1"), DATE_1);

    // 같은 contentHash로 재수집 (내용 동일)
    persist(posting("원티드", url, "hashC1"), DATE_2);

    // 분석 행이 1개뿐 (중복 생성 없음)
    JobPosting jp = jobPostingRepository.findFirstByUrl(url).orElseThrow();
    long count = jobPostingAnalysisRepository.findByJobPostingId(jp.getId()).stream().count();
    assertThat(count).isEqualTo(1);
  }
}
