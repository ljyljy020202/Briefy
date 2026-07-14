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

  @Query(
      "SELECT j FROM JobPosting j WHERE j.collectedDate BETWEEN :from AND :to"
          + " AND (j.source IS NULL OR j.source NOT IN :excludedSources)")
  List<JobPosting> findAllByCollectedDateBetweenExcludingSources(
      @Param("from") LocalDate from,
      @Param("to") LocalDate to,
      @Param("excludedSources") Collection<String> excludedSources);
}
