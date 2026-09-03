package com.briefy.domain.briefing.service;

import com.briefy.config.BriefingProperties;
import com.briefy.config.ClassificationMode;
import com.briefy.config.ClassificationProperties;
import com.briefy.domain.briefing.dto.BriefingDetailResponse;
import com.briefy.domain.briefing.dto.BriefingListItem;
import com.briefy.domain.briefing.dto.GenerateResult;
import com.briefy.domain.briefing.entity.BriefingArticle;
import com.briefy.domain.briefing.entity.BriefingJob;
import com.briefy.domain.briefing.entity.BriefingJobStatus;
import com.briefy.domain.briefing.entity.BriefingReport;
import com.briefy.domain.briefing.entity.BriefingTriggerType;
import com.briefy.domain.briefing.recommendation.AnalysisEligibility;
import com.briefy.domain.briefing.recommendation.ClassificationAnalyzer;
import com.briefy.domain.briefing.recommendation.FilterResult;
import com.briefy.domain.briefing.recommendation.RecommendationCandidate;
import com.briefy.domain.briefing.recommendation.RecommendationFilter;
import com.briefy.domain.briefing.recommendation.RecommendationSelector;
import com.briefy.domain.briefing.recommendation.RelevanceScorer;
import com.briefy.domain.briefing.recommendation.ScoreBreakdown;
import com.briefy.domain.briefing.repository.BriefingArticleRepository;
import com.briefy.domain.briefing.repository.BriefingJobRepository;
import com.briefy.domain.briefing.repository.BriefingReportRepository;
import com.briefy.domain.candidatepool.entity.JobPosting;
import com.briefy.domain.candidatepool.entity.JobPostingAnalysis;
import com.briefy.domain.candidatepool.repository.JobPostingAnalysisRepository;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BriefingService {

  private static final Logger log = LoggerFactory.getLogger(BriefingService.class);
  private static final ZoneId KST = ZoneId.of("Asia/Seoul");
  private static final int EXPOSURE_LOOKBACK_DAYS = 7;

  private final BriefingJobRepository briefingJobRepository;
  private final BriefingReportRepository briefingReportRepository;
  private final BriefingArticleRepository briefingArticleRepository;
  private final UserBriefingPreferenceRepository userBriefingPreferenceRepository;
  private final AgentClient agentClient;
  private final CandidatePoolService candidatePoolService;
  private final UserRepository userRepository;
  private final BriefingJobPersistenceService briefingJobPersistenceService;
  private final BriefingProperties briefingProperties;
  private final JobPostingAnalysisRepository jobPostingAnalysisRepository;
  private final ClassificationProperties classificationProperties;

  public BriefingService(
      BriefingJobRepository briefingJobRepository,
      BriefingReportRepository briefingReportRepository,
      BriefingArticleRepository briefingArticleRepository,
      UserBriefingPreferenceRepository userBriefingPreferenceRepository,
      AgentClient agentClient,
      CandidatePoolService candidatePoolService,
      UserRepository userRepository,
      BriefingJobPersistenceService briefingJobPersistenceService,
      BriefingProperties briefingProperties,
      JobPostingAnalysisRepository jobPostingAnalysisRepository,
      ClassificationProperties classificationProperties) {
    this.briefingJobRepository = briefingJobRepository;
    this.briefingReportRepository = briefingReportRepository;
    this.briefingArticleRepository = briefingArticleRepository;
    this.userBriefingPreferenceRepository = userBriefingPreferenceRepository;
    this.agentClient = agentClient;
    this.candidatePoolService = candidatePoolService;
    this.userRepository = userRepository;
    this.briefingJobPersistenceService = briefingJobPersistenceService;
    this.briefingProperties = briefingProperties;
    this.jobPostingAnalysisRepository = jobPostingAnalysisRepository;
    this.classificationProperties = classificationProperties;
  }

  /**
   * 멱등적 브리핑 생성. 외부 Agent HTTP 호출이 있으므로 @Transactional 없음. 트랜잭션 경계는
   * BriefingJobPersistenceService(REQUIRES_NEW)로 격리.
   */
  public GenerateResult generateBriefing(Long userId) {
    return doGenerateBriefing(userId, BriefingTriggerType.MANUAL);
  }

  public GenerateResult generateScheduledBriefing(Long userId) {
    return doGenerateBriefing(userId, BriefingTriggerType.SCHEDULED);
  }

  /** FAILED 상태인 브리핑 Job을 재시도한다. FAILED 상태이고 이미 Report가 없으며 최대 retry 횟수 미만일 때만 허용. */
  public GenerateResult retryBriefingJob(Long jobId) {
    BriefingJob job = briefingJobPersistenceService.findJobById(jobId);

    if (job.getStatus() != BriefingJobStatus.FAILED) {
      throw new BusinessException(ErrorCode.BRIEFING_JOB_RETRY_NOT_ALLOWED);
    }

    // 기존 Report가 있으면 재시도 차단
    boolean hasReport = briefingReportRepository.existsByBriefingJobId(jobId);
    if (hasReport) {
      throw new BusinessException(ErrorCode.BRIEFING_JOB_HAS_EXISTING_REPORT);
    }

    boolean claimed =
        briefingJobPersistenceService.claimForRetry(jobId, briefingProperties.jobMaxRetryCount());
    if (!claimed) {
      BriefingJob freshJob = briefingJobPersistenceService.findJobById(jobId);
      if (freshJob.getRetryCount() >= briefingProperties.jobMaxRetryCount()) {
        throw new BusinessException(ErrorCode.BRIEFING_JOB_MAX_RETRY_EXCEEDED);
      }
      throw new BusinessException(ErrorCode.BRIEFING_JOB_ALREADY_PROCESSING);
    }

    return executeBriefingCore(job.getUserId(), jobId, job.getBriefingDate(), job.getTriggerType());
  }

  private GenerateResult doGenerateBriefing(Long userId, BriefingTriggerType triggerType) {
    LocalDate briefingDate = LocalDate.now(KST);

    // Step 1: 오늘 이미 완료된 브리핑이 있으면 반환 (짧은 readOnly TX)
    java.util.Optional<BriefingReport> existingReport =
        briefingJobPersistenceService.findTodayReport(userId, briefingDate);
    if (existingReport.isPresent()) {
      BriefingReport r = existingReport.get();
      return new GenerateResult(
          r.getId(), r.getBriefingJobId(), BriefingJobStatus.COMPLETED.name());
    }

    // Step 2: Job 생성 (짧은 REQUIRES_NEW TX)
    BriefingJob newJob =
        triggerType == BriefingTriggerType.SCHEDULED
            ? BriefingJob.createScheduled(userId, briefingDate)
            : BriefingJob.createManual(userId, briefingDate);
    BriefingJob job = briefingJobPersistenceService.createOrGet(newJob);

    // Step 3: 상태 분기
    switch (job.getStatus()) {
      case PROCESSING -> throw new BusinessException(ErrorCode.BRIEFING_JOB_ALREADY_PROCESSING);
      case COMPLETED -> {
        java.util.Optional<BriefingReport> r =
            briefingJobPersistenceService.findTodayReport(userId, briefingDate);
        if (r.isPresent()) {
          return new GenerateResult(
              r.get().getId(), job.getId(), BriefingJobStatus.COMPLETED.name());
        }
      }
      case FAILED -> throw new BusinessException(ErrorCode.BRIEFING_JOB_FAILED_NO_RETRY);
      default -> {
        /* PENDING: 계속 */
      }
    }

    // Step 4: 조건부 선점 (REQUIRES_NEW TX)
    boolean claimed = briefingJobPersistenceService.claimForProcessing(job.getId());
    if (!claimed) {
      throw new BusinessException(ErrorCode.BRIEFING_JOB_ALREADY_PROCESSING);
    }

    return executeBriefingCore(userId, job.getId(), briefingDate, triggerType);
  }

  /** Agent 호출 → Report 저장 핵심 로직. doGenerateBriefing과 retryBriefingJob 양쪽에서 공유. */
  private GenerateResult executeBriefingCore(
      Long userId, Long jobId, LocalDate briefingDate, BriefingTriggerType triggerType) {
    try {
      // Step 5: 사용자 선호도 조회 (TX 없음)
      List<UserBriefingPreference> preferences =
          userBriefingPreferenceRepository.findAllByUserIdAndActiveTrue(userId);
      UserBriefingPreference jobPref =
          preferences.stream()
              .filter(p -> p.getCategory().getCode() == BriefingCategoryCode.JOB_POSTING)
              .findFirst()
              .orElse(null);
      Map<String, Object> preference = jobPref != null ? jobPref.getPreference() : Map.of();

      // Step 6: Top 7 선정 (TX 없음)
      List<AgentCandidateJobPosting> top7 = selectTop7(briefingDate, preference, userId);
      AgentCandidatePool candidatePool = new AgentCandidatePool(top7, List.of(), List.of());

      // Step 7: Agent HTTP 호출 with retry (TX 없음)
      AgentBriefingRequest agentRequest = buildAgentRequest(userId, preference, candidatePool);
      AgentBriefingResponse agentResponse =
          agentClient.generate(
              agentRequest,
              briefingProperties.agentRetryMaxAttempts(),
              briefingProperties.agentRetryBackoffSeconds());

      // Agent 응답 계약 검증
      validateAgentResponse(agentResponse);

      // 구조화 로그
      log.info(
          "briefing generation: userId={} mode={} usedFallback={} rewriteCount={}"
              + " fallbackReason={}",
          userId,
          agentResponse.generationModeOrDefault(),
          agentResponse.isUsedFallback(),
          agentResponse.rewriteCountOrZero(),
          agentResponse.fallbackReason());

      // nickname 치환 (기존 로직 유지)
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
                agentResponse.tokenUsage(),
                agentResponse.generationMode(),
                agentResponse.usedFallback(),
                agentResponse.fallbackReason(),
                agentResponse.rewriteCount());
      }

      // Step 8: Report 저장 + Job 완료 (짧은 REQUIRES_NEW TX)
      BriefingJob freshJob =
          briefingJobRepository
              .findById(jobId)
              .orElseThrow(() -> new BusinessException(ErrorCode.BRIEFING_JOB_NOT_FOUND));
      BriefingReport report = buildReport(userId, freshJob, agentResponse);
      BriefingReport saved =
          briefingJobPersistenceService.saveReportAndComplete(
              jobId,
              report,
              agentResponse.generationModeOrDefault(),
              agentResponse.fallbackReason());

      return new GenerateResult(saved.getId(), jobId, BriefingJobStatus.COMPLETED.name());

    } catch (BusinessException e) {
      // Step 9: 실패 기록 (REQUIRES_NEW TX — 원래 TX 롤백과 무관)
      briefingJobPersistenceService.recordFailure(jobId, e.getMessage());
      throw e;
    } catch (Exception e) {
      String msg = e.getMessage() != null ? e.getMessage() : "Unknown error";
      briefingJobPersistenceService.recordFailure(jobId, msg);
      throw new BusinessException(ErrorCode.BRIEFING_JOB_FAILED);
    }
  }

  /** Agent 응답 계약 검증. EMPTY 모드는 title/content 검증 생략. FALLBACK 모드는 content 필수이나 articles는 빈 배열 허용. */
  private void validateAgentResponse(AgentBriefingResponse response) {
    String mode = response.generationModeOrDefault();
    if ("EMPTY".equals(mode)) {
      return; // empty 응답은 별도 검증 없음
    }
    if (response.title() == null || response.title().isBlank()) {
      throw new BusinessException(ErrorCode.AGENT_CONTRACT_VIOLATION, "Agent returned blank title");
    }
    if (response.content() == null || response.content().isBlank()) {
      throw new BusinessException(
          ErrorCode.AGENT_CONTRACT_VIOLATION, "Agent returned blank content");
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

    // 분류 분석 일괄 조회 — N+1 방지
    ClassificationMode mode = classificationProperties.mode();
    Map<Long, JobPostingAnalysis> analysisMap = loadAnalysisMap(postings);

    List<RecommendationCandidate> candidates =
        postings.stream()
            .flatMap(
                p -> {
                  JobPostingAnalysis analysis = analysisMap.get(p.getId());
                  AnalysisEligibility eligibility =
                      ClassificationAnalyzer.derive(analysis, preference, mode);

                  FilterResult filterResult;
                  RelevanceScorer.ScoringResult scored;

                  if (mode == ClassificationMode.SHADOW) {
                    // SHADOW: 키워드 기반 결과 사용, 분류 결과를 로그로만 남김
                    filterResult = RecommendationFilter.evaluate(p, preference, date);
                    scored = RelevanceScorer.score(p, preference);

                    FilterResult classFilter =
                        RecommendationFilter.evaluateWithClassification(
                            p, preference, date, eligibility, ClassificationMode.ENFORCE);
                    RelevanceScorer.ScoringResult classScored =
                        RelevanceScorer.score(p, preference, eligibility);

                    if (filterResult.eligible() != classFilter.eligible()) {
                      log.info(
                          "SHADOW_FILTER_DIFF: posting={} keyword={} classification={}",
                          p.getId(),
                          filterResult.reason(),
                          classFilter.reason());
                    }
                    if (filterResult.eligible()) {
                      int kwRole = scored.breakdown().roleScore();
                      int clRole = classScored.breakdown().roleScore();
                      int kwExp = scored.breakdown().experienceScore();
                      int clExp = classScored.breakdown().experienceScore();
                      int clOpenRecruit = classScored.breakdown().openRecruitmentScore();
                      if (kwRole != clRole || kwExp != clExp || clOpenRecruit != 0) {
                        log.info(
                            "SHADOW_SCORE_DIFF: posting={} kwRole={} clRole={} kwExp={} clExp={}"
                                + " clOpenRecruit={}",
                            p.getId(),
                            kwRole,
                            clRole,
                            kwExp,
                            clExp,
                            clOpenRecruit);
                      }
                    }
                  } else {
                    filterResult =
                        RecommendationFilter.evaluateWithClassification(
                            p, preference, date, eligibility, mode);
                    scored = RelevanceScorer.score(p, preference, eligibility);
                  }

                  if (!filterResult.eligible()) return java.util.stream.Stream.empty();

                  int penalty =
                      RelevanceScorer.computeExposurePenalty(p.getUrl(), exposureMap, date);
                  ScoreBreakdown breakdown = scored.breakdown().withExposurePenalty(penalty);
                  boolean isNew =
                      RecommendationCandidate.computeIsNew(
                          p, date, RecommendationSelector.NEW_DAYS);
                  boolean isUrgent =
                      RecommendationCandidate.computeIsUrgent(
                          p, date, RecommendationSelector.URGENT_DAYS);
                  return java.util.stream.Stream.of(
                      new RecommendationCandidate(
                          p, isNew, isUrgent, breakdown, scored.evidence()));
                })
            .toList();

    List<RecommendationCandidate> top7 = RecommendationSelector.select(candidates);

    return IntStream.range(0, top7.size())
        .mapToObj(i -> toAgentCandidateJobPosting(top7.get(i), i + 1))
        .toList();
  }

  // ---------------------------------------------------------------------------
  // Analysis map (batch load to avoid N+1)
  // ---------------------------------------------------------------------------

  private Map<Long, JobPostingAnalysis> loadAnalysisMap(List<JobPosting> postings) {
    if (postings.isEmpty()) return Map.of();
    List<Long> ids = postings.stream().map(JobPosting::getId).toList();
    List<JobPostingAnalysis> analyses = jobPostingAnalysisRepository.findAllByJobPostingIdIn(ids);
    Map<Long, JobPostingAnalysis> map = new HashMap<>(analyses.size() * 2);
    for (JobPostingAnalysis a : analyses) {
      map.put(a.getJobPostingId(), a);
    }
    return map;
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
