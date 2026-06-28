package com.briefy.domain.candidatepool.repository;

import com.briefy.domain.candidatepool.entity.JobPosting;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobPostingRepository extends JpaRepository<JobPosting, Long> {

  List<JobPosting> findAllByCollectedDate(LocalDate collectedDate);

  boolean existsByUrl(String url);

  boolean existsByContentHash(String contentHash);
}
