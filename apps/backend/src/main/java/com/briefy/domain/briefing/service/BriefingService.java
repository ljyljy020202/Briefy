package com.briefy.domain.briefing.service;

import com.briefy.domain.briefing.client.AgentClient;
import com.briefy.domain.briefing.client.dto.AgentBriefingRequest;
import com.briefy.domain.briefing.client.dto.AgentBriefingResponse;
import com.briefy.domain.briefing.client.dto.AgentCandidateJobPosting;
import com.briefy.domain.briefing.client.dto.AgentCandidatePool;
import com.briefy.domain.briefing.dto.BriefingDetailResponse;
import com.briefy.domain.briefing.dto.BriefingListItem;
import com.briefy.domain.briefing.dto.GenerateBriefingRequest;
import com.briefy.domain.briefing.dto.GenerateResult;
import com.briefy.domain.briefing.entity.BriefingArticle;
import com.briefy.domain.briefing.entity.BriefingJob;
import com.briefy.domain.briefing.entity.BriefingReport;
import com.briefy.domain.briefing.entity.BriefingTriggerType;
import com.briefy.domain.briefing.repository.BriefingJobRepository;
import com.briefy.domain.briefing.repository.BriefingReportRepository;
import com.briefy.domain.briefingpreference.entity.BriefingCategoryCode;
import com.briefy.domain.briefingpreference.entity.UserBriefingPreference;
import com.briefy.domain.briefingpreference.repository.UserBriefingPreferenceRepository;
import com.briefy.domain.candidatepool.entity.JobPosting;
import com.briefy.domain.candidatepool.service.CandidatePoolService;
import com.briefy.global.exception.BusinessException;
import com.briefy.global.exception.ErrorCode;
import com.briefy.global.response.PageResult;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BriefingService {

  private static final int MAX_CANDIDATE_COUNT = 30;

  private final BriefingJobRepository briefingJobRepository;
  private final BriefingReportRepository briefingReportRepository;
  private final UserBriefingPreferenceRepository userBriefingPreferenceRepository;
  private final AgentClient agentClient;
  private final CandidatePoolService candidatePoolService;

  public BriefingService(
      BriefingJobRepository briefingJobRepository,
      BriefingReportRepository briefingReportRepository,
      UserBriefingPreferenceRepository userBriefingPreferenceRepository,
      AgentClient agentClient,
      CandidatePoolService candidatePoolService) {
    this.briefingJobRepository = briefingJobRepository;
    this.briefingReportRepository = briefingReportRepository;
    this.userBriefingPreferenceRepository = userBriefingPreferenceRepository;
    this.agentClient = agentClient;
    this.candidatePoolService = candidatePoolService;
  }

  /**
   * noRollbackFor ensures the job FAILED status is committed to the DB even when an exception is
   * re-thrown, so failure state is always visible for debugging and retries.
   */
  @Transactional(noRollbackFor = Exception.class)
  public GenerateResult generateBriefing(Long userId, GenerateBriefingRequest request) {
    return doGenerateBriefing(userId, request, BriefingTriggerType.MANUAL);
  }

  @Transactional(noRollbackFor = Exception.class)
  public GenerateResult generateScheduledBriefing(Long userId) {
    return doGenerateBriefing(
        userId, new GenerateBriefingRequest(null), BriefingTriggerType.SCHEDULED);
  }

  private GenerateResult doGenerateBriefing(
      Long userId, GenerateBriefingRequest request, BriefingTriggerType triggerType) {
    List<UserBriefingPreference> preferences =
        userBriefingPreferenceRepository.findAllByUserIdAndActiveTrue(userId);

    UserBriefingPreference jobPref =
        preferences.stream()
            .filter(p -> p.getCategory().getCode() == BriefingCategoryCode.JOB_POSTING)
            .findFirst()
            .orElse(null);

    BriefingJob job =
        triggerType == BriefingTriggerType.SCHEDULED
            ? BriefingJob.createScheduled(userId)
            : BriefingJob.createManual(userId);
    job.startProcessing();
    briefingJobRepository.save(job);

    try {
      Map<String, Object> preference = jobPref != null ? jobPref.getPreference() : Map.of();
      LocalDate briefingDate = LocalDate.now();
      List<AgentCandidateJobPosting> candidates = selectCandidates(briefingDate, preference);
      AgentCandidatePool candidatePool = new AgentCandidatePool(candidates, List.of(), List.of());

      AgentBriefingRequest agentRequest =
          buildAgentRequest(userId, preference, request, candidatePool);
      AgentBriefingResponse agentResponse = agentClient.generate(agentRequest);

      BriefingReport report = buildReport(userId, job, request.resolvedTone(), agentResponse);
      BriefingReport saved = briefingReportRepository.save(report);

      job.complete();
      return new GenerateResult(saved.getId(), job.getId(), job.getStatus().name());

    } catch (BusinessException e) {
      job.fail(e.getMessage());
      throw e;
    } catch (Exception e) {
      job.fail(e.getMessage() != null ? e.getMessage() : "Unknown error");
      throw new BusinessException(ErrorCode.BRIEFING_JOB_FAILED);
    }
  }

  @Transactional(readOnly = true)
  public PageResult<BriefingListItem> listBriefings(Long userId, int page, int size) {
    int cappedSize = Math.min(size, 50);
    Page<BriefingReport> reports =
        briefingReportRepository.findAllByUserIdOrderByReportDateDesc(
            userId, PageRequest.of(page, cappedSize));
    return PageResult.from(reports.map(BriefingListItem::from));
  }

  @Transactional(readOnly = true)
  public BriefingDetailResponse getBriefingDetail(Long userId, Long reportId) {
    BriefingReport report =
        briefingReportRepository
            .findById(reportId)
            .orElseThrow(() -> new BusinessException(ErrorCode.BRIEFING_REPORT_NOT_FOUND));

    if (!report.getUserId().equals(userId)) {
      throw new BusinessException(ErrorCode.FORBIDDEN);
    }

    return BriefingDetailResponse.from(report);
  }

  private List<AgentCandidateJobPosting> selectCandidates(
      LocalDate date, Map<String, Object> preference) {
    List<JobPosting> postings = candidatePoolService.findJobPostingsByDate(date);
    if (postings == null || postings.isEmpty()) return List.of();

    return postings.stream()
        .map(p -> toAgentCandidateJobPosting(p, scorePosting(p, preference)))
        .sorted(Comparator.comparingInt(AgentCandidateJobPosting::preScore).reversed())
        .limit(MAX_CANDIDATE_COUNT)
        .toList();
  }

  private int scorePosting(JobPosting posting, Map<String, Object> pref) {
    int score = 0;

    List<String> prefRoles = extractStringList(pref, "roles");
    List<String> prefCompanies = extractStringList(pref, "companies");
    List<String> prefSkills = extractStringList(pref, "skills");
    List<String> prefLocations = extractStringList(pref, "locations");
    List<String> prefExpLevels = extractStringList(pref, "experienceLevels");
    List<String> prefEmpTypes = extractStringList(pref, "employmentTypes");

    // role/title match: +30
    String titleLower = posting.getTitle() != null ? posting.getTitle().toLowerCase() : "";
    String rolesStr = posting.getRoles() != null ? posting.getRoles().toLowerCase() : "";
    for (String role : prefRoles) {
      if (titleLower.contains(role.toLowerCase()) || rolesStr.contains(role.toLowerCase())) {
        score += 30;
        break;
      }
    }

    // interested company match: +15
    String company = posting.getCompany() != null ? posting.getCompany() : "";
    for (String prefCo : prefCompanies) {
      if (company.equalsIgnoreCase(prefCo)) {
        score += 15;
        break;
      }
    }

    // each matching skill: +5, max +25
    String skillsStr = posting.getSkills() != null ? posting.getSkills().toLowerCase() : "";
    int skillScore = 0;
    for (String skill : prefSkills) {
      if (skillsStr.contains(skill.toLowerCase())) {
        skillScore += 5;
        if (skillScore >= 25) break;
      }
    }
    score += skillScore;

    // location match: +10
    String location = posting.getLocation() != null ? posting.getLocation() : "";
    for (String prefLoc : prefLocations) {
      if (location.contains(prefLoc) || prefLoc.contains(location)) {
        score += 10;
        break;
      }
    }

    // experience level match: +10
    String expLevel = posting.getExperienceLevel() != null ? posting.getExperienceLevel() : "";
    for (String prefExp : prefExpLevels) {
      if (expLevel.equalsIgnoreCase(prefExp)) {
        score += 10;
        break;
      }
    }

    // employment type match: +5
    String empType = posting.getEmploymentType() != null ? posting.getEmploymentType() : "";
    for (String prefEmp : prefEmpTypes) {
      if (empType.equalsIgnoreCase(prefEmp)) {
        score += 5;
        break;
      }
    }

    // deadline within 7 days: +10
    if (posting.getDeadline() != null) {
      long days = ChronoUnit.DAYS.between(LocalDate.now(), posting.getDeadline());
      if (days >= 0 && days <= 7) {
        score += 10;
      }
    }

    // recently collected (within 3 days): +5
    if (posting.getCollectedDate() != null
        && !posting.getCollectedDate().isBefore(LocalDate.now().minusDays(3))) {
      score += 5;
    }

    return score;
  }

  private AgentCandidateJobPosting toAgentCandidateJobPosting(JobPosting p, int preScore) {
    return new AgentCandidateJobPosting(
        p.getId(),
        p.getSource(),
        p.getUrl(),
        p.getCompany(),
        p.getTitle(),
        null,
        p.getEmploymentType(),
        p.getExperienceLevel(),
        p.getLocation(),
        p.getDeadline() != null ? p.getDeadline().toString() : null,
        parseJsonArray(p.getSkills()),
        parseJsonArray(p.getRoles()),
        p.getDescription(),
        p.getPublishedAt() != null ? p.getPublishedAt().toString() : null,
        p.getCollectedDate() != null ? p.getCollectedDate().toString() : null,
        p.getContentHash(),
        preScore);
  }

  private List<String> extractStringList(Map<String, Object> pref, String key) {
    Object val = pref.get(key);
    if (val instanceof List<?> list) {
      return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }
    return List.of();
  }

  private List<String> parseJsonArray(String json) {
    if (json == null || json.isBlank()) return List.of();
    String trimmed = json.trim();
    if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return List.of();
    String inner = trimmed.substring(1, trimmed.length() - 1).trim();
    if (inner.isBlank()) return List.of();
    return Arrays.stream(inner.split(","))
        .map(s -> s.trim().replaceAll("^\"|\"$", ""))
        .filter(s -> !s.isBlank())
        .toList();
  }

  private AgentBriefingRequest buildAgentRequest(
      Long userId,
      Map<String, Object> preference,
      GenerateBriefingRequest request,
      AgentCandidatePool candidatePool) {
    return new AgentBriefingRequest(
        userId,
        BriefingCategoryCode.JOB_POSTING.name(),
        preference,
        LocalDate.now().toString(),
        request.resolvedTone(),
        candidatePool);
  }

  private BriefingReport buildReport(
      Long userId, BriefingJob job, String tone, AgentBriefingResponse agentResponse) {
    List<AgentBriefingResponse.AgentArticle> agentArticles =
        agentResponse.articles() != null ? agentResponse.articles() : List.of();

    Integer tokenInput = null;
    Integer tokenOutput = null;
    if (agentResponse.tokenUsage() != null) {
      tokenInput = agentResponse.tokenUsage().inputTokens();
      tokenOutput = agentResponse.tokenUsage().outputTokens();
    }

    BriefingReport report =
        BriefingReport.create(
            userId,
            job,
            agentResponse.title(),
            agentResponse.summary(),
            agentResponse.content(),
            LocalDate.now(),
            tone,
            tokenInput,
            tokenOutput,
            agentArticles.size());

    for (int i = 0; i < agentArticles.size(); i++) {
      AgentBriefingResponse.AgentArticle a = agentArticles.get(i);
      report.addArticle(
          BriefingArticle.create(
              report,
              a.title(),
              a.source(),
              a.url(),
              a.summary(),
              a.whyItMatters(),
              parsePublishedAt(a.publishedAt()),
              i));
    }

    return report;
  }

  private LocalDateTime parsePublishedAt(String publishedAt) {
    if (publishedAt == null || publishedAt.isBlank()) {
      return null;
    }
    try {
      return LocalDateTime.parse(publishedAt);
    } catch (Exception e) {
      return null;
    }
  }
}
