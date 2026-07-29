package com.briefy.domain.candidatepool.repository;

import com.briefy.domain.candidatepool.entity.JobPosting;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JobPostingRepository extends JpaRepository<JobPosting, Long> {

  Optional<JobPosting> findFirstByUrl(String url);

  Optional<JobPosting> findFirstByCanonicalFingerprint(String canonicalFingerprint);

  List<JobPosting> findAllByCollectedDate(LocalDate collectedDate);

  List<JobPosting> findAllByCollectedDateBetween(LocalDate from, LocalDate to);

  /**
   * Returns all job postings eligible for a daily briefing candidate pool.
   *
   * <p>Eligibility criteria:
   *
   * <ul>
   *   <li>Has at least one active {@link com.briefy.domain.candidatepool.entity.JobPostingSource}
   *   <li>That active source's {@code source} is NOT in {@code excludedSources} (e.g. "fixture")
   *   <li>The posting's deadline is in the future or unknown (null)
   * </ul>
   *
   * <p>Collection recency ({@code collected_date}) is intentionally NOT a filter — a posting
   * collected long ago but still active and un-expired remains in the candidate pool.
   */
  @Query(
      "SELECT DISTINCT j FROM JobPosting j, JobPostingSource s"
          + " WHERE s.jobPosting = j"
          + "   AND s.active = true"
          + "   AND (s.source IS NULL OR s.source NOT IN :excludedSources)"
          + "   AND (j.deadline IS NULL OR j.deadline >= :referenceDate)")
  List<JobPosting> findEligibleJobPostingsForBriefing(
      @Param("referenceDate") LocalDate referenceDate,
      @Param("excludedSources") Collection<String> excludedSources);
}
