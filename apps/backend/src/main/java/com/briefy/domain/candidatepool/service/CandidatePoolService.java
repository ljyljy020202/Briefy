package com.briefy.domain.candidatepool.service;

import com.briefy.domain.candidatepool.dto.CandidatePoolUpsertResult;
import com.briefy.domain.candidatepool.dto.CollectedJobPostingData;
import com.briefy.domain.candidatepool.entity.JobPosting;
import com.briefy.domain.candidatepool.entity.JobPostingSource;
import com.briefy.domain.candidatepool.repository.JobPostingRepository;
import com.briefy.domain.candidatepool.repository.JobPostingSourceRepository;
import java.net.URI;
import java.net.URISyntaxException;
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
            .refreshFrom(data.deadline(), data.description(), data.contentHash(), collectedDate);
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

  private static final int BRIEFING_LOOKBACK_DAYS = 7;
  private static final List<String> TEST_SOURCES = List.of("fixture");

  @Transactional(readOnly = true)
  public List<JobPosting> findJobPostingsByDate(LocalDate date) {
    return jobPostingRepository.findAllByCollectedDateBetweenExcludingSources(
        date.minusDays(BRIEFING_LOOKBACK_DAYS), date, TEST_SOURCES);
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

  // Normalizes URL to lowercase scheme+host+path, strips query/fragment and trailing slashes
  // from non-root paths. Matches Agent's canonicalize_url() contract.
  private static String canonicalizeUrl(String url) {
    try {
      URI uri = new URI(url);
      String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase() : "";
      String host = uri.getHost() != null ? uri.getHost().toLowerCase() : "";
      String path = uri.getPath() != null ? uri.getPath() : "";
      if (!path.equals("/") && path.endsWith("/")) {
        path = path.replaceAll("/+$", "");
      }
      return scheme + "://" + host + path;
    } catch (URISyntaxException e) {
      return url.toLowerCase();
    }
  }
}
