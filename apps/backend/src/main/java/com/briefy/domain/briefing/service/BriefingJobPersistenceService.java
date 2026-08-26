package com.briefy.domain.briefing.service;

import com.briefy.domain.briefing.entity.BriefingJob;
import com.briefy.domain.briefing.entity.BriefingReport;
import com.briefy.domain.briefing.repository.BriefingJobRepository;
import com.briefy.domain.briefing.repository.BriefingReportRepository;
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

  public BriefingJobPersistenceService(
      BriefingJobRepository briefingJobRepository,
      BriefingReportRepository briefingReportRepository) {
    this.briefingJobRepository = briefingJobRepository;
    this.briefingReportRepository = briefingReportRepository;
  }

  /** 멱등적 BriefingJob 생성. UNIQUE(user_id, briefing_date) 충돌 시 기존 job 재조회. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public BriefingJob createOrGet(BriefingJob newJob) {
    try {
      return briefingJobRepository.save(newJob);
    } catch (DataIntegrityViolationException ex) {
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

  /** Report 저장 및 Job 완료 처리. 하나의 짧은 트랜잭션. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public BriefingReport saveReportAndComplete(Long jobId, BriefingReport report) {
    BriefingReport saved = briefingReportRepository.save(report);
    briefingJobRepository.findById(jobId).ifPresent(BriefingJob::complete);
    return saved;
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
}
