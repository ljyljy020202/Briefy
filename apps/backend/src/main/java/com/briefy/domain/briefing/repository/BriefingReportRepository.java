package com.briefy.domain.briefing.repository;

import com.briefy.domain.briefing.entity.BriefingReport;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BriefingReportRepository extends JpaRepository<BriefingReport, Long> {

  Page<BriefingReport> findAllByUserIdOrderByReportDateDesc(Long userId, Pageable pageable);

  List<BriefingReport> findAllByUserId(Long userId);

  boolean existsByUserIdAndReportDate(Long userId, LocalDate reportDate);

  Optional<BriefingReport> findByUserIdAndReportDate(Long userId, LocalDate reportDate);

  boolean existsByBriefingJobId(Long briefingJobId);
}
