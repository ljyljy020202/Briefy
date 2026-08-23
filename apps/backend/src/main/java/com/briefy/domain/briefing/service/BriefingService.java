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
import com.briefy.domain.briefing.policy.CandidateType;
import com.briefy.domain.briefing.policy.ExperienceParser;
import com.briefy.domain.briefing.policy.ExperiencePolicy;
import com.briefy.domain.briefing.policy.JobRolePolicy;
import com.briefy.domain.briefing.policy.ParsedExperience;
import com.briefy.domain.briefing.repository.BriefingArticleRepository;
import com.briefy.domain.briefing.repository.BriefingJobRepository;
import com.briefy.domain.briefing.repository.BriefingReportRepository;
import com.briefy.domain.briefingpreference.entity.BriefingCategoryCode;
import com.briefy.domain.briefingpreference.entity.UserBriefingPreference;
import com.briefy.domain.briefingpreference.repository.UserBriefingPreferenceRepository;
import com.briefy.domain.candidatepool.entity.JobPosting;
import com.briefy.domain.candidatepool.service.CandidatePoolService;
import com.briefy.domain.company.entity.Company;
import com.briefy.domain.user.repository.UserRepository;
import com.briefy.global.exception.BusinessException;
import com.briefy.global.exception.ErrorCode;
import com.briefy.global.response.PageResult;
import com.briefy.global.util.UrlUtils;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BriefingService {

  private static final ZoneId KST = ZoneId.of("Asia/Seoul");

  // ── Top-30 quota ──────────────────────────────────────────────────────────
  private static final int MAX_CANDIDATE_COUNT = 30;
  private static final int QUOTA_NEW = 8;
  private static final int QUOTA_URGENT = 10;
  private static final int QUOTA_EVERGREEN = 12;

  // ── Per-company diversity limits ──────────────────────────────────────────
  private static final int MAX_PER_COMPANY = 2;
  private static final int MAX_PER_TARGETED_COMPANY = 3;

  // ── Personalisation score weights ──────────────────────────────────────────
  private static final int SCORE_ROLE_MATCH = 30;
  private static final int SCORE_TARGET_COMPANY = 25;
  private static final int SCORE_SKILL = 5;
  private static final int SCORE_SKILLS_MAX = 25;
  private static final int SCORE_EXPERIENCE = 15;
  private static final int SCORE_INDUSTRY = 15;
  private static final int SCORE_LOCATION = 10;
  private static final int SCORE_EMPLOYMENT_TYPE = 10;
  private static final int SCORE_COMPANY_SIZE = 15;
  private static final int SCORE_RECENT = 5;

  // ── Deadline urgency bonus (replaces old SCORE_DEADLINE_SOON=10) ──────────
  private static final int URGENCY_BONUS_CRITICAL = 20; // deadline ≤ 1 day
  private static final int URGENCY_BONUS_NEAR = 10; // deadline ≤ 3 days

  // ── Exposure penalty ──────────────────────────────────────────────────────
  private static final int EXPOSURE_PENALTY_YESTERDAY = 25; // exposed yesterday or today
  private static final int EXPOSURE_PENALTY_RECENT = 15; // exposed 2–3 days ago
  private static final int EXPOSURE_PENALTY_STALE = 10; // exposed 4–6 days ago
  private static final int EXPOSURE_LOOKBACK_DAYS = 7;

  // ── CandidateType thresholds ──────────────────────────────────────────────
  private static final int NEW_DAYS = 3; // published/collected within this many days → NEW
  private static final int URGENT_DAYS = 7; // deadline within this many days → URGENT

  private final BriefingJobRepository briefingJobRepository;
  private final BriefingReportRepository briefingReportRepository;
  private final BriefingArticleRepository briefingArticleRepository;
  private final UserBriefingPreferenceRepository userBriefingPreferenceRepository;
  private final AgentClient agentClient;
  private final CandidatePoolService candidatePoolService;
  private final UserRepository userRepository;

  public BriefingService(
      BriefingJobRepository briefingJobRepository,
      BriefingReportRepository briefingReportRepository,
      BriefingArticleRepository briefingArticleRepository,
      UserBriefingPreferenceRepository userBriefingPreferenceRepository,
      AgentClient agentClient,
      CandidatePoolService candidatePoolService,
      UserRepository userRepository) {
    this.briefingJobRepository = briefingJobRepository;
    this.briefingReportRepository = briefingReportRepository;
    this.briefingArticleRepository = briefingArticleRepository;
    this.userBriefingPreferenceRepository = userBriefingPreferenceRepository;
    this.agentClient = agentClient;
    this.candidatePoolService = candidatePoolService;
    this.userRepository = userRepository;
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
      List<AgentCandidateJobPosting> candidates =
          selectCandidates(briefingDate, preference, userId);
      AgentCandidatePool candidatePool = new AgentCandidatePool(candidates, List.of(), List.of());

      AgentBriefingRequest agentRequest = buildAgentRequest(userId, preference, candidatePool);
      AgentBriefingResponse agentResponse = agentClient.generate(agentRequest);

      String nickname =
          userRepository
              .findById(userId)
              .map(u -> u.getNickname())
              .filter(n -> n != null && !n.isBlank())
              .orElse(null);
      if (nickname != null) {
        String updated =
            agentResponse.content().replace("# 오늘의 채용 브리핑", "# 오늘의 채용 브리핑 - " + nickname + "님");
        agentResponse =
            new AgentBriefingResponse(
                agentResponse.title(),
                agentResponse.summary(),
                updated,
                agentResponse.articles(),
                agentResponse.tokenUsage());
      }

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

  // ---------------------------------------------------------------------------
  // Candidate selection pipeline
  // ---------------------------------------------------------------------------

  private List<AgentCandidateJobPosting> selectCandidates(
      LocalDate date, Map<String, Object> preference, Long userId) {
    Map<String, LocalDate> exposureMap = loadExposureMap(userId, date);
    List<JobPosting> postings = candidatePoolService.findEligibleJobPostingsForBriefing(date);
    if (postings == null || postings.isEmpty()) return List.of();

    List<String> prefCompanies = extractStringList(preference, "companies");

    List<AgentCandidateJobPosting> scored =
        postings.stream()
            .filter(p -> isEligible(p, preference, date))
            .map(
                p -> {
                  int base = scorePosting(p, preference, date);
                  int urgency = computeUrgencyBonus(p.getDeadline(), date);
                  int penalty = computeExposurePenalty(p.getUrl(), exposureMap, date);
                  int total = base + urgency - penalty;
                  CandidateType type = classifyCandidateType(p, date);
                  return toAgentCandidateJobPosting(p, total, type);
                })
            .sorted(
                Comparator.comparingInt(AgentCandidateJobPosting::preScore)
                    .reversed()
                    .thenComparing(AgentCandidateJobPosting::companyName)
                    .thenComparing(AgentCandidateJobPosting::title))
            .toList();

    return buildTop30(scored, prefCompanies);
  }

  // ---------------------------------------------------------------------------
  // Eligibility filter
  // ---------------------------------------------------------------------------

  private boolean isEligible(JobPosting posting, Map<String, Object> pref, LocalDate today) {
    // ── 1. Expired postings ────────────────────────────────────────────────────
    if (posting.getDeadline() != null && posting.getDeadline().isBefore(today)) {
      return false;
    }

    // ── 2. Role eligibility (hard filter) ─────────────────────────────────────
    List<String> prefRoles = extractStringList(pref, "roles");
    JobRolePolicy.Verdict roleVerdict =
        JobRolePolicy.evaluate(prefRoles, posting.getTitle(), posting.getRoles());
    if (roleVerdict == JobRolePolicy.Verdict.MISMATCH) {
      return false;
    }

    // ── 3. Experience eligibility (hard filter for new-grad users) ─────────────
    List<String> prefExpLevels = extractStringList(pref, "experienceLevels");
    ParsedExperience parsedExp = ExperienceParser.parse(posting.getExperienceLevel());
    ExperiencePolicy.Verdict expVerdict = ExperiencePolicy.evaluate(prefExpLevels, parsedExp);
    if (expVerdict == ExperiencePolicy.Verdict.EXCLUDE) {
      return false;
    }

    // ── 4. Employment type (hard filter when both sides are explicit) ───────────
    List<String> prefEmpTypes = extractStringList(pref, "employmentTypes");
    String empType = posting.getEmploymentType();
    if (!prefEmpTypes.isEmpty() && empType != null && !empType.isBlank()) {
      if (prefEmpTypes.stream().noneMatch(e -> e.equalsIgnoreCase(empType))) {
        return false;
      }
    }

    return true;
  }

  // ---------------------------------------------------------------------------
  // Personalisation scoring
  // ---------------------------------------------------------------------------

  private int scorePosting(JobPosting posting, Map<String, Object> pref, LocalDate today) {
    int score = 0;

    List<String> prefRoles = extractStringList(pref, "roles");
    List<String> prefCompanies = extractStringList(pref, "companies");
    List<String> prefSkills = extractStringList(pref, "skills");
    List<String> prefLocations = extractStringList(pref, "locations");
    List<String> prefExpLevels = extractStringList(pref, "experienceLevels");
    List<String> prefEmpTypes = extractStringList(pref, "employmentTypes");
    List<String> prefIndustries = extractStringList(pref, "industries");
    List<String> prefCompanySizes = extractStringList(pref, "companySizes");

    // role/title match: +30 on MATCH; AMBIGUOUS gets no bonus (relative penalty)
    JobRolePolicy.Verdict roleVerdict =
        JobRolePolicy.evaluate(prefRoles, posting.getTitle(), posting.getRoles());
    if (roleVerdict == JobRolePolicy.Verdict.MATCH) {
      score += SCORE_ROLE_MATCH;
    }

    // target company match: +25
    // Uses prefix-aware matching so group subsidiaries (e.g. "토스인컴") match
    // a parent preference entry (e.g. "토스").
    String company = posting.getCompany() != null ? posting.getCompany() : "";
    String companyL = company.toLowerCase();
    for (String prefCo : prefCompanies) {
      if (isCompanyMatch(companyL, prefCo.toLowerCase())) {
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

    // experience compatibility: PASS_FULL → +15; PASS_PARTIAL → 0 (EXCLUDE already filtered)
    ParsedExperience parsedExp = ExperienceParser.parse(posting.getExperienceLevel());
    ExperiencePolicy.Verdict expVerdict = ExperiencePolicy.evaluate(prefExpLevels, parsedExp);
    if (expVerdict == ExperiencePolicy.Verdict.PASS_FULL) {
      score += SCORE_EXPERIENCE;
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

    // recently collected (within 3 days): +5
    if (posting.getCollectedDate() != null
        && !posting.getCollectedDate().isBefore(today.minusDays(3))) {
      score += SCORE_RECENT;
    }

    return score;
  }

  // ---------------------------------------------------------------------------
  // Urgency bonus (replaces old flat SCORE_DEADLINE_SOON)
  // ---------------------------------------------------------------------------

  int computeUrgencyBonus(LocalDate deadline, LocalDate today) {
    if (deadline == null) return 0;
    long daysUntil = ChronoUnit.DAYS.between(today, deadline);
    if (daysUntil <= 1) return URGENCY_BONUS_CRITICAL;
    if (daysUntil <= 3) return URGENCY_BONUS_NEAR;
    return 0;
  }

  // ---------------------------------------------------------------------------
  // Exposure penalty
  // ---------------------------------------------------------------------------

  private Map<String, LocalDate> loadExposureMap(Long userId, LocalDate today) {
    LocalDate since = today.minusDays(EXPOSURE_LOOKBACK_DAYS);
    List<BriefingArticleRepository.ExposedUrlInfo> rows =
        briefingArticleRepository.findRecentExposuresByUserId(userId, since);
    if (rows == null || rows.isEmpty()) return Map.of();

    Map<String, LocalDate> map = new HashMap<>();
    for (BriefingArticleRepository.ExposedUrlInfo row : rows) {
      if (row.getUrl() == null) continue;
      String canonical = UrlUtils.canonicalize(row.getUrl());
      if (canonical != null) {
        // If the same canonical URL appears multiple times (different raw forms),
        // keep the most recent exposure date.
        map.merge(canonical, row.getLastExposedDate(), (a, b) -> a.isAfter(b) ? a : b);
      }
    }
    return map;
  }

  int computeExposurePenalty(String url, Map<String, LocalDate> exposureMap, LocalDate today) {
    String canonical = UrlUtils.canonicalize(url);
    if (canonical == null) return 0;
    LocalDate lastExposed = exposureMap.get(canonical);
    if (lastExposed == null) return 0;

    long daysSince = ChronoUnit.DAYS.between(lastExposed, today);
    if (daysSince <= 1) return EXPOSURE_PENALTY_YESTERDAY;
    if (daysSince <= 3) return EXPOSURE_PENALTY_RECENT;
    if (daysSince <= 6) return EXPOSURE_PENALTY_STALE;
    return 0;
  }

  // ---------------------------------------------------------------------------
  // CandidateType classification
  // ---------------------------------------------------------------------------

  CandidateType classifyCandidateType(JobPosting jp, LocalDate today) {
    LocalDate newThreshold = today.minusDays(NEW_DAYS);

    // NEW: publishedAt within 3 days; if null, use collectedDate as firstSeenAt
    boolean isNew = false;
    if (jp.getPublishedAt() != null) {
      isNew = !jp.getPublishedAt().toLocalDate().isBefore(newThreshold);
    } else if (jp.getCollectedDate() != null) {
      isNew = !jp.getCollectedDate().isBefore(newThreshold);
    }
    if (isNew) return CandidateType.NEW;

    // URGENT: not NEW and deadline within 7 days (inclusive)
    if (jp.getDeadline() != null) {
      long daysUntil = ChronoUnit.DAYS.between(today, jp.getDeadline());
      if (daysUntil >= 0 && daysUntil <= URGENT_DAYS) {
        return CandidateType.URGENT;
      }
    }

    return CandidateType.EVERGREEN;
  }

  // ---------------------------------------------------------------------------
  // Top-30 quota selection with per-company diversity
  // ---------------------------------------------------------------------------

  private List<AgentCandidateJobPosting> buildTop30(
      List<AgentCandidateJobPosting> allScored, List<String> targetedCompanies) {
    Map<String, Integer> companyCount = new HashMap<>();
    Set<String> selectedUrls = new HashSet<>();
    List<AgentCandidateJobPosting> result = new ArrayList<>();

    List<AgentCandidateJobPosting> newGroup =
        allScored.stream().filter(c -> CandidateType.NEW.name().equals(c.candidateType())).toList();
    List<AgentCandidateJobPosting> urgentGroup =
        allScored.stream()
            .filter(c -> CandidateType.URGENT.name().equals(c.candidateType()))
            .toList();
    List<AgentCandidateJobPosting> evergreenGroup =
        allScored.stream()
            .filter(c -> CandidateType.EVERGREEN.name().equals(c.candidateType()))
            .toList();

    result.addAll(
        pickFromGroup(newGroup, targetedCompanies, companyCount, selectedUrls, QUOTA_NEW));
    result.addAll(
        pickFromGroup(urgentGroup, targetedCompanies, companyCount, selectedUrls, QUOTA_URGENT));
    result.addAll(
        pickFromGroup(
            evergreenGroup, targetedCompanies, companyCount, selectedUrls, QUOTA_EVERGREEN));

    // Fill remaining slots from any leftover candidates (cross-type, still respecting limits)
    if (result.size() < MAX_CANDIDATE_COUNT) {
      List<AgentCandidateJobPosting> leftovers =
          allScored.stream().filter(c -> !selectedUrls.contains(c.sourceUrl())).toList();
      result.addAll(
          pickFromGroup(
              leftovers,
              targetedCompanies,
              companyCount,
              selectedUrls,
              MAX_CANDIDATE_COUNT - result.size()));
    }

    return result;
  }

  private List<AgentCandidateJobPosting> pickFromGroup(
      List<AgentCandidateJobPosting> group,
      List<String> targetedCompanies,
      Map<String, Integer> companyCount,
      Set<String> selectedUrls,
      int quota) {
    List<AgentCandidateJobPosting> result = new ArrayList<>();
    for (AgentCandidateJobPosting candidate : group) {
      if (result.size() >= quota) break;
      String companyLower =
          candidate.companyName() != null ? candidate.companyName().toLowerCase() : "";
      boolean isTargeted =
          !companyLower.isEmpty()
              && targetedCompanies.stream()
                  .anyMatch(t -> isCompanyMatch(companyLower, t.toLowerCase()));
      int limit = isTargeted ? MAX_PER_TARGETED_COMPANY : MAX_PER_COMPANY;
      int current = companyCount.getOrDefault(companyLower, 0);
      if (current < limit) {
        result.add(candidate);
        companyCount.put(companyLower, current + 1);
        if (candidate.sourceUrl() != null) selectedUrls.add(candidate.sourceUrl());
      }
    }
    return result;
  }

  // ---------------------------------------------------------------------------
  // DTO builder
  // ---------------------------------------------------------------------------

  private AgentCandidateJobPosting toAgentCandidateJobPosting(
      JobPosting p, int preScore, CandidateType candidateType) {
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
        preScore,
        true,
        candidateType != null ? candidateType.name() : null);
  }

  // ---------------------------------------------------------------------------
  // Agent request / report building
  // ---------------------------------------------------------------------------

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

  // ---------------------------------------------------------------------------
  // Utilities
  // ---------------------------------------------------------------------------

  /**
   * True when the posting company name matches a preference entry using prefix-aware comparison.
   *
   * <p>Both arguments must already be lowercase. A match occurs when either string is a prefix of
   * the other, so a user preference of "토스" matches posting companies "토스인컴", "토스뱅크", "토스페이먼츠",
   * etc.
   */
  static boolean isCompanyMatch(String postingCompanyLower, String prefCompanyLower) {
    if (postingCompanyLower.isEmpty() || prefCompanyLower.isEmpty()) return false;
    return postingCompanyLower.equals(prefCompanyLower)
        || postingCompanyLower.startsWith(prefCompanyLower)
        || prefCompanyLower.startsWith(postingCompanyLower);
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
