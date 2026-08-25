package com.briefy.domain.briefing.service;

import com.briefy.domain.briefing.dto.BriefingDetailResponse;
import com.briefy.domain.briefing.dto.BriefingListItem;
import com.briefy.domain.briefing.dto.GenerateResult;
import com.briefy.domain.briefing.entity.BriefingArticle;
import com.briefy.domain.briefing.entity.BriefingJob;
import com.briefy.domain.briefing.entity.BriefingReport;
import com.briefy.domain.briefing.entity.BriefingTriggerType;
import com.briefy.domain.briefing.recommendation.RecommendationCandidate;
import com.briefy.domain.briefing.recommendation.RecommendationFilter;
import com.briefy.domain.briefing.recommendation.RecommendationSelector;
import com.briefy.domain.briefing.recommendation.RelevanceScorer;
import com.briefy.domain.briefing.recommendation.ScoreBreakdown;
import com.briefy.domain.briefing.repository.BriefingArticleRepository;
import com.briefy.domain.briefing.repository.BriefingJobRepository;
import com.briefy.domain.briefing.repository.BriefingReportRepository;
import com.briefy.domain.candidatepool.entity.JobPosting;
import com.briefy.domain.candidatepool.service.CandidatePoolService;
import com.briefy.domain.preference.entity.BriefingCategoryCode;
import com.briefy.domain.preference.entity.UserBriefingPreference;
import com.briefy.domain.preference.repository.UserBriefingPreferenceRepository;
import com.briefy.domain.user.repository.UserRepository;
import com.briefy.global.exception.BusinessException;
import com.briefy.global.exception.ErrorCode;
import com.briefy.global.response.PageResult;
import com.briefy.global.util.UrlUtils;
import com.briefy.infra.agent.AgentClient;
import com.briefy.infra.agent.dto.AgentBriefingRequest;
import com.briefy.infra.agent.dto.AgentBriefingResponse;
import com.briefy.infra.agent.dto.AgentCandidateJobPosting;
import com.briefy.infra.agent.dto.AgentCandidatePool;
import com.briefy.infra.agent.dto.AgentMatchEvidence;
import com.briefy.infra.agent.dto.AgentScoreBreakdown;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BriefingService {

  private static final ZoneId KST = ZoneId.of("Asia/Seoul");
  private static final int EXPOSURE_LOOKBACK_DAYS = 7;

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
      List<AgentCandidateJobPosting> top7 = selectTop7(briefingDate, preference, userId);
      AgentCandidatePool candidatePool = new AgentCandidatePool(top7, List.of(), List.of());

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
  // Recommendation pipeline: filter → score → expose-penalty → select Top 7
  // ---------------------------------------------------------------------------

  private List<AgentCandidateJobPosting> selectTop7(
      LocalDate date, Map<String, Object> preference, Long userId) {
    Map<String, LocalDate> exposureMap = loadExposureMap(userId, date);
    List<JobPosting> postings = candidatePoolService.findEligibleJobPostingsForBriefing(date);
    if (postings == null || postings.isEmpty()) return List.of();

    List<RecommendationCandidate> candidates =
        postings.stream()
            .filter(p -> RecommendationFilter.evaluate(p, preference, date).eligible())
            .map(
                p -> {
                  RelevanceScorer.ScoringResult scored = RelevanceScorer.score(p, preference);
                  int penalty =
                      RelevanceScorer.computeExposurePenalty(p.getUrl(), exposureMap, date);
                  ScoreBreakdown breakdown = scored.breakdown().withExposurePenalty(penalty);
                  boolean isNew =
                      RecommendationCandidate.computeIsNew(
                          p, date, RecommendationSelector.NEW_DAYS);
                  boolean isUrgent =
                      RecommendationCandidate.computeIsUrgent(
                          p, date, RecommendationSelector.URGENT_DAYS);
                  return new RecommendationCandidate(
                      p, isNew, isUrgent, breakdown, scored.evidence());
                })
            .toList();

    List<RecommendationCandidate> top7 = RecommendationSelector.select(candidates);

    return IntStream.range(0, top7.size())
        .mapToObj(i -> toAgentCandidateJobPosting(top7.get(i), i + 1))
        .toList();
  }

  // ---------------------------------------------------------------------------
  // Exposure map
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
        map.merge(canonical, row.getLastExposedDate(), (a, b) -> a.isAfter(b) ? a : b);
      }
    }
    return map;
  }

  // ---------------------------------------------------------------------------
  // DTO builder
  // ---------------------------------------------------------------------------

  private AgentCandidateJobPosting toAgentCandidateJobPosting(
      RecommendationCandidate candidate, int rank) {
    JobPosting p = candidate.posting();
    return new AgentCandidateJobPosting(
        p.getId(),
        rank,
        p.getSource(),
        p.getUrl(),
        p.getCompany(),
        p.getTitle(),
        p.getEmploymentType(),
        p.getExperienceLevel(),
        p.getLocation(),
        p.getDeadline() != null ? p.getDeadline().toString() : null,
        parseJsonArray(p.getSkills()),
        parseJsonArray(p.getRoles()),
        p.getDescription(),
        p.getPublishedAt() != null ? p.getPublishedAt().toString() : null,
        p.getCollectedDate() != null ? p.getCollectedDate().toString() : null,
        candidate.isNew(),
        candidate.isUrgent(),
        AgentScoreBreakdown.from(candidate.scoreBreakdown()),
        AgentMatchEvidence.from(candidate.matchEvidence()));
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

    // displayOrder reflects the Backend-determined rank (1-based, preserved by Agent).
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
