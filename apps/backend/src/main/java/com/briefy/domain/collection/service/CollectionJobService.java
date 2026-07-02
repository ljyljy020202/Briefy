package com.briefy.domain.collection.service;

import com.briefy.domain.collection.entity.CollectionJob;
import com.briefy.domain.collection.entity.CollectionJobStatus;
import com.briefy.domain.collection.entity.CollectionTriggerType;
import com.briefy.domain.collection.repository.CollectionJobRepository;
import com.briefy.global.exception.BusinessException;
import com.briefy.global.exception.ErrorCode;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CollectionJobService {

  private final CollectionJobRepository collectionJobRepository;

  public CollectionJobService(CollectionJobRepository collectionJobRepository) {
    this.collectionJobRepository = collectionJobRepository;
  }

  public CollectionJob createPending(
      LocalDate collectionDate, String categories, CollectionTriggerType triggerType) {
    CollectionJob job = CollectionJob.createPending(collectionDate, categories, triggerType);
    return collectionJobRepository.save(job);
  }

  public CollectionJob markProcessing(Long jobId) {
    CollectionJob job = findOrThrow(jobId);
    job.startProcessing();
    return job;
  }

  public CollectionJob markCompleted(
      Long jobId, int collectedCount, int savedCount, int deduplicatedCount) {
    CollectionJob job = findOrThrow(jobId);
    job.complete(collectedCount, savedCount, deduplicatedCount);
    return job;
  }

  public CollectionJob markFailed(Long jobId, String errorMessage) {
    CollectionJob job = findOrThrow(jobId);
    job.fail(errorMessage);
    return job;
  }

  @Transactional(readOnly = true)
  public boolean isAlreadyActiveForDate(LocalDate date) {
    return collectionJobRepository.existsByCollectionDateAndStatusIn(
        date, List.of(CollectionJobStatus.PROCESSING, CollectionJobStatus.COMPLETED));
  }

  private CollectionJob findOrThrow(Long jobId) {
    return collectionJobRepository
        .findById(jobId)
        .orElseThrow(() -> new BusinessException(ErrorCode.COLLECTION_JOB_NOT_FOUND));
  }
}
