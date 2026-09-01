package com.briefy.domain.candidatepool.service;

import com.briefy.domain.candidatepool.dto.CandidatePoolUpsertResult;
import com.briefy.domain.candidatepool.dto.CollectedJobPostingData;
import com.briefy.domain.candidatepool.entity.JobPosting;
import com.briefy.domain.candidatepool.entity.JobPostingAnalysis;
import com.briefy.domain.candidatepool.entity.JobPostingSource;
import com.briefy.domain.candidatepool.repository.JobPostingAnalysisRepository;
import com.briefy.domain.candidatepool.repository.JobPostingRepository;
import com.briefy.domain.candidatepool.repository.JobPostingSourceRepository;
import com.briefy.global.util.UrlUtils;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CandidatePoolService {

  private static final Logger log = LoggerFactory.getLogger(CandidatePoolService.class);

  private final JobPostingRepository jobPostingRepository;
  private final JobPostingSourceRepository jobPostingSourceRepository;
  private final JobPostingAnalysisRepository jobPostingAnalysisRepository;
  private final String classifierVersion;

  public CandidatePoolService(
      JobPostingRepository jobPostingRepository,
      JobPostingSourceRepository jobPostingSourceRepository,
      JobPostingAnalysisRepository jobPostingAnalysisRepository,
      @Value("${briefy.classifier.version:1.0.0}") String classifierVersion) {
    this.jobPostingRepository = jobPostingRepository;
    this.jobPostingSourceRepository = jobPostingSourceRepository;
    this.jobPostingAnalysisRepository = jobPostingAnalysisRepository;
    this.classifierVersion = classifierVersion;
  }

  @Transactional
  public CandidatePoolUpsertResult upsertJobPostings(
      List<CollectedJobPostingData> postings, LocalDate collectedDate) {
    LocalDateTime now = LocalDateTime.now();
    int saved = 0;
    int duplicates = 0;

    List<Long> newIds = new ArrayList<>();
    List<Long> updatedIds = new ArrayList<>();
    Set<Long> touchedIdSet = new LinkedHashSet<>();

    for (CollectedJobPostingData data : postings) {
      String sourceRecordKey =
          data.sourceRecordKey() != null
              ? data.sourceRecordKey()
              : buildSourceRecordKey(data.source(), data.sourceExternalId(), data.url());

      // Step 1: Exact source-record lookup — same source, possibly changed content.
      if (sourceRecordKey != null) {
        Optional<JobPostingSource> existingSource =
            jobPostingSourceRepository.findBySourceRecordKey(sourceRecordKey);
        if (existingSource.isPresent()) {
          JobPosting posting = existingSource.get().getJobPosting();

          // 동일 소스에서 contentHash가 변경되었으면 same-source 갱신 (모든 non-null 필드 덮어쓰기).
          boolean contentHashChanged =
              existingSource.get().getSourceContentHash() != null
                  && !existingSource.get().getSourceContentHash().equals(data.contentHash());

          if (contentHashChanged) {
            posting.updateFromSameSource(
                data.deadline(),
                data.description(),
                data.contentHash(),
                collectedDate,
                data.roles(),
                data.skills(),
                data.experienceLevel(),
                data.employmentType(),
                data.location(),
                data.publishedAt());
          } else {
            // contentHash 동일: null-preserve 보강만 수행
            posting.refreshFrom(
                data.deadline(),
                data.description(),
                data.contentHash(),
                collectedDate,
                data.roles(),
                data.skills(),
                data.experienceLevel(),
                data.employmentType(),
                data.location(),
                data.publishedAt());
          }
          existingSource.get().touch(now, collectedDate, data.contentHash());

          handleAnalysis(posting, data.descriptionTruncated(), touchedIdSet, updatedIds);
          duplicates++;
          continue;
        }
      }

      // Step 2: Canonical posting lookup — same content, new source path.
      Optional<JobPosting> canonical = Optional.empty();
      if (data.canonicalFingerprint() != null) {
        canonical =
            jobPostingRepository.findFirstByCanonicalFingerprint(data.canonicalFingerprint());
      }
      if (canonical.isEmpty() && data.url() != null) {
        canonical = jobPostingRepository.findFirstByUrl(data.url());
      }

      if (canonical.isPresent()) {
        // 교차 소스: null-preserve 보강만 수행 (기존 유효 값 보존)
        canonical
            .get()
            .refreshFrom(
                data.deadline(),
                data.description(),
                data.contentHash(),
                collectedDate,
                data.roles(),
                data.skills(),
                data.experienceLevel(),
                data.employmentType(),
                data.location(),
                data.publishedAt());
        if (sourceRecordKey != null) {
          jobPostingSourceRepository.save(
              JobPostingSource.create(
                  canonical.get(),
                  data.source(),
                  null,
                  data.url(),
                  sourceRecordKey,
                  data.contentHash(),
                  now,
                  collectedDate));
        }
        handleAnalysis(canonical.get(), data.descriptionTruncated(), touchedIdSet, updatedIds);
        duplicates++;
        continue;
      }

      // Step 3: New canonical posting.
      JobPosting posting =
          jobPostingRepository.save(
              JobPosting.create(
                  data.title(),
                  data.company(),
                  data.source(),
                  data.url(),
                  data.location(),
                  data.deadline(),
                  data.description(),
                  data.roles(),
                  data.skills(),
                  data.employmentType(),
                  data.experienceLevel(),
                  data.contentHash(),
                  collectedDate,
                  data.publishedAt()));
      if (sourceRecordKey != null) {
        jobPostingSourceRepository.save(
            JobPostingSource.create(
                posting,
                data.source(),
                null,
                data.url(),
                sourceRecordKey,
                data.contentHash(),
                now,
                collectedDate));
      }

      String analysisHash =
          AnalysisInputHashCalculator.compute(posting, data.descriptionTruncated());
      jobPostingAnalysisRepository.save(
          JobPostingAnalysis.pending(posting.getId(), analysisHash, classifierVersion));
      newIds.add(posting.getId());
      touchedIdSet.add(posting.getId());
      saved++;
    }

    List<Long> touchedIds = new ArrayList<>(touchedIdSet);
    return new CandidatePoolUpsertResult(
        postings.size(), saved, duplicates, newIds, updatedIds, touchedIds);
  }

  /**
   * 분석 행을 확인하고 필요하면 PENDING으로 등록 또는 재설정한다.
   *
   * <ul>
   *   <li>분석 행 없음 → PENDING 생성
   *   <li>hash 또는 version 변경 → 이전 claim 무효화 후 PENDING 재설정 → updatedIds 기록
   *   <li>동일 hash + version → 재분류 불필요, touchedIds에만 기록
   * </ul>
   */
  private void handleAnalysis(
      JobPosting posting,
      Boolean descriptionTruncated,
      Set<Long> touchedIdSet,
      List<Long> updatedIds) {
    Long postingId = posting.getId();
    String newHash = AnalysisInputHashCalculator.compute(posting, descriptionTruncated);

    Optional<JobPostingAnalysis> existing =
        jobPostingAnalysisRepository.findByJobPostingId(postingId);

    if (existing.isEmpty()) {
      jobPostingAnalysisRepository.save(
          JobPostingAnalysis.pending(postingId, newHash, classifierVersion));
      touchedIdSet.add(postingId);
      return;
    }

    JobPostingAnalysis analysis = existing.get();
    boolean hashChanged = !newHash.equals(analysis.getAnalysisInputHash());
    boolean versionChanged = !classifierVersion.equals(analysis.getClassifierVersion());

    if (hashChanged || versionChanged) {
      if (hashChanged) {
        log.debug("candidatepool: analysisInputHash changed for posting={}, re-queuing", postingId);
      }
      analysis.resetForNewInput(newHash, classifierVersion);
      updatedIds.add(postingId);
    }
    touchedIdSet.add(postingId);
  }

  private static final List<String> FIXTURE_SOURCES = List.of("fixture");

  /**
   * Returns all job postings eligible for a daily briefing candidate pool on the given date.
   *
   * <p>A posting qualifies when it has at least one active, non-fixture source record AND its
   * deadline has not yet passed. Collection recency is NOT a criterion — an old posting that is
   * still active and un-expired remains in the pool.
   */
  @Transactional(readOnly = true)
  public List<JobPosting> findEligibleJobPostingsForBriefing(LocalDate referenceDate) {
    return jobPostingRepository.findEligibleJobPostingsForBriefing(referenceDate, FIXTURE_SOURCES);
  }

  // SHA-256 hex of "source|identifier".
  // identifier = sourceExternalId when non-blank, else canonicalize(url).
  // Returns null when both sourceExternalId and url are null (no source record created).
  static String buildSourceRecordKey(String source, String sourceExternalId, String url) {
    String identifier;
    if (sourceExternalId != null && !sourceExternalId.isBlank()) {
      identifier = sourceExternalId;
    } else if (url != null) {
      identifier = canonicalizeUrl(url);
    } else {
      return null;
    }
    String raw = (source == null ? "" : source) + "|" + identifier;
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] hash = md.digest(raw.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder(64);
      for (byte b : hash) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }

  private static String canonicalizeUrl(String url) {
    return UrlUtils.canonicalize(url);
  }
}
