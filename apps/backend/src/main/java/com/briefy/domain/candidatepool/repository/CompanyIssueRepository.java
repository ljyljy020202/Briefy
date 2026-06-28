package com.briefy.domain.candidatepool.repository;

import com.briefy.domain.candidatepool.entity.CompanyIssue;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CompanyIssueRepository extends JpaRepository<CompanyIssue, Long> {

  List<CompanyIssue> findAllByCollectedDate(LocalDate collectedDate);

  boolean existsByUrl(String url);
}
