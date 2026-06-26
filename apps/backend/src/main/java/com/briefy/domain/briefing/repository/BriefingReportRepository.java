package com.briefy.domain.briefing.repository;

import com.briefy.domain.briefing.entity.BriefingReport;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BriefingReportRepository extends JpaRepository<BriefingReport, Long> {

  Page<BriefingReport> findAllByUserIdOrderByReportDateDesc(Long userId, Pageable pageable);
}
