package com.briefy.domain.candidatepool.service;

import com.briefy.config.ClassificationProperties;
import com.briefy.domain.candidatepool.dto.AnalysisStatsResponse;
import com.briefy.domain.candidatepool.dto.BackfillRequest;
import com.briefy.domain.candidatepool.dto.BackfillResponse;
import com.briefy.domain.candidatepool.entity.JobPosting;
import com.briefy.domain.candidatepool.entity.JobPostingAnalysis;
import com.briefy.domain.candidatepool.entity.analysis.ClassificationStatus;
import com.briefy.domain.candidatepool.repository.JobPostingAnalysisRepository;
import com.briefy.domain.candidatepool.repository.JobPostingRepository;
import com.briefy.global.exception.BusinessException;
import com.briefy.global.exception.ErrorCode;
import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 관리자 분류 backfill 및 통계 조회 서비스. */
@Service
public class AnalysisBackfillService {

  private static final Logger log = LoggerFactory.getLogger(AnalysisBackfillService.class);

  private static final int MAX_LIMIT = 1000;
  private static final List<String> EXCLUDED_SOURCES = List.of("fixture");

  private final JobPostingRepository postingRepository;
  private final JobPostingAnalysisRepository analysisRepository;
  private final ClassificationProperties properties;

  public AnalysisBackfillService(
      JobPostingRepository postingRepository,
      JobPostingAnalysisRepository analysisRepository,
      ClassificationProperties properties) {
    this.postingRepository = postingRepository;
    this.analysisRepository = analysisRepository;
    this.properties = properties;
  }

  /**
   * backfill 대상을 선정하여 PENDING으로 등록한다 (dryRun=true면 등록 없이 건수만 반환).
   *
   * @throws BusinessException limit 범위 초과 시 {@link
   *     ErrorCode#CLASSIFICATION_BACKFILL_LIMIT_EXCEEDED}
   */
  @Transactional
  public BackfillResponse backfill(BackfillRequest request) {
    int limit = request.limit() <= 0 ? MAX_LIMIT : Math.min(request.limit(), MAX_LIMIT);
    if (request.limit() > MAX_LIMIT) {
      throw new BusinessException(
          ErrorCode.CLASSIFICATION_BACKFILL_LIMIT_EXCEEDED,
          "backfill limit은 1~1000 사이여야 합니다. 요청: " + request.limit());
    }

    String version =
        (request.classifierVersion() != null && !request.classifierVersion().isBlank())
            ? request.classifierVersion()
            : properties.classifierVersion();

    List<JobPosting> candidates = selectCandidates(request, limit);
    if (candidates.isEmpty()) {
      return new BackfillResponse(request.dryRun(), 0, 0, 0, Collections.emptyMap());
    }

    List<Long> candidateIds = candidates.stream().map(JobPosting::getId).toList();

    // 각 공고의 기존 분석 행 조회
    Map<Long, JobPostingAnalysis> existingMap =
        analysisRepository.findAllByJobPostingIdIn(candidateIds).stream()
            .collect(Collectors.toMap(JobPostingAnalysis::getJobPostingId, a -> a));

    Map<String, Integer> reasons = new LinkedHashMap<>();
    int enqueued = 0;
    int skipped = 0;

    for (JobPosting posting : candidates) {
      String hash = AnalysisInputHashCalculator.compute(posting, null);
      JobPostingAnalysis existing = existingMap.get(posting.getId());

      if (existing == null) {
        // 분석 행 없음 → PENDING 생성
        if (!request.dryRun()) {
          analysisRepository.save(JobPostingAnalysis.pending(posting.getId(), hash, version));
        }
        reasons.merge("NO_ANALYSIS_ROW", 1, Integer::sum);
        enqueued++;
      } else if (isCompleted(existing) && !request.forceReclassify()) {
        // 동일 hash+version 완료 → skip
        if (existing.getAnalysisInputHash().equals(hash)
            && existing.getClassifierVersion().equals(version)) {
          reasons.merge("ALREADY_COMPLETED_SAME_VERSION", 1, Integer::sum);
          skipped++;
        } else {
          // 버전 또는 입력 변경 → 재분류
          if (!request.dryRun()) {
            existing.resetForNewInput(hash, version);
          }
          reasons.merge("VERSION_OR_INPUT_CHANGED", 1, Integer::sum);
          enqueued++;
        }
      } else if (isCompleted(existing) && request.forceReclassify()) {
        // forceReclassify → 강제 재분류
        if (!request.dryRun()) {
          existing.resetForNewInput(hash, version);
        }
        reasons.merge("FORCE_RECLASSIFY", 1, Integer::sum);
        enqueued++;
      } else {
        // PENDING/PROCESSING/FAILED 상태 → 이미 처리 대기 중
        reasons.merge("ALREADY_PENDING_OR_PROCESSING", 1, Integer::sum);
        skipped++;
      }
    }

    log.info(
        "Backfill complete dryRun={} target={} enqueued={} skipped={} reasons={}",
        request.dryRun(),
        candidates.size(),
        enqueued,
        skipped,
        reasons);

    return new BackfillResponse(request.dryRun(), candidates.size(), enqueued, skipped, reasons);
  }

  /** 분류 분석 상태 집계를 반환한다. */
  @Transactional(readOnly = true)
  public AnalysisStatsResponse stats() {
    List<Object[]> rows = analysisRepository.countByStatus();

    Map<String, Long> byStatus = new HashMap<>();
    for (Object[] row : rows) {
      ClassificationStatus status = (ClassificationStatus) row[0];
      Long count = (Long) row[1];
      byStatus.put(status.name(), count);
    }

    long total = byStatus.values().stream().mapToLong(Long::longValue).sum();

    return new AnalysisStatsResponse(
        total,
        byStatus,
        byStatus.getOrDefault("PENDING", 0L),
        byStatus.getOrDefault("PROCESSING", 0L),
        byStatus.getOrDefault("SUCCEEDED", 0L),
        byStatus.getOrDefault("FALLBACK", 0L),
        byStatus.getOrDefault("FAILED", 0L),
        byStatus.getOrDefault("CONFLICT", 0L));
  }

  private List<JobPosting> selectCandidates(BackfillRequest request, int limit) {
    List<JobPosting> postings;

    if (request.postingIds() != null && !request.postingIds().isEmpty()) {
      postings = postingRepository.findAllById(request.postingIds());
    } else if (request.fromDate() != null || request.toDate() != null) {
      LocalDate from = request.fromDate() != null ? request.fromDate() : LocalDate.of(2000, 1, 1);
      LocalDate to = request.toDate() != null ? request.toDate() : LocalDate.now();
      postings = postingRepository.findAllByCollectedDateBetween(from, to);
    } else {
      postings =
          postingRepository.findEligibleJobPostingsForBriefing(LocalDate.now(), EXCLUDED_SOURCES);
    }

    // afterId 커서 기반 필터
    if (request.afterId() != null) {
      postings =
          postings.stream().filter(p -> p.getId() > request.afterId()).collect(Collectors.toList());
    }

    // ID 오름차순 정렬 후 limit 적용
    return postings.stream()
        .sorted((a, b) -> Long.compare(a.getId(), b.getId()))
        .limit(limit)
        .collect(Collectors.toList());
  }

  private boolean isCompleted(JobPostingAnalysis analysis) {
    ClassificationStatus s = analysis.getClassificationStatus();
    return s == ClassificationStatus.SUCCEEDED || s == ClassificationStatus.FALLBACK;
  }
}
