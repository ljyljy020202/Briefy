package com.briefy.domain.briefing.repository;

import com.briefy.domain.briefing.entity.BriefingJob;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BriefingJobRepository extends JpaRepository<BriefingJob, Long> {

  void deleteAllByUserId(Long userId);
}
