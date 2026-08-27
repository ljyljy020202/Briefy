package com.briefy.domain.collection.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.briefy.domain.collection.entity.CollectionJob;
import com.briefy.domain.collection.entity.CollectionJobStatus;
import com.briefy.domain.collection.entity.CollectionTriggerType;
import com.briefy.domain.collection.repository.CollectionJobRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CollectionJobIdempotencyTest {

  @Mock private CollectionJobRepository collectionJobRepository;
  @Mock private EntityManager entityManager;

  private CollectionJobService collectionJobService;

  private static final LocalDate TEST_DATE = LocalDate.of(2026, 8, 1);

  @BeforeEach
  void setUp() {
    collectionJobService = new CollectionJobService(collectionJobRepository);
    collectionJobService.entityManager = entityManager;
  }

  private CollectionJob pendingJob() {
    return CollectionJob.createPending(
        TEST_DATE, "[\"JOB_POSTING\"]", CollectionTriggerType.MANUAL);
  }

  // ── createOrGetForDate ────────────────────────────────────────────────────

  @Test
  void createOrGetForDate_firstCall_returnsNewPendingJob() {
    CollectionJob job = pendingJob();
    // find-first: 첫 조회에서 없음 → save() 호출
    when(collectionJobRepository.findByCollectionDate(TEST_DATE)).thenReturn(Optional.empty());
    when(collectionJobRepository.save(any())).thenReturn(job);

    CollectionJob result =
        collectionJobService.createOrGetForDate(
            TEST_DATE, "[\"JOB_POSTING\"]", CollectionTriggerType.MANUAL);

    assertThat(result.getStatus()).isEqualTo(CollectionJobStatus.PENDING);
    assertThat(result.getCollectionDate()).isEqualTo(TEST_DATE);
  }

  @Test
  void createOrGetForDate_existingCompletedJob_returnsImmediatelyWithoutInsert() {
    // 순차 재시도 시나리오: 첫 조회에서 COMPLETED job 발견 → save() 호출 없이 즉시 반환
    CollectionJob completedJob = pendingJob();
    completedJob.complete(10, 8, 2);
    when(collectionJobRepository.findByCollectionDate(TEST_DATE))
        .thenReturn(Optional.of(completedJob));

    CollectionJob result =
        collectionJobService.createOrGetForDate(
            TEST_DATE, "[\"JOB_POSTING\"]", CollectionTriggerType.MANUAL);

    assertThat(result.getStatus()).isEqualTo(CollectionJobStatus.COMPLETED);
    assertThat(result.getSavedCount()).isEqualTo(8);
    verify(collectionJobRepository, never()).save(any());
  }

  @Test
  void createOrGetForDate_concurrentRace_returnsExistingJob() {
    // 동시 race condition: 첫 조회에서 없음 → save() DIVE → em.clear() 후 fallback 조회
    CollectionJob existingJob = pendingJob();
    when(collectionJobRepository.findByCollectionDate(TEST_DATE))
        .thenReturn(Optional.empty()) // 첫 조회: 없음
        .thenReturn(Optional.of(existingJob)); // fallback 조회: 경쟁 스레드가 만든 job 반환
    when(collectionJobRepository.save(any()))
        .thenThrow(new DataIntegrityViolationException("Duplicate entry"));

    CollectionJob result =
        collectionJobService.createOrGetForDate(
            TEST_DATE, "[\"JOB_POSTING\"]", CollectionTriggerType.MANUAL);

    assertThat(result).isEqualTo(existingJob);
    verify(entityManager).clear();
  }

  @Test
  void createOrGetForDate_concurrentRace_fallbackAlsoEmpty_rethrowsOriginal() {
    // 동시 race condition + fallback 조회도 실패 → 원래 예외 전파
    DataIntegrityViolationException original =
        new DataIntegrityViolationException("Duplicate entry");
    when(collectionJobRepository.findByCollectionDate(TEST_DATE))
        .thenReturn(Optional.empty()) // 첫 조회: 없음
        .thenReturn(Optional.empty()); // fallback 조회: 여전히 없음
    when(collectionJobRepository.save(any())).thenThrow(original);

    assertThatThrownBy(
            () ->
                collectionJobService.createOrGetForDate(
                    TEST_DATE, "[\"JOB_POSTING\"]", CollectionTriggerType.MANUAL))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  // ── claimForProcessing ────────────────────────────────────────────────────

  @Test
  void claimForProcessing_oneRowUpdated_returnsTrue() {
    when(collectionJobRepository.claimPending(eq(1L), any(LocalDateTime.class))).thenReturn(1);

    boolean claimed = collectionJobService.claimForProcessing(1L);

    assertThat(claimed).isTrue();
  }

  @Test
  void claimForProcessing_zeroRowsUpdated_returnsFalse() {
    when(collectionJobRepository.claimPending(eq(1L), any(LocalDateTime.class))).thenReturn(0);

    boolean claimed = collectionJobService.claimForProcessing(1L);

    assertThat(claimed).isFalse();
  }

  // ── DailyCollectionService state-routing tests ────────────────────────────
  // These test the branch logic via collectionJobService.createOrGetForDate state.

  @Test
  void createOrGetForDate_processingStatus_callerShouldThrowAlreadyActive() {
    CollectionJob processingJob = pendingJob();
    processingJob.startProcessing();
    // find-first: 첫 조회에서 PROCESSING job 발견 → save() 없이 반환
    when(collectionJobRepository.findByCollectionDate(TEST_DATE))
        .thenReturn(Optional.of(processingJob));

    CollectionJob result =
        collectionJobService.createOrGetForDate(
            TEST_DATE, "[\"JOB_POSTING\"]", CollectionTriggerType.MANUAL);

    assertThat(result.getStatus()).isEqualTo(CollectionJobStatus.PROCESSING);
    verify(collectionJobRepository, never()).save(any());
  }

  @Test
  void createOrGetForDate_completedStatus_callerShouldReturnExistingResult() {
    CollectionJob completedJob = pendingJob();
    completedJob.complete(10, 8, 2);
    when(collectionJobRepository.findByCollectionDate(TEST_DATE))
        .thenReturn(Optional.of(completedJob));

    CollectionJob result =
        collectionJobService.createOrGetForDate(
            TEST_DATE, "[\"JOB_POSTING\"]", CollectionTriggerType.MANUAL);

    assertThat(result.getStatus()).isEqualTo(CollectionJobStatus.COMPLETED);
    assertThat(result.getSavedCount()).isEqualTo(8);
    verify(collectionJobRepository, never()).save(any());
  }

  @Test
  void createOrGetForDate_failedStatus_callerShouldReturnFailedResult() {
    CollectionJob failedJob = pendingJob();
    failedJob.fail("Agent timeout");
    when(collectionJobRepository.findByCollectionDate(TEST_DATE))
        .thenReturn(Optional.of(failedJob));

    CollectionJob result =
        collectionJobService.createOrGetForDate(
            TEST_DATE, "[\"JOB_POSTING\"]", CollectionTriggerType.MANUAL);

    assertThat(result.getStatus()).isEqualTo(CollectionJobStatus.FAILED);
    assertThat(result.getErrorMessage()).isEqualTo("Agent timeout");
    verify(collectionJobRepository, never()).save(any());
  }
}
