package com.briefy.domain.collection.service;

import com.briefy.domain.collection.entity.CollectionJob;
import com.briefy.domain.collection.entity.CollectionJobStatus;
import com.briefy.domain.collection.entity.CollectionTriggerType;
import com.briefy.domain.collection.repository.CollectionJobRepository;
import com.briefy.global.exception.BusinessException;
import com.briefy.global.exception.ErrorCode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
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

  @PersistenceContext
  EntityManager entityManager;

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

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public CollectionJob markPartialSuccess(
      Long jobId, int collectedCount, int savedCount, int deduplicatedCount, String errorSummary) {
    CollectionJob job = findOrThrow(jobId);
    job.completePartial(collectedCount, savedCount, deduplicatedCount, errorSummary);
    return job;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean claimForRetry(Long jobId, int maxRetries) {
    int updated =
        collectionJobRepository.claimFailedForRetry(jobId, LocalDateTime.now(), maxRetries);
    return updated == 1;
  }

  @Transactional(readOnly = true)
  public boolean isAlreadyActiveForDate(LocalDate date) {
    return collectionJobRepository.existsByCollectionDateAndStatusIn(
        date, List.of(CollectionJobStatus.PROCESSING, CollectionJobStatus.COMPLETED));
  }

  /**
   * 날짜 기준 멱등적 CollectionJob 생성.
   *
   * <p>find-first → create-if-absent 패턴:
   *
   * <ol>
   *   <li>먼저 SELECT로 존재 여부 확인 — 이미 있으면 INSERT 없이 즉시 반환 (순차 재시도 정상 처리)
   *   <li>없으면 save() 시도 (최초 호출)
   *   <li>동시 race condition으로 DIVE 발생 시 entityManager.clear()로 dirty 세션 초기화 후 재조회
   * </ol>
   *
   * <p>배경: GenerationType.IDENTITY는 persist() 시점에 INSERT를 즉시 실행한다. 기존 try-save/catch-DIVE
   * 패턴에서는 INSERT 실패 후 엔티티가 L1 캐시에 dirty 상태로 남아, catch 블록의 findByCollectionDate
   * 호출이 FlushMode.AUTO 에 의해 다시 flush를 시도해 두 번째 UNIQUE 위반이 발생했다.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public CollectionJob createOrGetForDate(
      LocalDate date, String categoriesJson, CollectionTriggerType triggerType) {
    // ① 먼저 조회 — 순차 재시도 시 INSERT 시도 없이 즉시 반환
    Optional<CollectionJob> existing = collectionJobRepository.findByCollectionDate(date);
    if (existing.isPresent()) {
      return existing.get();
    }
    // ② 없으면 생성 시도
    CollectionJob job = CollectionJob.createPending(date, categoriesJson, triggerType);
    try {
      return collectionJobRepository.save(job);
    } catch (DataIntegrityViolationException ex) {
      // ③ 동시 race condition: dirty 세션 초기화 후 재조회
      entityManager.clear();
      return collectionJobRepository.findByCollectionDate(date).orElseThrow(() -> ex);
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

  public CollectionJob findOrThrow(Long jobId) {
    return collectionJobRepository
        .findById(jobId)
        .orElseThrow(() -> new BusinessException(ErrorCode.COLLECTION_JOB_NOT_FOUND));
  }
}
