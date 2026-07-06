package com.briefy.domain.collection.service;

import com.briefy.domain.briefing.client.AgentClient;
import com.briefy.domain.briefing.client.dto.AgentCollectedJobPosting;
import com.briefy.domain.briefing.client.dto.AgentCollectionOptions;
import com.briefy.domain.briefing.client.dto.AgentCollectionRequest;
import com.briefy.domain.briefing.client.dto.AgentCollectionResponse;
import com.briefy.domain.briefing.client.dto.AgentCompanyProfile;
import com.briefy.domain.briefing.client.dto.AgentOfficialCompanySource;
import com.briefy.domain.briefing.client.dto.AgentSeedKeywords;
import com.briefy.domain.briefingpreference.entity.BriefingCategoryCode;
import com.briefy.domain.briefingpreference.entity.UserBriefingPreference;
import com.briefy.domain.briefingpreference.repository.UserBriefingPreferenceRepository;
import com.briefy.domain.candidatepool.dto.CandidatePoolUpsertResult;
import com.briefy.domain.candidatepool.dto.CollectedJobPostingData;
import com.briefy.domain.candidatepool.service.CandidatePoolService;
import com.briefy.domain.collection.dto.DailyCollectionResult;
import com.briefy.domain.collection.entity.CollectionJob;
import com.briefy.domain.collection.entity.CollectionJobStatus;
import com.briefy.domain.collection.entity.CollectionTriggerType;
import com.briefy.domain.company.repository.CompanyRepository;
import com.briefy.domain.company.repository.CompanySourceRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
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
  private final CompanyRepository companyRepository;
  private final CompanySourceRepository companySourceRepository;

  public DailyCollectionService(
      CollectionJobService collectionJobService,
      UserBriefingPreferenceRepository userBriefingPreferenceRepository,
      AgentClient agentClient,
      CandidatePoolService candidatePoolService,
      CompanyRepository companyRepository,
      CompanySourceRepository companySourceRepository) {
    this.collectionJobService = collectionJobService;
    this.userBriefingPreferenceRepository = userBriefingPreferenceRepository;
    this.agentClient = agentClient;
    this.candidatePoolService = candidatePoolService;
    this.companyRepository = companyRepository;
    this.companySourceRepository = companySourceRepository;
  }

  public DailyCollectionResult triggerDailyCollection(
      LocalDate collectDate, List<String> requestedCategories) {
    return executeCollection(collectDate, requestedCategories, CollectionTriggerType.MANUAL);
  }

  public DailyCollectionResult triggerScheduledDailyCollection(LocalDate collectDate) {
    if (collectionJobService.isAlreadyActiveForDate(collectDate)) {
      log.info(
          "Skipping scheduled collection for {} — already PROCESSING or COMPLETED", collectDate);
      return new DailyCollectionResult(
          null,
          "SKIPPED",
          collectDate,
          0,
          0,
          0,
          "Already PROCESSING or COMPLETED for " + collectDate);
    }
    return executeCollection(collectDate, null, CollectionTriggerType.SCHEDULED);
  }

  private DailyCollectionResult executeCollection(
      LocalDate collectDate, List<String> requestedCategories, CollectionTriggerType triggerType) {
    List<String> categories = resolveCategories(requestedCategories);
    String categoriesJson = buildCategoriesJson(categories);

    CollectionJob job =
        collectionJobService.createPending(collectDate, categoriesJson, triggerType);
    collectionJobService.markProcessing(job.getId());

    try {
      AgentSeedKeywords seedKeywords = aggregateSeedKeywords(categories);
      List<AgentCompanyProfile> companyProfiles = resolveCompanyProfiles(seedKeywords.companies());
      List<AgentOfficialCompanySource> officialCompanySources =
          resolveOfficialSources(companyProfiles.stream().map(AgentCompanyProfile::id).toList());

      AgentCollectionRequest agentRequest =
          new AgentCollectionRequest(
              job.getId(),
              collectDate.toString(),
              categories,
              seedKeywords,
              new AgentCollectionOptions(3, 14, 50),
              companyProfiles,
              officialCompanySources);

      AgentCollectionResponse agentResponse = agentClient.triggerDailyCollection(agentRequest);

      List<CollectedJobPostingData> postingData =
          mapToPostingData(agentResponse.jobPostings(), collectDate);
      CandidatePoolUpsertResult upsertResult =
          candidatePoolService.upsertJobPostings(postingData, collectDate);

      int collectedCount =
          agentResponse.stats() != null
              ? agentResponse.stats().collectedCount()
              : postingData.size();
      collectionJobService.markCompleted(
          job.getId(), collectedCount, upsertResult.savedCount(), upsertResult.duplicateCount());

      log.info(
          "Daily collection completed: jobId={}, collected={}, saved={}, duplicates={}",
          job.getId(),
          collectedCount,
          upsertResult.savedCount(),
          upsertResult.duplicateCount());

      return new DailyCollectionResult(
          job.getId(),
          CollectionJobStatus.COMPLETED.name(),
          collectDate,
          collectedCount,
          upsertResult.savedCount(),
          upsertResult.duplicateCount(),
          null);

    } catch (Exception e) {
      String errorMessage = e.getMessage() != null ? e.getMessage() : "Unknown error";
      log.error("Daily collection failed: jobId={}, error={}", job.getId(), errorMessage);
      collectionJobService.markFailed(job.getId(), errorMessage);
      return new DailyCollectionResult(
          job.getId(), CollectionJobStatus.FAILED.name(), collectDate, 0, 0, 0, errorMessage);
    }
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

  private List<AgentCompanyProfile> resolveCompanyProfiles(List<String> companyNames) {
    if (companyNames.isEmpty()) return List.of();
    List<String> normalized =
        companyNames.stream().map(n -> n.toLowerCase().trim()).distinct().toList();
    return companyRepository.findActiveByNormalizedNames(normalized).stream()
        .map(
            c -> {
              String codes = c.getIndustryCodes();
              List<String> industryCodes =
                  (codes == null || codes.isBlank())
                      ? List.of()
                      : Arrays.stream(codes.split(","))
                          .map(String::strip)
                          .filter(s -> !s.isEmpty())
                          .toList();
              return new AgentCompanyProfile(
                  c.getId(),
                  c.getCanonicalName(),
                  c.getNormalizedName(),
                  c.getCompanySize(),
                  industryCodes);
            })
        .toList();
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
