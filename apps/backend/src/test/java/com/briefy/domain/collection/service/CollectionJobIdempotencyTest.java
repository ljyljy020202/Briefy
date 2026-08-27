package com.briefy.domain.collection.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.briefy.domain.collection.entity.CollectionJob;
import com.briefy.domain.collection.entity.CollectionJobStatus;
import com.briefy.domain.collection.entity.CollectionTriggerType;
import com.briefy.domain.collection.repository.CollectionJobRepository;
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

  private CollectionJobService collectionJobService;

  private static final LocalDate TEST_DATE = LocalDate.of(2026, 8, 1);

  @BeforeEach
  void setUp() {
    collectionJobService = new CollectionJobService(collectionJobRepository);
  }

  private CollectionJob pendingJob() {
    return CollectionJob.createPending(
        TEST_DATE, "[\"JOB_POSTING\"]", CollectionTriggerType.MANUAL);
  }

  // ── createOrGetForDate ────────────────────────────────────────────────────

  @Test
  void createOrGetForDate_firstCall_returnsNewPendingJob() {
    CollectionJob job = pendingJob();
    when(collectionJobRepository.save(any())).thenReturn(job);

    CollectionJob result =
        collectionJobService.createOrGetForDate(
            TEST_DATE, "[\"JOB_POSTING\"]", CollectionTriggerType.MANUAL);

    assertThat(result.getStatus()).isEqualTo(CollectionJobStatus.PENDING);
    assertThat(result.getCollectionDate()).isEqualTo(TEST_DATE);
  }

  @Test
  void createOrGetForDate_uniqueConflict_returnsExistingJob() {
    CollectionJob existingJob = pendingJob();
    when(collectionJobRepository.save(any()))
        .thenThrow(new DataIntegrityViolationException("Duplicate entry"));
    when(collectionJobRepository.findByCollectionDate(TEST_DATE))
        .thenReturn(Optional.of(existingJob));

    CollectionJob result =
        collectionJobService.createOrGetForDate(
            TEST_DATE, "[\"JOB_POSTING\"]", CollectionTriggerType.MANUAL);

    assertThat(result).isEqualTo(existingJob);
  }

  @Test
  void createOrGetForDate_uniqueConflict_findByDateAlsoFails_rethrowsOriginal() {
    DataIntegrityViolationException original =
        new DataIntegrityViolationException("Duplicate entry");
    when(collectionJobRepository.save(any())).thenThrow(original);
    when(collectionJobRepository.findByCollectionDate(TEST_DATE)).thenReturn(Optional.empty());

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
    // Simulate: existing job is PROCESSING
    CollectionJob processingJob = pendingJob();
    // Manually set to PROCESSING by calling startProcessing
    processingJob.startProcessing();
    when(collectionJobRepository.save(any()))
        .thenThrow(new DataIntegrityViolationException("duplicate"));
    when(collectionJobRepository.findByCollectionDate(TEST_DATE))
        .thenReturn(Optional.of(processingJob));

    CollectionJob result =
        collectionJobService.createOrGetForDate(
            TEST_DATE, "[\"JOB_POSTING\"]", CollectionTriggerType.MANUAL);

    // Caller (DailyCollectionService) is responsible for throwing based on status
    assertThat(result.getStatus()).isEqualTo(CollectionJobStatus.PROCESSING);
  }

  @Test
  void createOrGetForDate_completedStatus_callerShouldReturnExistingResult() {
    CollectionJob completedJob = pendingJob();
    completedJob.complete(10, 8, 2);
    when(collectionJobRepository.save(any()))
        .thenThrow(new DataIntegrityViolationException("duplicate"));
    when(collectionJobRepository.findByCollectionDate(TEST_DATE))
        .thenReturn(Optional.of(completedJob));

    CollectionJob result =
        collectionJobService.createOrGetForDate(
            TEST_DATE, "[\"JOB_POSTING\"]", CollectionTriggerType.MANUAL);

    assertThat(result.getStatus()).isEqualTo(CollectionJobStatus.COMPLETED);
    assertThat(result.getSavedCount()).isEqualTo(8);
  }

  @Test
  void createOrGetForDate_failedStatus_callerShouldReturnFailedResult() {
    CollectionJob failedJob = pendingJob();
    failedJob.fail("Agent timeout");
    when(collectionJobRepository.save(any()))
        .thenThrow(new DataIntegrityViolationException("duplicate"));
    when(collectionJobRepository.findByCollectionDate(TEST_DATE))
        .thenReturn(Optional.of(failedJob));

    CollectionJob result =
        collectionJobService.createOrGetForDate(
            TEST_DATE, "[\"JOB_POSTING\"]", CollectionTriggerType.MANUAL);

    assertThat(result.getStatus()).isEqualTo(CollectionJobStatus.FAILED);
    assertThat(result.getErrorMessage()).isEqualTo("Agent timeout");
  }
}
