package com.briefy.domain.briefing.repository;

import com.briefy.domain.briefing.entity.BriefingJob;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BriefingJobRepository extends JpaRepository<BriefingJob, Long> {

  void deleteAllByUserId(Long userId);

  Optional<BriefingJob> findByUserIdAndBriefingDate(Long userId, LocalDate briefingDate);

  @Modifying
  @Query(
      """
      UPDATE BriefingJob j
      SET j.status = com.briefy.domain.briefing.entity.BriefingJobStatus.PROCESSING,
          j.startedAt = :now
      WHERE j.id = :id
      AND j.status = com.briefy.domain.briefing.entity.BriefingJobStatus.PENDING
      """)
  int claimPending(@Param("id") Long id, @Param("now") LocalDateTime now);
}
