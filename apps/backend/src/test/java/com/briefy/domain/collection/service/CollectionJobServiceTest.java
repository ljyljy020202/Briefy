package com.briefy.domain.collection.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.briefy.domain.collection.entity.CollectionJob;
import com.briefy.domain.collection.entity.CollectionJobStatus;
import com.briefy.domain.collection.entity.CollectionTriggerType;
import com.briefy.domain.collection.repository.CollectionJobRepository;
import com.briefy.global.exception.BusinessException;
import com.briefy.global.exception.ErrorCode;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CollectionJobServiceTest {

  @Mock private CollectionJobRepository collectionJobRepository;

  @InjectMocks private CollectionJobService collectionJobService;

  private static final LocalDate TEST_DATE = LocalDate.of(2026, 6, 29);
  private static final String TEST_CATEGORIES = "[\"JOB_POSTING\"]";

  @Test
  void createPending_savesJobWithPendingStatus() {
    when(collectionJobRepository.save(any(CollectionJob.class)))
        .thenAnswer(inv -> inv.getArgument(0));

    CollectionJob result =
        collectionJobService.createPending(
            TEST_DATE, TEST_CATEGORIES, CollectionTriggerType.MANUAL);

    assertThat(result.getStatus()).isEqualTo(CollectionJobStatus.PENDING);
    assertThat(result.getCollectionDate()).isEqualTo(TEST_DATE);
    assertThat(result.getTriggerType()).isEqualTo(CollectionTriggerType.MANUAL);
    assertThat(result.getCategories()).isEqualTo(TEST_CATEGORIES);
    assertThat(result.getRetryCount()).isZero();
    verify(collectionJobRepository).save(any(CollectionJob.class));
  }

  @Test
  void markProcessing_transitionsStatusAndSetsStartedAt() {
    CollectionJob job =
        CollectionJob.createPending(TEST_DATE, TEST_CATEGORIES, CollectionTriggerType.SCHEDULED);
    when(collectionJobRepository.findById(1L)).thenReturn(Optional.of(job));

    CollectionJob result = collectionJobService.markProcessing(1L);

    assertThat(result.getStatus()).isEqualTo(CollectionJobStatus.PROCESSING);
    assertThat(result.getStartedAt()).isNotNull();
  }

  @Test
  void markCompleted_transitionsStatusAndSetsCounts() {
    CollectionJob job =
        CollectionJob.createPending(TEST_DATE, TEST_CATEGORIES, CollectionTriggerType.SCHEDULED);
    when(collectionJobRepository.findById(1L)).thenReturn(Optional.of(job));

    CollectionJob result = collectionJobService.markCompleted(1L, 50, 40, 10);

    assertThat(result.getStatus()).isEqualTo(CollectionJobStatus.COMPLETED);
    assertThat(result.getCompletedAt()).isNotNull();
    assertThat(result.getCollectedCount()).isEqualTo(50);
    assertThat(result.getSavedCount()).isEqualTo(40);
    assertThat(result.getDeduplicatedCount()).isEqualTo(10);
  }

  @Test
  void markFailed_transitionsStatusAndRecordsError() {
    CollectionJob job =
        CollectionJob.createPending(TEST_DATE, TEST_CATEGORIES, CollectionTriggerType.MANUAL);
    when(collectionJobRepository.findById(1L)).thenReturn(Optional.of(job));

    CollectionJob result = collectionJobService.markFailed(1L, "agent timeout");

    assertThat(result.getStatus()).isEqualTo(CollectionJobStatus.FAILED);
    assertThat(result.getCompletedAt()).isNotNull();
    assertThat(result.getErrorMessage()).isEqualTo("agent timeout");
    // retryCount is only incremented in resetForRetry() / claimFailedForRetry(),
    // not on the initial failure.
    assertThat(result.getRetryCount()).isEqualTo(0);
  }

  @Test
  void markProcessing_unknownJobId_throwsCollectionJobNotFound() {
    when(collectionJobRepository.findById(99L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> collectionJobService.markProcessing(99L))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            e ->
                assertThat(((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.COLLECTION_JOB_NOT_FOUND));
  }
}
