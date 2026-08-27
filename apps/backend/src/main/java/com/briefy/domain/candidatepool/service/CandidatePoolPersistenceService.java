package com.briefy.domain.candidatepool.service;

import com.briefy.domain.candidatepool.dto.CandidatePoolUpsertResult;
import com.briefy.domain.candidatepool.dto.CollectedJobPostingData;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Candidate Pool 저장을 짧은 독립 트랜잭션으로 실행. Agent HTTP 호출과 분리하여 커넥션 점유 방지. */
@Service
public class CandidatePoolPersistenceService {

  private final CandidatePoolService candidatePoolService;

  public CandidatePoolPersistenceService(CandidatePoolService candidatePoolService) {
    this.candidatePoolService = candidatePoolService;
  }

  /** job_postings와 job_posting_sources를 하나의 트랜잭션에서 upsert. 실패 시 해당 저장 전체 롤백. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public CandidatePoolUpsertResult saveAtomically(
      List<CollectedJobPostingData> postings, LocalDate collectedDate) {
    return candidatePoolService.upsertJobPostings(postings, collectedDate);
  }
}
