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
import com.briefy.domain.candidatepool.repository.JobPostingRepository;
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
    when(jobPostingRepository.findByUrl(posting.url())).thenReturn(Optional.empty());
    when(jobPostingRepository.existsByContentHash(posting.contentHash())).thenReturn(false);
    when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(inv -> inv.getArgument(0));

    CandidatePoolUpsertResult result =
        candidatePoolService.upsertJobPostings(List.of(posting), COLLECTED_DATE);

    assertThat(result.collectedCount()).isEqualTo(1);
    assertThat(result.savedCount()).isEqualTo(1);
    assertThat(result.duplicateCount()).isEqualTo(0);
    verify(jobPostingRepository).save(any(JobPosting.class));
  }

  @Test
  void upsertJobPostings_duplicateByUrl_refreshesMutableFieldsAndSkipsSave() {
    String url = "https://example.com/job/2";
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

    when(jobPostingRepository.findByUrl(url)).thenReturn(Optional.of(existing));

    CandidatePoolUpsertResult result =
        candidatePoolService.upsertJobPostings(List.of(incoming), COLLECTED_DATE);

    assertThat(result.collectedCount()).isEqualTo(1);
    assertThat(result.savedCount()).isEqualTo(0);
    assertThat(result.duplicateCount()).isEqualTo(1);
    assertThat(existing.getDeadline()).isEqualTo(newDeadline);
    assertThat(existing.getDescription()).isEqualTo("새 공고 설명");
    assertThat(existing.getContentHash()).isEqualTo("newhash");
    assertThat(existing.getCollectedDate()).isEqualTo(COLLECTED_DATE);
    verify(jobPostingRepository, never()).save(any());
  }

  @Test
  void upsertJobPostings_duplicateByContentHash_skippedWithoutSave() {
    CollectedJobPostingData posting = data("https://example.com/job/3", "duplicatehash");
    when(jobPostingRepository.findByUrl(posting.url())).thenReturn(Optional.empty());
    when(jobPostingRepository.existsByContentHash("duplicatehash")).thenReturn(true);

    CandidatePoolUpsertResult result =
        candidatePoolService.upsertJobPostings(List.of(posting), COLLECTED_DATE);

    assertThat(result.collectedCount()).isEqualTo(1);
    assertThat(result.savedCount()).isEqualTo(0);
    assertThat(result.duplicateCount()).isEqualTo(1);
    verify(jobPostingRepository, never()).save(any());
  }

  @Test
  void upsertJobPostings_mixedBatch_correctCountsAndSaveCallCount() {
    CollectedJobPostingData newPosting = data("https://example.com/job/4", "hash4");
    CollectedJobPostingData urlDup = data("https://example.com/job/5", "hash5");
    CollectedJobPostingData hashDup = data("https://example.com/job/6", "hash6");

    when(jobPostingRepository.findByUrl("https://example.com/job/4")).thenReturn(Optional.empty());
    when(jobPostingRepository.existsByContentHash("hash4")).thenReturn(false);
    when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(inv -> inv.getArgument(0));

    when(jobPostingRepository.findByUrl("https://example.com/job/5"))
        .thenReturn(Optional.of(existingPosting("https://example.com/job/5")));

    when(jobPostingRepository.findByUrl("https://example.com/job/6")).thenReturn(Optional.empty());
    when(jobPostingRepository.existsByContentHash("hash6")).thenReturn(true);

    CandidatePoolUpsertResult result =
        candidatePoolService.upsertJobPostings(
            List.of(newPosting, urlDup, hashDup), COLLECTED_DATE);

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
  void findJobPostingsByDate_delegatesToRepository() {
    List<JobPosting> expected =
        List.of(
            JobPosting.create(
                "풀스택 개발자",
                "카카오",
                null,
                "https://example.com/job/7",
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
}
