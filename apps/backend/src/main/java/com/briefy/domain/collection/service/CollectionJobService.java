package com.briefy.domain.collection.service;

import com.briefy.domain.collection.entity.CollectionJob;
import com.briefy.domain.collection.entity.CollectionJobStatus;
import com.briefy.domain.collection.entity.CollectionTriggerType;
import com.briefy.domain.collection.repository.CollectionJobRepository;
import com.briefy.global.exception.BusinessException;
import com.briefy.global.exception.ErrorCode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class CollectionJobService {

  private static final Logger log = LoggerFactory.getLogger(CollectionJobService.class);

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

  /**
   * 날짜 기준 멱등적 CollectionJob 생성.
   *
   * <ul>
   *   <li>없으면 PENDING으로 생성
   *   <li>UNIQUE 충돌 시 기존 job 재조회
   * </ul>
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public CollectionJob createOrGetForDate(
      LocalDate date, String categoriesJson, CollectionTriggerType triggerType) {
    try {
      CollectionJob job = CollectionJob.createPending(date, categoriesJson, triggerType);
      return collectionJobRepository.save(job);
    } catch (DataIntegrityViolationException ex) {
      // UNIQUE 충돌: 동시 INSERT 경합. 기존 job 재조회
      return collectionJobRepository
          .findByCollectionDate(date)
          .orElseThrow(() -> ex); // 조회도 실패하면 원래 예외 전파
    }
  }

  /**
   * 조건부 선점: PENDING → PROCESSING (1건만 성공)
   *
   * @return true if claimed
   */
  @Transactional
  public boolean claimForProcessing(Long jobId) {
    int updated = collectionJobRepository.claimPending(jobId, LocalDateTime.now());
    if (updated == 1) {
      log.debug("CollectionJob {} claimed for processing", jobId);
      return true;
    }
    return false;
  }

  private CollectionJob findOrThrow(Long jobId) {
    return collectionJobRepository
        .findById(jobId)
        .orElseThrow(() -> new BusinessException(ErrorCode.COLLECTION_JOB_NOT_FOUND));
  }
}
