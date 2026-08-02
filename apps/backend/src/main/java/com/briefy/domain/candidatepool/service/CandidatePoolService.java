package com.briefy.domain.candidatepool.service;

import com.briefy.domain.candidatepool.dto.CandidatePoolUpsertResult;
import com.briefy.domain.candidatepool.dto.CollectedJobPostingData;
import com.briefy.domain.candidatepool.entity.JobPosting;
import com.briefy.domain.candidatepool.entity.JobPostingSource;
import com.briefy.domain.candidatepool.repository.JobPostingRepository;
import com.briefy.domain.candidatepool.repository.JobPostingSourceRepository;
import com.briefy.global.util.UrlUtils;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CandidatePoolService {

  private final JobPostingRepository jobPostingRepository;
  private final JobPostingSourceRepository jobPostingSourceRepository;

  public CandidatePoolService(
      JobPostingRepository jobPostingRepository,
      JobPostingSourceRepository jobPostingSourceRepository) {
    this.jobPostingRepository = jobPostingRepository;
    this.jobPostingSourceRepository = jobPostingSourceRepository;
  }

  @Transactional
  public CandidatePoolUpsertResult upsertJobPostings(
      List<CollectedJobPostingData> postings, LocalDate collectedDate) {
    LocalDateTime now = LocalDateTime.now();
    int saved = 0;
    int duplicates = 0;

    for (CollectedJobPostingData data : postings) {
      // Prefer Agent-computed key; fall back to local computation.
      String sourceRecordKey =
          data.sourceRecordKey() != null
              ? data.sourceRecordKey()
              : buildSourceRecordKey(data.source(), data.sourceExternalId(), data.url());

      // Step 1: Exact source-record lookup — same source record was already collected.
      if (sourceRecordKey != null) {
        Optional<JobPostingSource> existingSource =
            jobPostingSourceRepository.findBySourceRecordKey(sourceRecordKey);
        if (existingSource.isPresent()) {
          existingSource.get().touch(now, collectedDate, data.contentHash());
          // Also enrich metadata on the parent posting in case it was null before.
          existingSource
              .get()
              .getJobPosting()
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
          duplicates++;
          continue;
        }
      }

      // Step 2: Canonical posting lookup — same content from a new source path.
      Optional<JobPosting> canonical = Optional.empty();
      if (data.canonicalFingerprint() != null) {
        canonical =
            jobPostingRepository.findFirstByCanonicalFingerprint(data.canonicalFingerprint());
      }
      if (canonical.isEmpty() && data.url() != null) {
        canonical = jobPostingRepository.findFirstByUrl(data.url());
      }

      if (canonical.isPresent()) {
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
      saved++;
    }

    return new CandidatePoolUpsertResult(postings.size(), saved, duplicates);
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

  // Delegates to UrlUtils so BriefingService and CandidatePoolService share
  // the same canonicalization contract for URL comparison and deduplication.
  private static String canonicalizeUrl(String url) {
    return UrlUtils.canonicalize(url);
  }
}
