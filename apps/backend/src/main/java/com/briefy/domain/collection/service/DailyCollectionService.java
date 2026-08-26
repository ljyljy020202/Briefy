package com.briefy.domain.collection.service;

import com.briefy.config.CollectionProperties;
import com.briefy.domain.candidatepool.dto.CandidatePoolUpsertResult;
import com.briefy.domain.candidatepool.dto.CollectedJobPostingData;
import com.briefy.domain.candidatepool.service.CandidatePoolPersistenceService;
import com.briefy.domain.candidatepool.service.CandidatePoolService;
import com.briefy.domain.collection.dto.DailyCollectionResult;
import com.briefy.domain.collection.entity.CollectionJob;
import com.briefy.domain.collection.entity.CollectionJobStatus;
import com.briefy.domain.collection.entity.CollectionTriggerType;
import com.briefy.domain.company.entity.Company;
import com.briefy.domain.company.entity.CompanyAlias;
import com.briefy.domain.company.repository.CompanyAliasRepository;
import com.briefy.domain.company.repository.CompanyRepository;
import com.briefy.domain.company.repository.CompanySourceRepository;
import com.briefy.domain.company.service.CompanyNameNormalizer;
import com.briefy.domain.preference.entity.BriefingCategoryCode;
import com.briefy.domain.preference.entity.UserBriefingPreference;
import com.briefy.domain.preference.repository.UserBriefingPreferenceRepository;
import com.briefy.global.exception.BusinessException;
import com.briefy.global.exception.ErrorCode;
import com.briefy.infra.agent.AgentClient;
import com.briefy.infra.agent.dto.AgentCollectedJobPosting;
import com.briefy.infra.agent.dto.AgentCollectionOptions;
import com.briefy.infra.agent.dto.AgentCollectionRequest;
import com.briefy.infra.agent.dto.AgentCollectionResponse;
import com.briefy.infra.agent.dto.AgentCompanyProfile;
import com.briefy.infra.agent.dto.AgentOfficialCompanySource;
import com.briefy.infra.agent.dto.AgentSeedKeywords;
import com.briefy.infra.agent.dto.AgentSourceOutcome;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class DailyCollectionService {

  private static final Logger log = LoggerFactory.getLogger(DailyCollectionService.class);

  private final CollectionJobService collectionJobService;
  private final UserBriefingPreferenceRepository userBriefingPreferenceRepository;
  private final AgentClient agentClient;
  private final CandidatePoolService candidatePoolService;
  private final CandidatePoolPersistenceService candidatePoolPersistenceService;
  private final CompanyRepository companyRepository;
  private final CompanyAliasRepository companyAliasRepository;
  private final CompanySourceRepository companySourceRepository;
  private final CompanyNameNormalizer companyNameNormalizer;
  private final CollectionProperties collectionProperties;

  public DailyCollectionService(
      CollectionJobService collectionJobService,
      UserBriefingPreferenceRepository userBriefingPreferenceRepository,
      AgentClient agentClient,
      CandidatePoolService candidatePoolService,
      CandidatePoolPersistenceService candidatePoolPersistenceService,
      CompanyRepository companyRepository,
      CompanyAliasRepository companyAliasRepository,
      CompanySourceRepository companySourceRepository,
      CompanyNameNormalizer companyNameNormalizer,
      CollectionProperties collectionProperties) {
    this.collectionJobService = collectionJobService;
    this.userBriefingPreferenceRepository = userBriefingPreferenceRepository;
    this.agentClient = agentClient;
    this.candidatePoolService = candidatePoolService;
    this.candidatePoolPersistenceService = candidatePoolPersistenceService;
    this.companyRepository = companyRepository;
    this.companyAliasRepository = companyAliasRepository;
    this.companySourceRepository = companySourceRepository;
    this.companyNameNormalizer = companyNameNormalizer;
    this.collectionProperties = collectionProperties;
  }

  public DailyCollectionResult triggerDailyCollection(
      LocalDate collectDate, List<String> requestedCategories) {
    return executeCollection(collectDate, requestedCategories, CollectionTriggerType.MANUAL);
  }

  public DailyCollectionResult triggerScheduledDailyCollection(LocalDate collectDate) {
    return executeCollection(collectDate, null, CollectionTriggerType.SCHEDULED);
  }

  public DailyCollectionResult retryCollectionJob(Long jobId) {
    CollectionJob job = collectionJobService.findOrThrow(jobId);

    if (job.getStatus() != CollectionJobStatus.FAILED) {
      throw new BusinessException(
          ErrorCode.COLLECTION_JOB_RETRY_NOT_ALLOWED,
          "Only FAILED jobs can be retried. Current status: " + job.getStatus());
    }

    boolean claimed =
        collectionJobService.claimForRetry(jobId, collectionProperties.jobMaxRetryCount());
    if (!claimed) {
      // 동시 retry 또는 최대 횟수 초과
      CollectionJob fresh = collectionJobService.findOrThrow(jobId);
      if (fresh.getRetryCount() >= collectionProperties.jobMaxRetryCount()) {
        throw new BusinessException(ErrorCode.COLLECTION_JOB_MAX_RETRY_EXCEEDED);
      }
      throw new BusinessException(ErrorCode.COLLECTION_JOB_ALREADY_ACTIVE);
    }

    return executeCollectionCore(
        job.getCollectionDate(), job.getId(), CollectionTriggerType.MANUAL);
  }

  private DailyCollectionResult executeCollection(
      LocalDate collectDate, List<String> requestedCategories, CollectionTriggerType triggerType) {
    List<String> categories = resolveCategories(requestedCategories);
    String categoriesJson = buildCategoriesJson(categories);

    // 1. 멱등적 생성 (UNIQUE 충돌 시 기존 job 재조회)
    CollectionJob job =
        collectionJobService.createOrGetForDate(collectDate, categoriesJson, triggerType);

    // 2. 상태에 따라 분기
    switch (job.getStatus()) {
      case PROCESSING -> {
        log.info("Collection already PROCESSING for {}: jobId={}", collectDate, job.getId());
        throw new BusinessException(ErrorCode.COLLECTION_JOB_ALREADY_ACTIVE);
      }
      case COMPLETED -> {
        log.info("Collection already COMPLETED for {}: jobId={}", collectDate, job.getId());
        return new DailyCollectionResult(
            job.getId(),
            CollectionJobStatus.COMPLETED.name(),
            collectDate,
            null,
            job.getSavedCount(),
            job.getDeduplicatedCount(),
            List.of(),
            null,
            CollectionJobStatus.COMPLETED.name());
      }
      case PARTIAL_SUCCESS -> {
        log.info("Collection already PARTIAL_SUCCESS for {}: jobId={}", collectDate, job.getId());
        return new DailyCollectionResult(
            job.getId(),
            CollectionJobStatus.PARTIAL_SUCCESS.name(),
            collectDate,
            null,
            job.getSavedCount(),
            job.getDeduplicatedCount(),
            List.of(),
            job.getErrorMessage(),
            CollectionJobStatus.PARTIAL_SUCCESS.name());
      }
      case FAILED -> {
        log.info("Collection previously FAILED for {}: jobId={}", collectDate, job.getId());
        return new DailyCollectionResult(
            job.getId(),
            CollectionJobStatus.FAILED.name(),
            collectDate,
            null,
            0,
            0,
            List.of(),
            job.getErrorMessage(),
            CollectionJobStatus.FAILED.name());
      }
      default -> {
        /* PENDING: 계속 진행 */
      }
    }

    // 3. 조건부 선점: PENDING → PROCESSING
    boolean claimed = collectionJobService.claimForProcessing(job.getId());
    if (!claimed) {
      log.info("Collection job {} already claimed by another thread", job.getId());
      throw new BusinessException(ErrorCode.COLLECTION_JOB_ALREADY_ACTIVE);
    }

    return executeCollectionCore(collectDate, job.getId(), triggerType);
  }

  private DailyCollectionResult executeCollectionCore(
      LocalDate collectDate, Long jobId, CollectionTriggerType triggerType) {
    // 카테고리는 항상 JOB_POSTING (1st MVP)
    List<String> categories =
        List.of(com.briefy.domain.preference.entity.BriefingCategoryCode.JOB_POSTING.name());

    try {
      AgentSeedKeywords seedKeywords = aggregateSeedKeywords(categories);
      List<AgentCompanyProfile> companyProfiles = resolveCompanyProfiles(seedKeywords.companies());
      List<AgentOfficialCompanySource> officialCompanySources =
          resolveOfficialSources(companyProfiles.stream().map(AgentCompanyProfile::id).toList());

      AgentCollectionRequest agentRequest =
          new AgentCollectionRequest(
              jobId,
              collectDate.toString(),
              categories,
              seedKeywords,
              new AgentCollectionOptions(
                  collectionProperties.lookbackDays(),
                  collectionProperties.discoveryLimitPerSource(),
                  collectionProperties.detailFetchLimitPerSource(),
                  collectionProperties.maxResultsPerSource(),
                  collectionProperties.maxTotalResults()),
              companyProfiles,
              officialCompanySources);

      AgentCollectionResponse agentResponse =
          agentClient.triggerDailyCollection(
              agentRequest,
              collectionProperties.agentRetryMaxAttempts(),
              collectionProperties.agentRetryBackoffSeconds());

      CollectionJobStatus outcome = determineOutcome(agentResponse);

      // 전체 소스 실패 → 저장하지 않고 FAILED 처리
      if (outcome == CollectionJobStatus.FAILED) {
        String errorSummary = buildSourceErrorSummary(agentResponse);
        collectionJobService.markFailed(jobId, errorSummary);
        log.warn(
            "Daily collection: all sources failed, jobId={}, errorSummary={}", jobId, errorSummary);
        return new DailyCollectionResult(
            jobId,
            CollectionJobStatus.FAILED.name(),
            collectDate,
            agentResponse.stats(),
            0,
            0,
            agentResponse.warnings() != null ? agentResponse.warnings() : List.of(),
            errorSummary,
            CollectionJobStatus.FAILED.name());
      }

      List<CollectedJobPostingData> postingData =
          mapToPostingData(agentResponse.jobPostings(), collectDate);
      CandidatePoolUpsertResult upsertResult;
      try {
        upsertResult = candidatePoolPersistenceService.saveAtomically(postingData, collectDate);
      } catch (Exception e) {
        String errorMessage = e.getMessage() != null ? e.getMessage() : "Persistence error";
        log.error("Daily collection: persistence failed, jobId={}, error={}", jobId, errorMessage);
        collectionJobService.markFailed(jobId, errorMessage);
        return new DailyCollectionResult(
            jobId,
            CollectionJobStatus.FAILED.name(),
            collectDate,
            agentResponse.stats(),
            0,
            0,
            agentResponse.warnings() != null ? agentResponse.warnings() : List.of(),
            errorMessage,
            CollectionJobStatus.FAILED.name());
      }

      int agentFinalCount =
          agentResponse.stats() != null ? agentResponse.stats().finalCount() : postingData.size();

      if (outcome == CollectionJobStatus.PARTIAL_SUCCESS) {
        String errorSummary = buildSourceErrorSummary(agentResponse);
        collectionJobService.markPartialSuccess(
            jobId,
            agentFinalCount,
            upsertResult.savedCount(),
            upsertResult.duplicateCount(),
            errorSummary);
        log.info(
            "Daily collection partial success: jobId={}, agentFinal={}, saved={}, persistenceDups={}",
            jobId,
            agentFinalCount,
            upsertResult.savedCount(),
            upsertResult.duplicateCount());
      } else {
        collectionJobService.markCompleted(
            jobId, agentFinalCount, upsertResult.savedCount(), upsertResult.duplicateCount());
        log.info(
            "Daily collection completed: jobId={}, agentFinal={}, saved={}, persistenceDups={}",
            jobId,
            agentFinalCount,
            upsertResult.savedCount(),
            upsertResult.duplicateCount());
      }

      return new DailyCollectionResult(
          jobId,
          outcome.name(),
          collectDate,
          agentResponse.stats(),
          upsertResult.savedCount(),
          upsertResult.duplicateCount(),
          agentResponse.warnings() != null ? agentResponse.warnings() : List.of(),
          null,
          outcome.name());

    } catch (Exception e) {
      String errorMessage = e.getMessage() != null ? e.getMessage() : "Unknown error";
      log.error("Daily collection failed: jobId={}, error={}", jobId, errorMessage);
      collectionJobService.markFailed(jobId, errorMessage);
      return new DailyCollectionResult(
          jobId,
          CollectionJobStatus.FAILED.name(),
          collectDate,
          null,
          0,
          0,
          List.of(),
          errorMessage,
          CollectionJobStatus.FAILED.name());
    }
  }

  private CollectionJobStatus determineOutcome(AgentCollectionResponse response) {
    List<AgentSourceOutcome> outcomes = response.sourceOutcomes();
    if (outcomes == null || outcomes.isEmpty()) {
      // 소스 정보 없으면 공고 수로 판단 (후방 호환)
      return CollectionJobStatus.COMPLETED;
    }
    long successCount = outcomes.stream().filter(AgentSourceOutcome::success).count();
    long failureCount = outcomes.stream().filter(o -> !o.success()).count();
    if (failureCount == 0) return CollectionJobStatus.COMPLETED;
    if (successCount == 0) return CollectionJobStatus.FAILED;
    return CollectionJobStatus.PARTIAL_SUCCESS;
  }

  private String buildSourceErrorSummary(AgentCollectionResponse response) {
    if (response.sourceOutcomes() == null || response.sourceOutcomes().isEmpty()) {
      return null;
    }
    return response.sourceOutcomes().stream()
        .filter(o -> !o.success())
        .map(o -> o.sourceName() + ":" + (o.errorSummary() != null ? o.errorSummary() : "error"))
        .collect(java.util.stream.Collectors.joining(", "));
  }

  private AgentSeedKeywords aggregateSeedKeywords(List<String> categories) {
    if (!categories.contains(BriefingCategoryCode.JOB_POSTING.name())) {
      return emptyKeywords();
    }

    List<UserBriefingPreference> prefs =
        userBriefingPreferenceRepository.findAllByCategoryCodeAndActiveTrue(
            BriefingCategoryCode.JOB_POSTING);

    Set<String> roles = new LinkedHashSet<>();
    Set<String> companies = new LinkedHashSet<>();
    Set<String> companySizes = new LinkedHashSet<>();
    Set<String> industries = new LinkedHashSet<>();
    Set<String> skills = new LinkedHashSet<>();
    Set<String> locations = new LinkedHashSet<>();
    Set<String> experienceLevels = new LinkedHashSet<>();
    Set<String> employmentTypes = new LinkedHashSet<>();

    for (UserBriefingPreference pref : prefs) {
      Map<String, Object> prefMap = pref.getPreference();
      if (prefMap == null) continue;
      roles.addAll(extractStringList(prefMap, "roles"));
      companies.addAll(extractStringList(prefMap, "companies"));
      companySizes.addAll(extractStringList(prefMap, "companySizes"));
      industries.addAll(extractStringList(prefMap, "industries"));
      skills.addAll(extractStringList(prefMap, "skills"));
      locations.addAll(extractStringList(prefMap, "locations"));
      experienceLevels.addAll(extractStringList(prefMap, "experienceLevels"));
      employmentTypes.addAll(extractStringList(prefMap, "employmentTypes"));
    }

    return new AgentSeedKeywords(
        new ArrayList<>(roles),
        new ArrayList<>(companies),
        new ArrayList<>(companySizes),
        new ArrayList<>(industries),
        new ArrayList<>(skills),
        new ArrayList<>(locations),
        new ArrayList<>(experienceLevels),
        new ArrayList<>(employmentTypes),
        List.of());
  }

  private List<String> extractStringList(Map<String, Object> pref, String key) {
    Object val = pref.get(key);
    if (val instanceof List<?> list) {
      return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }
    return List.of();
  }

  /**
   * Resolve preference company names to registered CompanyProfiles.
   *
   * <p>Matching order:
   *
   * <ol>
   *   <li>Normalize each name with {@link CompanyNameNormalizer}.
   *   <li>Direct match against {@code companies.normalized_name} (exact).
   *   <li>For names not matched in step 2, alias match against {@code
   *       company_aliases.normalized_alias} (exact).
   *   <li>Deduplicate by company id — direct match wins over alias match.
   * </ol>
   *
   * <p>Names that match nothing are silently skipped; no exception is thrown.
   */
  private List<AgentCompanyProfile> resolveCompanyProfiles(List<String> companyNames) {
    if (companyNames.isEmpty()) {
      return List.of();
    }

    // Normalize all names; distinct to avoid redundant queries
    List<String> normalized =
        companyNames.stream().map(companyNameNormalizer::normalize).distinct().toList();

    // Step 1: direct normalized_name match
    List<Company> directMatches = companyRepository.findActiveByNormalizedNames(normalized);

    Set<String> directMatchedNames =
        directMatches.stream().map(Company::getNormalizedName).collect(Collectors.toSet());

    // Step 2: alias match for names not found by direct lookup
    List<String> unmatched =
        normalized.stream().filter(n -> !directMatchedNames.contains(n)).toList();

    List<Company> aliasMatches = List.of();
    if (!unmatched.isEmpty()) {
      aliasMatches =
          companyAliasRepository.findAllByNormalizedAliasIn(unmatched).stream()
              .map(CompanyAlias::getCompany)
              .toList();
    }

    // Step 3: merge — direct match takes precedence; deduplicate by company id
    LinkedHashMap<Long, Company> byId = new LinkedHashMap<>();
    for (Company c : directMatches) {
      byId.put(c.getId(), c);
    }
    for (Company c : aliasMatches) {
      byId.putIfAbsent(c.getId(), c);
    }

    return byId.values().stream().map(this::toCompanyProfile).toList();
  }

  private AgentCompanyProfile toCompanyProfile(Company c) {
    String codes = c.getIndustryCodes();
    List<String> industryCodes =
        (codes == null || codes.isBlank())
            ? List.of()
            : Arrays.stream(codes.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    return new AgentCompanyProfile(
        c.getId(), c.getCanonicalName(), c.getNormalizedName(), c.getCompanySize(), industryCodes);
  }

  private List<CollectedJobPostingData> mapToPostingData(
      List<AgentCollectedJobPosting> postings, LocalDate collectedDate) {
    if (postings == null || postings.isEmpty()) return List.of();
    return postings.stream()
        .map(
            p ->
                new CollectedJobPostingData(
                    p.title(),
                    p.companyName(),
                    p.source(),
                    p.sourceUrl(),
                    p.location(),
                    parseDate(p.deadline()),
                    p.description(),
                    toJsonArray(p.roles()),
                    toJsonArray(p.skills()),
                    p.employmentType(),
                    p.experienceLevel(),
                    p.contentHash(),
                    parseDateTime(p.postedAt()),
                    p.sourceRecordKey(),
                    p.sourceExternalId(),
                    p.canonicalFingerprint()))
        .toList();
  }

  private String toJsonArray(List<String> items) {
    if (items == null || items.isEmpty()) return null;
    return "["
        + items.stream()
            .map(s -> "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
            .collect(Collectors.joining(","))
        + "]";
  }

  private LocalDate parseDate(String dateStr) {
    if (dateStr == null || dateStr.isBlank()) return null;
    try {
      return LocalDate.parse(dateStr);
    } catch (Exception ignored) {
      return null;
    }
  }

  private LocalDateTime parseDateTime(String dateTimeStr) {
    if (dateTimeStr == null || dateTimeStr.isBlank()) return null;
    try {
      return LocalDateTime.parse(dateTimeStr);
    } catch (Exception ignored) {
      return null;
    }
  }

  private List<String> resolveCategories(List<String> requested) {
    if (requested == null || requested.isEmpty()) {
      return List.of(BriefingCategoryCode.JOB_POSTING.name());
    }
    return requested;
  }

  private String buildCategoriesJson(List<String> categories) {
    return "["
        + categories.stream().map(c -> "\"" + c + "\"").collect(Collectors.joining(","))
        + "]";
  }

  private List<AgentOfficialCompanySource> resolveOfficialSources(List<Long> companyIds) {
    if (companyIds.isEmpty()) return List.of();
    return companySourceRepository.findActiveByCompanyIds(companyIds, "ACTIVE").stream()
        .map(
            s ->
                new AgentOfficialCompanySource(
                    s.getCompany().getId(),
                    s.getSourceType(),
                    s.getSourceUrl(),
                    s.getAdapterType(),
                    s.getConfigJson()))
        .toList();
  }

  private static AgentSeedKeywords emptyKeywords() {
    return new AgentSeedKeywords(
        List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
        List.of());
  }
}
