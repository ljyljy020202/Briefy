package com.briefy.domain.candidatepool.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.briefy.config.JpaConfig;
import com.briefy.domain.candidatepool.dto.CandidatePoolUpsertResult;
import com.briefy.domain.candidatepool.dto.CollectedJobPostingData;
import com.briefy.domain.candidatepool.entity.JobPosting;
import com.briefy.domain.candidatepool.entity.JobPostingSource;
import com.briefy.domain.candidatepool.repository.JobPostingRepository;
import com.briefy.domain.candidatepool.repository.JobPostingSourceRepository;
import java.time.LocalDate;
import java.util.List;
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
      "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
    })
@Import({CandidatePoolService.class, JpaConfig.class})
class CandidatePoolServiceIntegrationTest {

  @Autowired private CandidatePoolService candidatePoolService;
  @Autowired private JobPostingRepository jobPostingRepository;
  @Autowired private JobPostingSourceRepository jobPostingSourceRepository;
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
        null);
  }

  private CollectedJobPostingData posting(String source, String url, String contentHash) {
    return posting(source, url, contentHash, "백엔드 개발자");
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
            "fingerprint1"); // canonicalFingerprint matches the existing DB row
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
            null);

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
            null, null, null, null, null, null, null, null, null, null, null, null);

    assertThatThrownBy(() -> candidatePoolService.upsertJobPostings(List.of(good, bad), DATE_1))
        .isInstanceOf(Exception.class);

    // Both the good posting and its source were rolled back.
    assertThat(jobPostingRepository.count()).isZero();
    assertThat(jobPostingSourceRepository.count()).isZero();
  }
}
