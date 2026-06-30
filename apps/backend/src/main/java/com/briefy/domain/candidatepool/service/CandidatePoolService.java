package com.briefy.domain.candidatepool.service;

import com.briefy.domain.candidatepool.dto.CandidatePoolUpsertResult;
import com.briefy.domain.candidatepool.dto.CollectedJobPostingData;
import com.briefy.domain.candidatepool.entity.JobPosting;
import com.briefy.domain.candidatepool.repository.JobPostingRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CandidatePoolService {

  private final JobPostingRepository jobPostingRepository;

  public CandidatePoolService(JobPostingRepository jobPostingRepository) {
    this.jobPostingRepository = jobPostingRepository;
  }

  @Transactional
  public CandidatePoolUpsertResult upsertJobPostings(
      List<CollectedJobPostingData> postings, LocalDate collectedDate) {
    int saved = 0;
    int duplicates = 0;

    for (CollectedJobPostingData data : postings) {
      if (data.url() != null) {
        Optional<JobPosting> existingByUrl = jobPostingRepository.findByUrl(data.url());
        if (existingByUrl.isPresent()) {
          existingByUrl
              .get()
              .refreshFrom(data.deadline(), data.description(), data.contentHash(), collectedDate);
          duplicates++;
          continue;
        }
      }

      if (data.contentHash() != null
          && jobPostingRepository.existsByContentHash(data.contentHash())) {
        duplicates++;
        continue;
      }

      jobPostingRepository.save(
          JobPosting.create(
              data.title(),
              data.company(),
              data.source(),
              data.url(),
              data.location(),
              data.deadline(),
              data.description(),
              data.roles(),
              data.skills(),
              data.employmentType(),
              data.experienceLevel(),
              data.contentHash(),
              collectedDate,
              data.publishedAt()));
      saved++;
    }

    return new CandidatePoolUpsertResult(postings.size(), saved, duplicates);
  }

  @Transactional(readOnly = true)
  public List<JobPosting> findJobPostingsByDate(LocalDate date) {
    return jobPostingRepository.findAllByCollectedDate(date);
  }
}
