package com.briefy.domain.candidatepool.repository;

import com.briefy.domain.candidatepool.entity.JobPosting;
import com.briefy.domain.candidatepool.entity.JobPostingSource;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobPostingSourceRepository extends JpaRepository<JobPostingSource, Long> {

  Optional<JobPostingSource> findBySourceRecordKey(String sourceRecordKey);

  List<JobPostingSource> findAllByJobPosting(JobPosting jobPosting);
}
