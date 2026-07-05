package com.briefy.domain.candidatepool.repository;

import com.briefy.domain.candidatepool.entity.JobPosting;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobPostingRepository extends JpaRepository<JobPosting, Long> {

  Optional<JobPosting> findFirstByUrl(String url);

  Optional<JobPosting> findFirstByCanonicalFingerprint(String canonicalFingerprint);

  List<JobPosting> findAllByCollectedDate(LocalDate collectedDate);
}
