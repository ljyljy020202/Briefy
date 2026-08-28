package com.briefy.domain.briefing.service;

import com.briefy.domain.briefing.entity.BriefingJob;
import com.briefy.domain.briefing.entity.BriefingReport;
import com.briefy.domain.briefing.repository.BriefingJobRepository;
import com.briefy.domain.briefing.repository.BriefingReportRepository;
import com.briefy.global.exception.BusinessException;
import com.briefy.global.exception.ErrorCode;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BriefingJobPersistenceService {

  private static final Logger log = LoggerFactory.getLogger(BriefingJobPersistenceService.class);

  private final BriefingJobRepository briefingJobRepository;
  private final BriefingReportRepository briefingReportRepository;
  private final EntityManager entityManager;

  public BriefingJobPersistenceService(
      BriefingJobRepository briefingJobRepository,
      BriefingReportRepository briefingReportRepository,
      EntityManager entityManager) {
    this.briefingJobRepository = briefingJobRepository;
    this.briefingReportRepository = briefingReportRepository;
    this.entityManager = entityManager;
  }

  /** 멱등적 BriefingJob 생성. UNIQUE(user_id, briefing_date) 충돌 시 기존 job 재조회. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public BriefingJob createOrGet(BriefingJob newJob) {
    try {
      return briefingJobRepository.save(newJob);
    } catch (DataIntegrityViolationException ex) {
      // 중복 키 예외 후 Hibernate 세션이 오염되므로(HHH000099) 1차 캐시를 비우고 재조회한다.
      entityManager.clear();
      return briefingJobRepository
          .findByUserIdAndBriefingDate(newJob.getUserId(), newJob.getBriefingDate())
          .orElseThrow(() -> ex);
    }
  }

  /**
   * 조건부 선점: PENDING → PROCESSING.
   *
   * @return true if this call claimed the job
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean claimForProcessing(Long jobId) {
    int updated = briefingJobRepository.claimPending(jobId, LocalDateTime.now());
    return updated == 1;
  }

  /**
   * 조건부 선점: FAILED → PROCESSING (retry용).
   *
   * @return true if this call successfully claimed the failed job for retry
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean claimForRetry(Long jobId, int maxRetries) {
    int updated = briefingJobRepository.claimFailedForRetry(jobId, LocalDateTime.now(), maxRetries);
    return updated == 1;
  }

  /** Report 저장 및 Job 완료 처리 (generationMode/fallbackReason 포함). 하나의 짧은 트랜잭션. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public BriefingReport saveReportAndComplete(
      Long jobId, BriefingReport report, String generationMode, String fallbackReason) {
    BriefingReport saved = briefingReportRepository.save(report);
    briefingJobRepository
        .findById(jobId)
        .ifPresent(job -> job.completeWithMode(generationMode, fallbackReason));
    return saved;
  }

  /**
   * Report 저장 및 Job 완료 처리 (하위 호환용 — generationMode 없이 호출하는 기존 코드용).
   *
   * @deprecated saveReportAndComplete(Long, BriefingReport, String, String)을 사용하세요.
   */
  @Deprecated
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public BriefingReport saveReportAndComplete(Long jobId, BriefingReport report) {
    return saveReportAndComplete(jobId, report, "LLM", null);
  }

  /** 실패 상태 기록. 원래 트랜잭션 실패와 무관하게 커밋. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordFailure(Long jobId, String errorMessage) {
    briefingJobRepository.findById(jobId).ifPresent(job -> job.fail(errorMessage));
    log.warn("BriefingJob {} marked as FAILED: {}", jobId, errorMessage);
  }

  /** 오늘 날짜 BriefingReport 조회 (중복 체크용). */
  @Transactional(readOnly = true)
  public Optional<BriefingReport> findTodayReport(Long userId, LocalDate date) {
    return briefingReportRepository.findByUserIdAndReportDate(userId, date);
  }

  /** Job 단건 조회. 존재하지 않으면 BRIEFING_JOB_NOT_FOUND 예외. */
  @Transactional(readOnly = true)
  public BriefingJob findJobById(Long jobId) {
    return briefingJobRepository
        .findById(jobId)
        .orElseThrow(() -> new BusinessException(ErrorCode.BRIEFING_JOB_NOT_FOUND));
  }
}
