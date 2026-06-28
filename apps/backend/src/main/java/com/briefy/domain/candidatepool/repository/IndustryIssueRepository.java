package com.briefy.domain.candidatepool.repository;

import com.briefy.domain.candidatepool.entity.IndustryIssue;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IndustryIssueRepository extends JpaRepository<IndustryIssue, Long> {

  List<IndustryIssue> findAllByCollectedDate(LocalDate collectedDate);

  boolean existsByUrl(String url);
}
