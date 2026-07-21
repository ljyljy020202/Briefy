package com.briefy.domain.briefing.service;

import com.briefy.domain.briefing.client.AgentClient;
import com.briefy.domain.briefing.client.dto.AgentBriefingRequest;
import com.briefy.domain.briefing.client.dto.AgentBriefingResponse;
import com.briefy.domain.briefing.client.dto.AgentCandidateJobPosting;
import com.briefy.domain.briefing.client.dto.AgentCandidatePool;
import com.briefy.domain.briefing.dto.BriefingDetailResponse;
import com.briefy.domain.briefing.dto.BriefingListItem;
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
import com.briefy.domain.company.entity.Company;
import com.briefy.global.exception.BusinessException;
import com.briefy.global.exception.ErrorCode;
import com.briefy.global.response.PageResult;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BriefingService {

  private static final ZoneId KST = ZoneId.of("Asia/Seoul");
  private static final int MAX_CANDIDATE_COUNT = 30;
  private static final int MAX_PER_COMPANY = 2;
  private static final int MAX_PER_TARGETED_COMPANY = 3;

  private static final int SCORE_ROLE_MATCH = 30;
  private static final int SCORE_TARGET_COMPANY = 25;
  private static final int SCORE_SKILL = 5;
  private static final int SCORE_SKILLS_MAX = 25;
  private static final int SCORE_EXPERIENCE = 15;
  private static final int SCORE_INDUSTRY = 12;
  private static final int SCORE_LOCATION = 10;
  private static final int SCORE_EMPLOYMENT_TYPE = 10;
  private static final int SCORE_COMPANY_SIZE = 8;
  private static final int SCORE_DEADLINE_SOON = 10;
  private static final int SCORE_RECENT = 5;

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
  public GenerateResult generateBriefing(Long userId) {
    return doGenerateBriefing(userId, BriefingTriggerType.MANUAL);
  }

  @Transactional(noRollbackFor = Exception.class)
  public GenerateResult generateScheduledBriefing(Long userId) {
    return doGenerateBriefing(userId, BriefingTriggerType.SCHEDULED);
  }

  private GenerateResult doGenerateBriefing(Long userId, BriefingTriggerType triggerType) {
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
      LocalDate briefingDate = LocalDate.now(KST);
      List<AgentCandidateJobPosting> candidates = selectCandidates(briefingDate, preference);
      AgentCandidatePool candidatePool = new AgentCandidatePool(candidates, List.of(), List.of());

      AgentBriefingRequest agentRequest = buildAgentRequest(userId, preference, candidatePool);
      AgentBriefingResponse agentResponse = agentClient.generate(agentRequest);

      BriefingReport report = buildReport(userId, job, agentResponse);
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

    List<String> prefCompanies = extractStringList(preference, "companies");

    List<AgentCandidateJobPosting> scored =
        postings.stream()
            .filter(p -> isEligible(p, preference))
            .map(p -> toAgentCandidateJobPosting(p, scorePosting(p, preference)))
            .sorted(
                Comparator.comparingInt(AgentCandidateJobPosting::preScore)
                    .reversed()
                    .thenComparing(AgentCandidateJobPosting::companyName)
                    .thenComparing(AgentCandidateJobPosting::title))
            .toList();

    return applyDiversitySelection(scored, prefCompanies);
  }

  private boolean isEligible(JobPosting posting, Map<String, Object> pref) {
    if (posting.getDeadline() != null && posting.getDeadline().isBefore(LocalDate.now(KST))) {
      return false;
    }

    List<String> prefExpLevels = extractStringList(pref, "experienceLevels");
    String expLevel = posting.getExperienceLevel();
    if (!prefExpLevels.isEmpty() && expLevel != null && !expLevel.isBlank()) {
      if (prefExpLevels.stream().noneMatch(e -> e.equalsIgnoreCase(expLevel))) {
        return false;
      }
    }

    List<String> prefEmpTypes = extractStringList(pref, "employmentTypes");
    String empType = posting.getEmploymentType();
    if (!prefEmpTypes.isEmpty() && empType != null && !empType.isBlank()) {
      if (prefEmpTypes.stream().noneMatch(e -> e.equalsIgnoreCase(empType))) {
        return false;
      }
    }

    return true;
  }

  private int scorePosting(JobPosting posting, Map<String, Object> pref) {
    int score = 0;

    List<String> prefRoles = extractStringList(pref, "roles");
    List<String> prefCompanies = extractStringList(pref, "companies");
    List<String> prefSkills = extractStringList(pref, "skills");
    List<String> prefLocations = extractStringList(pref, "locations");
    List<String> prefExpLevels = extractStringList(pref, "experienceLevels");
    List<String> prefEmpTypes = extractStringList(pref, "employmentTypes");
    List<String> prefIndustries = extractStringList(pref, "industries");
    List<String> prefCompanySizes = extractStringList(pref, "companySizes");

    // role/title match: +30
    String titleLower = posting.getTitle() != null ? posting.getTitle().toLowerCase() : "";
    String rolesStr = posting.getRoles() != null ? posting.getRoles().toLowerCase() : "";
    for (String role : prefRoles) {
      if (titleLower.contains(role.toLowerCase()) || rolesStr.contains(role.toLowerCase())) {
        score += SCORE_ROLE_MATCH;
        break;
      }
    }

    // target company match: +25
    String company = posting.getCompany() != null ? posting.getCompany() : "";
    for (String prefCo : prefCompanies) {
      if (company.equalsIgnoreCase(prefCo)) {
        score += SCORE_TARGET_COMPANY;
        break;
      }
    }

    // each matching skill: +5, max +25
    String skillsStr = posting.getSkills() != null ? posting.getSkills().toLowerCase() : "";
    int skillScore = 0;
    for (String skill : prefSkills) {
      if (skillsStr.contains(skill.toLowerCase())) {
        skillScore += SCORE_SKILL;
        if (skillScore >= SCORE_SKILLS_MAX) break;
      }
    }
    score += skillScore;

    // experience level match: +15
    String expLevel = posting.getExperienceLevel() != null ? posting.getExperienceLevel() : "";
    for (String prefExp : prefExpLevels) {
      if (expLevel.equalsIgnoreCase(prefExp)) {
        score += SCORE_EXPERIENCE;
        break;
      }
    }

    // industry match via linkedCompany: +12
    Company linkedCompany = posting.getLinkedCompany();
    if (!prefIndustries.isEmpty()
        && linkedCompany != null
        && linkedCompany.getIndustryCodes() != null
        && !linkedCompany.getIndustryCodes().isBlank()) {
      List<String> codes =
          Arrays.stream(linkedCompany.getIndustryCodes().split(",")).map(String::trim).toList();
      for (String prefIndustry : prefIndustries) {
        if (codes.stream().anyMatch(c -> c.equalsIgnoreCase(prefIndustry))) {
          score += SCORE_INDUSTRY;
          break;
        }
      }
    }

    // location match: +10
    String location = posting.getLocation() != null ? posting.getLocation() : "";
    for (String prefLoc : prefLocations) {
      if (location.contains(prefLoc) || prefLoc.contains(location)) {
        score += SCORE_LOCATION;
        break;
      }
    }

    // employment type match: +10
    String empType = posting.getEmploymentType() != null ? posting.getEmploymentType() : "";
    for (String prefEmp : prefEmpTypes) {
      if (empType.equalsIgnoreCase(prefEmp)) {
        score += SCORE_EMPLOYMENT_TYPE;
        break;
      }
    }

    // company size match via linkedCompany: +8
    if (!prefCompanySizes.isEmpty()
        && linkedCompany != null
        && linkedCompany.getCompanySize() != null
        && !linkedCompany.getCompanySize().isBlank()) {
      for (String prefSize : prefCompanySizes) {
        if (linkedCompany.getCompanySize().equalsIgnoreCase(prefSize)) {
          score += SCORE_COMPANY_SIZE;
          break;
        }
      }
    }

    // deadline within 7 days: +10
    if (posting.getDeadline() != null) {
      long days = ChronoUnit.DAYS.between(LocalDate.now(KST), posting.getDeadline());
      if (days >= 0 && days <= 7) {
        score += SCORE_DEADLINE_SOON;
      }
    }

    // recently collected (within 3 days): +5
    if (posting.getCollectedDate() != null
        && !posting.getCollectedDate().isBefore(LocalDate.now(KST).minusDays(3))) {
      score += SCORE_RECENT;
    }

    return score;
  }

  private List<AgentCandidateJobPosting> applyDiversitySelection(
      List<AgentCandidateJobPosting> sorted, List<String> targetCompanies) {
    Map<String, Integer> companyCount = new HashMap<>();
    List<AgentCandidateJobPosting> result = new ArrayList<>();

    for (AgentCandidateJobPosting candidate : sorted) {
      if (result.size() >= MAX_CANDIDATE_COUNT) break;
      String companyLower =
          candidate.companyName() != null ? candidate.companyName().toLowerCase() : "";
      boolean isTargeted =
          !companyLower.isEmpty()
              && targetCompanies.stream().anyMatch(t -> t.toLowerCase().equals(companyLower));
      int limit = isTargeted ? MAX_PER_TARGETED_COMPANY : MAX_PER_COMPANY;
      int current = companyCount.getOrDefault(companyLower, 0);
      if (current < limit) {
        result.add(candidate);
        companyCount.put(companyLower, current + 1);
      }
    }
    return result;
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
      Long userId, Map<String, Object> preference, AgentCandidatePool candidatePool) {
    return new AgentBriefingRequest(
        userId,
        BriefingCategoryCode.JOB_POSTING.name(),
        preference,
        LocalDate.now(KST).toString(),
        "easy",
        candidatePool);
  }

  private BriefingReport buildReport(
      Long userId, BriefingJob job, AgentBriefingResponse agentResponse) {
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
            LocalDate.now(KST),
            "easy",
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
