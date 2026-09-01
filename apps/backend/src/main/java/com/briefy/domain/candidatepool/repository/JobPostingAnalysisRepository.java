package com.briefy.domain.candidatepool.repository;

import com.briefy.domain.candidatepool.entity.JobPostingAnalysis;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JobPostingAnalysisRepository extends JpaRepository<JobPostingAnalysis, Long> {

  /** job_posting_id로 분석 행을 조회한다. */
  Optional<JobPostingAnalysis> findByJobPostingId(Long jobPostingId);

  /**
   * 추천 후보 ID 목록에 대한 분석을 일괄 조회한다.
   *
   * <p>후보별 lazy loading에 의한 N+1을 방지하기 위해 IN 쿼리를 사용한다. 분석 행이 없는 공고 ID는 결과에 포함되지 않는다.
   */
  @Query("SELECT a FROM JobPostingAnalysis a WHERE a.jobPostingId IN :jobPostingIds")
  List<JobPostingAnalysis> findAllByJobPostingIdIn(
      @Param("jobPostingIds") Collection<Long> jobPostingIds);

  /**
   * 주어진 ID 중 이미 분류 완료(SUCCEEDED 또는 FALLBACK)된 공고의 ID를 반환한다.
   *
   * <p>동일 {@code analysisInputHash} + {@code classifierVersion} 조합이면 재분류를 건너뛴다.
   */
  @Query(
      "SELECT a.jobPostingId FROM JobPostingAnalysis a"
          + " WHERE a.jobPostingId IN :jobPostingIds"
          + "   AND a.analysisInputHash = :analysisInputHash"
          + "   AND a.classifierVersion = :classifierVersion"
          + "   AND a.classificationStatus IN ('SUCCEEDED', 'FALLBACK')")
  List<Long> findAlreadyClassifiedIds(
      @Param("jobPostingIds") Collection<Long> jobPostingIds,
      @Param("analysisInputHash") String analysisInputHash,
      @Param("classifierVersion") String classifierVersion);

  /**
   * 재시도 가능한 PENDING 또는 FAILED 상태 분석을 조회한다.
   *
   * <p>PENDING 행과 {@code nextRetryAt}이 현재 시각 이전인 FAILED 행을 반환한다.
   */
  @Query(
      "SELECT a FROM JobPostingAnalysis a"
          + " WHERE a.classificationStatus = 'PENDING'"
          + "    OR (a.classificationStatus = 'FAILED' AND a.nextRetryAt <= :now)"
          + " ORDER BY a.createdAt ASC")
  List<JobPostingAnalysis> findRetryable(@Param("now") LocalDateTime now);

  /**
   * 만료된 PROCESSING 상태 분석을 조회한다 (클레임 만료).
   *
   * <p>작업자가 leaseUntil 이전에 완료하지 못한 행을 재클레임하기 위해 사용한다.
   */
  @Query(
      "SELECT a FROM JobPostingAnalysis a"
          + " WHERE a.classificationStatus = 'PROCESSING'"
          + "   AND a.leaseUntil < :now")
  List<JobPostingAnalysis> findExpiredLeases(@Param("now") LocalDateTime now);

  /** 주어진 job_posting_id 집합에서 분석 행이 존재하는 ID 목록을 반환한다. */
  @Query("SELECT a.jobPostingId FROM JobPostingAnalysis a WHERE a.jobPostingId IN :jobPostingIds")
  List<Long> findExistingJobPostingIds(@Param("jobPostingIds") Collection<Long> jobPostingIds);

  /**
   * 재시도 가능한 항목을 페이지 단위로 조회한다.
   *
   * <p>시도 횟수({@code attemptCount})가 {@code maxAttempts} 미만인 항목만 반환하여 무한 재시도를 방지한다. 전체 DB를 로딩하지 않도록
   * {@code Pageable}로 상한을 강제한다.
   */
  @Query(
      "SELECT a FROM JobPostingAnalysis a"
          + " WHERE (a.classificationStatus = 'PENDING'"
          + "    OR (a.classificationStatus = 'FAILED' AND a.nextRetryAt <= :now))"
          + "   AND a.attemptCount < :maxAttempts"
          + " ORDER BY a.createdAt ASC")
  List<JobPostingAnalysis> findRetryableWithLimit(
      @Param("now") LocalDateTime now, @Param("maxAttempts") int maxAttempts, Pageable pageable);

  /**
   * 만료된 PROCESSING 행을 PENDING으로 일괄 복구한다.
   *
   * <p>작업자 프로세스 중단 또는 예산 초과로 클레임이 해제되지 않은 항목을 다음 실행에서 재처리할 수 있도록 한다. 기존 {@code
   * analysisInputHash}·{@code classifierVersion}은 변경하지 않는다.
   *
   * @return 복구된 행 수
   */
  @Modifying
  @Query(
      "UPDATE JobPostingAnalysis a"
          + " SET a.classificationStatus = com.briefy.domain.candidatepool.entity.analysis.ClassificationStatus.PENDING,"
          + "     a.claimToken = null,"
          + "     a.leaseUntil = null"
          + " WHERE a.classificationStatus = com.briefy.domain.candidatepool.entity.analysis.ClassificationStatus.PROCESSING"
          + "   AND a.leaseUntil < :now")
  int resetExpiredLeases(@Param("now") LocalDateTime now);

  /**
   * 상태별 분석 행 수를 집계한다.
   *
   * @return {@code [ClassificationStatus, count]} 쌍의 목록
   */
  @Query(
      "SELECT a.classificationStatus, COUNT(a)"
          + " FROM JobPostingAnalysis a"
          + " GROUP BY a.classificationStatus")
  List<Object[]> countByStatus();

  /**
   * 분석 행이 없는 공고를 backfill하기 위해, 주어진 ID 범위에서 분석 행이 없는 공고 ID를 반환한다.
   *
   * <p>backfill 대상 선택에서 이미 분석 행이 있는 공고를 제외할 때 사용한다.
   */
  @Query(
      "SELECT a.jobPostingId FROM JobPostingAnalysis a"
          + " WHERE a.jobPostingId IN :jobPostingIds"
          + "   AND a.classificationStatus IN ('SUCCEEDED', 'FALLBACK')"
          + "   AND a.analysisInputHash = :analysisInputHash"
          + "   AND a.classifierVersion = :classifierVersion")
  List<Long> findCompletedSameInputIds(
      @Param("jobPostingIds") Collection<Long> jobPostingIds,
      @Param("analysisInputHash") String analysisInputHash,
      @Param("classifierVersion") String classifierVersion);
}
