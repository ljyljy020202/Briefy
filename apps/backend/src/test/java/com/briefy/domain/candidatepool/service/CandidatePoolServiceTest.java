package com.briefy.domain.candidatepool.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.briefy.domain.candidatepool.dto.CandidatePoolUpsertResult;
import com.briefy.domain.candidatepool.dto.CollectedJobPostingData;
import com.briefy.domain.candidatepool.entity.JobPosting;
import com.briefy.domain.candidatepool.entity.JobPostingSource;
import com.briefy.domain.candidatepool.repository.JobPostingRepository;
import com.briefy.domain.candidatepool.repository.JobPostingSourceRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CandidatePoolServiceTest {

  @Mock private JobPostingRepository jobPostingRepository;
  @Mock private JobPostingSourceRepository jobPostingSourceRepository;

  @InjectMocks private CandidatePoolService candidatePoolService;

  private static final LocalDate COLLECTED_DATE = LocalDate.of(2026, 6, 30);

  private CollectedJobPostingData data(String url, String contentHash) {
    return new CollectedJobPostingData(
        "백엔드 개발자",
        "네이버",
        "원티드",
        url,
        "서울",
        LocalDate.of(2026, 7, 15),
        "채용 공고 설명",
        "[\"백엔드 개발자\"]",
        "[\"Java\", \"Spring Boot\"]",
        "정규직",
        "신입",
        contentHash,
        null);
  }

  private JobPosting existingPosting(String url) {
    return JobPosting.create(
        "백엔드 개발자",
        "네이버",
        "원티드",
        url,
        "서울",
        null,
        "기존 설명",
        null,
        null,
        null,
        null,
        "oldhash",
        LocalDate.of(2026, 6, 1),
        null);
  }

  @Test
  void upsertJobPostings_newPosting_savedAndCountsAreCorrect() {
    CollectedJobPostingData posting = data("https://example.com/job/1", "hash1");
    stubSourceNotFound("원티드", "https://example.com/job/1");
    when(jobPostingRepository.findFirstByCanonicalFingerprint("hash1"))
        .thenReturn(Optional.empty());
    when(jobPostingRepository.findFirstByUrl("https://example.com/job/1"))
        .thenReturn(Optional.empty());
    when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(inv -> inv.getArgument(0));

    CandidatePoolUpsertResult result =
        candidatePoolService.upsertJobPostings(List.of(posting), COLLECTED_DATE);

    assertThat(result.collectedCount()).isEqualTo(1);
    assertThat(result.savedCount()).isEqualTo(1);
    assertThat(result.duplicateCount()).isEqualTo(0);
    verify(jobPostingRepository).save(any(JobPosting.class));
  }

  @Test
  void upsertJobPostings_exactSourceRecord_touchedAndCountedAsDuplicate() {
    String url = "https://example.com/job/2";
    String sourceKey = CandidatePoolService.buildSourceRecordKey("원티드", url);
    JobPostingSource existingSource = buildMockSource();
    when(jobPostingSourceRepository.findBySourceRecordKey(sourceKey))
        .thenReturn(Optional.of(existingSource));

    CollectedJobPostingData incoming = data(url, "newhash");
    CandidatePoolUpsertResult result =
        candidatePoolService.upsertJobPostings(List.of(incoming), COLLECTED_DATE);

    assertThat(result.savedCount()).isEqualTo(0);
    assertThat(result.duplicateCount()).isEqualTo(1);
    verify(jobPostingRepository, never()).save(any());
  }

  @Test
  void upsertJobPostings_canonicalByUrl_attachesNewSource() {
    String url = "https://example.com/job/3";
    JobPosting existing = existingPosting(url);
    LocalDate newDeadline = LocalDate.of(2026, 7, 20);

    CollectedJobPostingData incoming =
        new CollectedJobPostingData(
            "백엔드 개발자",
            "네이버",
            "원티드",
            url,
            "서울",
            newDeadline,
            "새 공고 설명",
            null,
            null,
            null,
            null,
            "newhash",
            null);

    stubSourceNotFound("원티드", url);
    when(jobPostingRepository.findFirstByCanonicalFingerprint("newhash"))
        .thenReturn(Optional.empty());
    when(jobPostingRepository.findFirstByUrl(url)).thenReturn(Optional.of(existing));

    CandidatePoolUpsertResult result =
        candidatePoolService.upsertJobPostings(List.of(incoming), COLLECTED_DATE);

    assertThat(result.savedCount()).isEqualTo(0);
    assertThat(result.duplicateCount()).isEqualTo(1);
    assertThat(existing.getDeadline()).isEqualTo(newDeadline);
    assertThat(existing.getDescription()).isEqualTo("새 공고 설명");
    assertThat(existing.getContentHash()).isEqualTo("newhash");
    assertThat(existing.getCollectedDate()).isEqualTo(COLLECTED_DATE);
    verify(jobPostingRepository, never()).save(any());
    verify(jobPostingSourceRepository).save(any(JobPostingSource.class));
  }

  @Test
  void upsertJobPostings_canonicalByFingerprint_attachesNewSource() {
    String url = "https://example.com/job/4";
    String contentHash = "duplicatehash";
    JobPosting existing = existingPosting("https://example.com/original-url");

    stubSourceNotFound("원티드", url);
    when(jobPostingRepository.findFirstByCanonicalFingerprint(contentHash))
        .thenReturn(Optional.of(existing));

    CollectedJobPostingData incoming = data(url, contentHash);
    CandidatePoolUpsertResult result =
        candidatePoolService.upsertJobPostings(List.of(incoming), COLLECTED_DATE);

    assertThat(result.savedCount()).isEqualTo(0);
    assertThat(result.duplicateCount()).isEqualTo(1);
    verify(jobPostingRepository, never()).save(any());
    verify(jobPostingSourceRepository).save(any(JobPostingSource.class));
  }

  @Test
  void upsertJobPostings_mixedBatch_correctCountsAndSaveCallCount() {
    CollectedJobPostingData newPosting = data("https://example.com/job/5", "hash5");
    CollectedJobPostingData sourceDup = data("https://example.com/job/6", "hash6");
    CollectedJobPostingData fingerprintDup = data("https://example.com/job/7", "hash7");

    // new posting: no source, no canonical
    stubSourceNotFound("원티드", "https://example.com/job/5");
    when(jobPostingRepository.findFirstByCanonicalFingerprint("hash5"))
        .thenReturn(Optional.empty());
    when(jobPostingRepository.findFirstByUrl("https://example.com/job/5"))
        .thenReturn(Optional.empty());
    when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(inv -> inv.getArgument(0));

    // source dup: exact source record found
    String sourceKey6 =
        CandidatePoolService.buildSourceRecordKey("원티드", "https://example.com/job/6");
    when(jobPostingSourceRepository.findBySourceRecordKey(sourceKey6))
        .thenReturn(Optional.of(buildMockSource()));

    // fingerprint dup: no source, but canonical by fingerprint
    stubSourceNotFound("원티드", "https://example.com/job/7");
    when(jobPostingRepository.findFirstByCanonicalFingerprint("hash7"))
        .thenReturn(Optional.of(existingPosting("https://example.com/other")));

    CandidatePoolUpsertResult result =
        candidatePoolService.upsertJobPostings(
            List.of(newPosting, sourceDup, fingerprintDup), COLLECTED_DATE);

    assertThat(result.collectedCount()).isEqualTo(3);
    assertThat(result.savedCount()).isEqualTo(1);
    assertThat(result.duplicateCount()).isEqualTo(2);
    verify(jobPostingRepository, times(1)).save(any(JobPosting.class));
  }

  @Test
  void upsertJobPostings_emptyList_returnsZeroCounts() {
    CandidatePoolUpsertResult result =
        candidatePoolService.upsertJobPostings(List.of(), COLLECTED_DATE);

    assertThat(result.collectedCount()).isEqualTo(0);
    assertThat(result.savedCount()).isEqualTo(0);
    assertThat(result.duplicateCount()).isEqualTo(0);
    verify(jobPostingRepository, never()).save(any());
  }

  @Test
  void upsertJobPostings_nullUrl_createsPostingWithoutSourceRecord() {
    CollectedJobPostingData noUrl =
        new CollectedJobPostingData(
            "백엔드 개발자",
            "네이버",
            "원티드",
            null,
            "서울",
            null,
            null,
            null,
            null,
            null,
            null,
            "hashNoUrl",
            null);

    // No source lookup when url is null (sourceRecordKey is null)
    when(jobPostingRepository.findFirstByCanonicalFingerprint("hashNoUrl"))
        .thenReturn(Optional.empty());
    // No url lookup either (url is null)
    when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(inv -> inv.getArgument(0));

    CandidatePoolUpsertResult result =
        candidatePoolService.upsertJobPostings(List.of(noUrl), COLLECTED_DATE);

    assertThat(result.savedCount()).isEqualTo(1);
    verify(jobPostingSourceRepository, never()).save(any());
  }

  @Test
  void findJobPostingsByDate_delegatesToRepository() {
    List<JobPosting> expected =
        List.of(
            JobPosting.create(
                "풀스택 개발자",
                "카카오",
                null,
                "https://example.com/job/8",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                COLLECTED_DATE,
                null));
    when(jobPostingRepository.findAllByCollectedDate(COLLECTED_DATE)).thenReturn(expected);

    List<JobPosting> result = candidatePoolService.findJobPostingsByDate(COLLECTED_DATE);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).getCompany()).isEqualTo("카카오");
    verify(jobPostingRepository).findAllByCollectedDate(COLLECTED_DATE);
  }

  @Test
  void buildSourceRecordKey_nullUrl_returnsNull() {
    assertThat(CandidatePoolService.buildSourceRecordKey("원티드", null)).isNull();
  }

  @Test
  void buildSourceRecordKey_sameInputs_returnsStableKey() {
    String key1 = CandidatePoolService.buildSourceRecordKey("원티드", "https://example.com");
    String key2 = CandidatePoolService.buildSourceRecordKey("원티드", "https://example.com");
    assertThat(key1).isEqualTo(key2).hasSize(64);
  }

  @Test
  void buildSourceRecordKey_differentSources_returnsDifferentKeys() {
    String wanted = CandidatePoolService.buildSourceRecordKey("원티드", "https://example.com");
    String saramin = CandidatePoolService.buildSourceRecordKey("사람인", "https://example.com");
    assertThat(wanted).isNotEqualTo(saramin);
  }

  @Test
  void buildSourceRecordKey_nullSource_returnsDifferentKeyFromNamedSource() {
    String noSource = CandidatePoolService.buildSourceRecordKey(null, "https://example.com");
    String withSource = CandidatePoolService.buildSourceRecordKey("원티드", "https://example.com");
    assertThat(noSource).isNotNull().hasSize(64).isNotEqualTo(withSource);
  }

  private void stubSourceNotFound(String source, String url) {
    String key = CandidatePoolService.buildSourceRecordKey(source, url);
    when(jobPostingSourceRepository.findBySourceRecordKey(key)).thenReturn(Optional.empty());
  }

  private JobPostingSource buildMockSource() {
    JobPosting jp =
        JobPosting.create(
            "백엔드",
            "네이버",
            "원티드",
            "https://x.com",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            COLLECTED_DATE,
            null);
    return JobPostingSource.create(
        jp, "원티드", null, "https://x.com", "key", null, null, COLLECTED_DATE);
  }
}
