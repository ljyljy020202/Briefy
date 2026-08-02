package com.briefy.domain.collection.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.briefy.config.CollectionProperties;
import com.briefy.domain.briefing.client.AgentClient;
import com.briefy.domain.briefing.client.dto.AgentCollectedJobPosting;
import com.briefy.domain.briefing.client.dto.AgentCollectionOptions;
import com.briefy.domain.briefing.client.dto.AgentCollectionRequest;
import com.briefy.domain.briefing.client.dto.AgentCollectionResponse;
import com.briefy.domain.briefing.client.dto.AgentCollectionStats;
import com.briefy.domain.briefingpreference.entity.BriefingCategoryCode;
import com.briefy.domain.briefingpreference.entity.UserBriefingPreference;
import com.briefy.domain.briefingpreference.repository.UserBriefingPreferenceRepository;
import com.briefy.domain.candidatepool.dto.CandidatePoolUpsertResult;
import com.briefy.domain.candidatepool.dto.CollectedJobPostingData;
import com.briefy.domain.candidatepool.service.CandidatePoolService;
import com.briefy.domain.collection.dto.DailyCollectionResult;
import com.briefy.domain.collection.entity.CollectionJob;
import com.briefy.domain.collection.entity.CollectionTriggerType;
import com.briefy.domain.company.entity.Company;
import com.briefy.domain.company.entity.CompanyAlias;
import com.briefy.domain.company.entity.CompanySource;
import com.briefy.domain.company.repository.CompanyAliasRepository;
import com.briefy.domain.company.repository.CompanyRepository;
import com.briefy.domain.company.repository.CompanySourceRepository;
import com.briefy.domain.company.service.CompanyNameNormalizer;
import com.briefy.global.exception.BusinessException;
import com.briefy.global.exception.ErrorCode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DailyCollectionServiceTest {

  @Mock private CollectionJobService collectionJobService;
  @Mock private UserBriefingPreferenceRepository userBriefingPreferenceRepository;
  @Mock private AgentClient agentClient;
  @Mock private CandidatePoolService candidatePoolService;
  @Mock private CompanyRepository companyRepository;
  @Mock private CompanyAliasRepository companyAliasRepository;
  @Mock private CompanySourceRepository companySourceRepository;

  private static final CollectionProperties DEFAULT_COLLECTION_PROPS =
      new CollectionProperties(7, 300, 100, 100, 500);

  private final CompanyNameNormalizer normalizer = new CompanyNameNormalizer();

  private DailyCollectionService dailyCollectionService;

  private static final LocalDate TEST_DATE = LocalDate.of(2026, 6, 30);

  @BeforeEach
  void setUp() {
    dailyCollectionService =
        new DailyCollectionService(
            collectionJobService,
            userBriefingPreferenceRepository,
            agentClient,
            candidatePoolService,
            companyRepository,
            companyAliasRepository,
            companySourceRepository,
            normalizer,
            DEFAULT_COLLECTION_PROPS);
    when(companyRepository.findActiveByNormalizedNames(any())).thenReturn(List.of());
    when(companyAliasRepository.findAllByNormalizedAliasIn(any())).thenReturn(List.of());
    when(companySourceRepository.findActiveByCompanyIds(any(), any())).thenReturn(List.of());
  }

  private CollectionJob pendingJob() {
    return CollectionJob.createPending(
        TEST_DATE, "[\"JOB_POSTING\"]", CollectionTriggerType.MANUAL);
  }

  private AgentCollectedJobPosting samplePosting() {
    return new AgentCollectedJobPosting(
        "원티드",
        "https://www.wanted.co.kr/wd/00123",
        "네이버",
        "네이버 — 백엔드 개발자",
        "백엔드 개발자",
        "정규직",
        "신입",
        "서울",
        "2026-07-15",
        List.of("Spring Boot", "Java"),
        List.of("백엔드 개발자"),
        "채용 공고 설명",
        "2026-06-30T09:00:00",
        "a".repeat(64),
        null,
        null,
        null);
  }

  private AgentCollectionResponse agentResponse(List<AgentCollectedJobPosting> postings) {
    int count = postings.size();
    return new AgentCollectionResponse(
        null,
        TEST_DATE.toString(),
        postings,
        List.of(),
        List.of(),
        new AgentCollectionStats(count, count, count, 0, 0, 0, count),
        List.of());
  }

  // ── helper mocks ─────────────────────────────────────────────────────────────

  private Company mockCompany(long id, String canonicalName, String normalizedName) {
    Company c = mock(Company.class);
    when(c.getId()).thenReturn(id);
    when(c.getCanonicalName()).thenReturn(canonicalName);
    when(c.getNormalizedName()).thenReturn(normalizedName);
    when(c.getCompanySize()).thenReturn(null);
    when(c.getIndustryCodes()).thenReturn(null);
    return c;
  }

  private CompanyAlias mockAlias(Company company, String normalizedAlias) {
    CompanyAlias a = mock(CompanyAlias.class);
    when(a.getCompany()).thenReturn(company);
    when(a.getNormalizedAlias()).thenReturn(normalizedAlias);
    return a;
  }

  // ── core collection flow ──────────────────────────────────────────────────────

  @Test
  void triggerDailyCollection_success_returnsCompletedResult() {
    CollectionJob job = pendingJob();
    when(collectionJobService.createPending(any(), any(), any())).thenReturn(job);
    when(userBriefingPreferenceRepository.findAllByCategoryCodeAndActiveTrue(
            BriefingCategoryCode.JOB_POSTING))
        .thenReturn(List.of());
    when(agentClient.triggerDailyCollection(any()))
        .thenReturn(agentResponse(List.of(samplePosting())));
    when(candidatePoolService.upsertJobPostings(any(), any()))
        .thenReturn(new CandidatePoolUpsertResult(1, 1, 0));

    DailyCollectionResult result =
        dailyCollectionService.triggerDailyCollection(TEST_DATE, List.of("JOB_POSTING"));

    assertThat(result.status()).isEqualTo("COMPLETED");
    assertThat(result.agentStats()).isNotNull();
    assertThat(result.agentStats().finalCount()).isEqualTo(1);
    assertThat(result.savedCount()).isEqualTo(1);
    assertThat(result.persistenceDuplicateCount()).isEqualTo(0);
    assertThat(result.errorMessage()).isNull();
    verify(collectionJobService).markCompleted(any(), anyInt(), anyInt(), anyInt());
  }

  @Test
  void triggerDailyCollection_agentThrows_returnsFailedResultAndMarksFailed() {
    CollectionJob job = pendingJob();
    when(collectionJobService.createPending(any(), any(), any())).thenReturn(job);
    when(userBriefingPreferenceRepository.findAllByCategoryCodeAndActiveTrue(
            BriefingCategoryCode.JOB_POSTING))
        .thenReturn(List.of());
    when(agentClient.triggerDailyCollection(any()))
        .thenThrow(new BusinessException(ErrorCode.AGENT_SERVER_ERROR));

    DailyCollectionResult result =
        dailyCollectionService.triggerDailyCollection(TEST_DATE, List.of("JOB_POSTING"));

    assertThat(result.status()).isEqualTo("FAILED");
    assertThat(result.errorMessage()).isNotNull();
    assertThat(result.agentStats()).isNull();
    assertThat(result.savedCount()).isEqualTo(0);
    verify(collectionJobService).markFailed(any(), any());
    verify(candidatePoolService, never()).upsertJobPostings(any(), any());
  }

  @Test
  void triggerDailyCollection_aggregatesSeedKeywordsFromActivePreferences() {
    CollectionJob job = pendingJob();
    when(collectionJobService.createPending(any(), any(), any())).thenReturn(job);

    UserBriefingPreference mockPref = mock(UserBriefingPreference.class);
    when(mockPref.getPreference())
        .thenReturn(
            Map.of(
                "roles", List.of("백엔드 개발자"),
                "companies", List.of("네이버", "카카오"),
                "skills", List.of("Spring Boot")));
    when(userBriefingPreferenceRepository.findAllByCategoryCodeAndActiveTrue(
            BriefingCategoryCode.JOB_POSTING))
        .thenReturn(List.of(mockPref));

    ArgumentCaptor<AgentCollectionRequest> captor =
        ArgumentCaptor.forClass(AgentCollectionRequest.class);
    when(agentClient.triggerDailyCollection(captor.capture())).thenReturn(agentResponse(List.of()));
    when(candidatePoolService.upsertJobPostings(any(), any()))
        .thenReturn(new CandidatePoolUpsertResult(0, 0, 0));

    dailyCollectionService.triggerDailyCollection(TEST_DATE, List.of("JOB_POSTING"));

    AgentCollectionRequest sent = captor.getValue();
    assertThat(sent.seedKeywords().roles()).containsExactly("백엔드 개발자");
    assertThat(sent.seedKeywords().companies()).containsExactlyInAnyOrder("네이버", "카카오");
    assertThat(sent.seedKeywords().skills()).containsExactly("Spring Boot");
  }

  @Test
  void triggerDailyCollection_callsCandidatePoolServiceWithMappedPostings() {
    CollectionJob job = pendingJob();
    when(collectionJobService.createPending(any(), any(), any())).thenReturn(job);
    when(userBriefingPreferenceRepository.findAllByCategoryCodeAndActiveTrue(any()))
        .thenReturn(List.of());

    AgentCollectedJobPosting posting = samplePosting();
    when(agentClient.triggerDailyCollection(any())).thenReturn(agentResponse(List.of(posting)));

    ArgumentCaptor<List<CollectedJobPostingData>> postingCaptor =
        ArgumentCaptor.forClass(List.class);
    when(candidatePoolService.upsertJobPostings(postingCaptor.capture(), eq(TEST_DATE)))
        .thenReturn(new CandidatePoolUpsertResult(1, 1, 0));

    dailyCollectionService.triggerDailyCollection(TEST_DATE, List.of("JOB_POSTING"));

    List<CollectedJobPostingData> captured = postingCaptor.getValue();
    assertThat(captured).hasSize(1);
    assertThat(captured.get(0).url()).isEqualTo(posting.sourceUrl());
    assertThat(captured.get(0).company()).isEqualTo(posting.companyName());
    assertThat(captured.get(0).title()).isEqualTo(posting.title());
  }

  @Test
  void triggerDailyCollection_nullCategories_defaultsToJobPosting() {
    CollectionJob job = pendingJob();
    when(collectionJobService.createPending(any(), any(), any())).thenReturn(job);
    when(userBriefingPreferenceRepository.findAllByCategoryCodeAndActiveTrue(
            BriefingCategoryCode.JOB_POSTING))
        .thenReturn(List.of());

    ArgumentCaptor<AgentCollectionRequest> captor =
        ArgumentCaptor.forClass(AgentCollectionRequest.class);
    when(agentClient.triggerDailyCollection(captor.capture())).thenReturn(agentResponse(List.of()));
    when(candidatePoolService.upsertJobPostings(any(), any()))
        .thenReturn(new CandidatePoolUpsertResult(0, 0, 0));

    dailyCollectionService.triggerDailyCollection(TEST_DATE, null);

    assertThat(captor.getValue().categories()).containsExactly("JOB_POSTING");
  }

  @Test
  void triggerScheduledDailyCollection_alwaysExecutes() {
    CollectionJob job = pendingJob();
    when(collectionJobService.createPending(any(), any(), any())).thenReturn(job);
    when(userBriefingPreferenceRepository.findAllByCategoryCodeAndActiveTrue(
            BriefingCategoryCode.JOB_POSTING))
        .thenReturn(List.of());
    when(agentClient.triggerDailyCollection(any())).thenReturn(agentResponse(List.of()));
    when(candidatePoolService.upsertJobPostings(any(), any()))
        .thenReturn(new CandidatePoolUpsertResult(0, 0, 0));

    DailyCollectionResult result =
        dailyCollectionService.triggerScheduledDailyCollection(TEST_DATE);

    assertThat(result.status()).isEqualTo("COMPLETED");
    verify(collectionJobService)
        .createPending(eq(TEST_DATE), any(), eq(CollectionTriggerType.SCHEDULED));
  }

  @Test
  void aggregateSeedKeywords_oldSixFieldPreference_newFieldsDefaultToEmpty() {
    CollectionJob job = pendingJob();
    when(collectionJobService.createPending(any(), any(), any())).thenReturn(job);

    UserBriefingPreference mockPref = mock(UserBriefingPreference.class);
    when(mockPref.getPreference())
        .thenReturn(
            Map.of(
                "roles", List.of("백엔드 개발자"),
                "companies", List.of("네이버"),
                "skills", List.of("Spring Boot"),
                "locations", List.of("서울"),
                "experienceLevels", List.of("신입"),
                "employmentTypes", List.of("정규직")));
    when(userBriefingPreferenceRepository.findAllByCategoryCodeAndActiveTrue(
            BriefingCategoryCode.JOB_POSTING))
        .thenReturn(List.of(mockPref));

    ArgumentCaptor<AgentCollectionRequest> captor =
        ArgumentCaptor.forClass(AgentCollectionRequest.class);
    when(agentClient.triggerDailyCollection(captor.capture())).thenReturn(agentResponse(List.of()));
    when(candidatePoolService.upsertJobPostings(any(), any()))
        .thenReturn(new CandidatePoolUpsertResult(0, 0, 0));

    dailyCollectionService.triggerDailyCollection(TEST_DATE, List.of("JOB_POSTING"));

    AgentCollectionRequest sent = captor.getValue();
    assertThat(sent.seedKeywords().companySizes()).isEmpty();
    assertThat(sent.seedKeywords().industries()).isEmpty();
    assertThat(sent.seedKeywords().roles()).containsExactly("백엔드 개발자");
  }

  @Test
  void aggregateSeedKeywords_newEightFieldPreference_companySizesAndIndustriesAggregated() {
    CollectionJob job = pendingJob();
    when(collectionJobService.createPending(any(), any(), any())).thenReturn(job);

    UserBriefingPreference mockPref = mock(UserBriefingPreference.class);
    when(mockPref.getPreference())
        .thenReturn(
            Map.of(
                "roles", List.of("백엔드 개발자"),
                "companies", List.of("네이버"),
                "companySizes", List.of("대기업", "중견기업"),
                "industries", List.of("IT/소프트웨어"),
                "skills", List.of("Spring Boot"),
                "locations", List.of("서울"),
                "experienceLevels", List.of("신입"),
                "employmentTypes", List.of("정규직")));
    when(userBriefingPreferenceRepository.findAllByCategoryCodeAndActiveTrue(
            BriefingCategoryCode.JOB_POSTING))
        .thenReturn(List.of(mockPref));

    ArgumentCaptor<AgentCollectionRequest> captor =
        ArgumentCaptor.forClass(AgentCollectionRequest.class);
    when(agentClient.triggerDailyCollection(captor.capture())).thenReturn(agentResponse(List.of()));
    when(candidatePoolService.upsertJobPostings(any(), any()))
        .thenReturn(new CandidatePoolUpsertResult(0, 0, 0));

    dailyCollectionService.triggerDailyCollection(TEST_DATE, List.of("JOB_POSTING"));

    AgentCollectionRequest sent = captor.getValue();
    assertThat(sent.seedKeywords().companySizes()).containsExactlyInAnyOrder("대기업", "중견기업");
    assertThat(sent.seedKeywords().industries()).containsExactly("IT/소프트웨어");
  }

  @Test
  void aggregateSeedKeywords_deduplicatesCompanySizesAndIndustriesAcrossPreferences() {
    CollectionJob job = pendingJob();
    when(collectionJobService.createPending(any(), any(), any())).thenReturn(job);

    UserBriefingPreference pref1 = mock(UserBriefingPreference.class);
    when(pref1.getPreference())
        .thenReturn(
            Map.of(
                "companySizes", List.of("대기업", "스타트업"),
                "industries", List.of("IT/소프트웨어", "핀테크")));

    UserBriefingPreference pref2 = mock(UserBriefingPreference.class);
    when(pref2.getPreference())
        .thenReturn(
            Map.of(
                "companySizes", List.of("대기업"),
                "industries", List.of("IT/소프트웨어", "게임")));

    when(userBriefingPreferenceRepository.findAllByCategoryCodeAndActiveTrue(
            BriefingCategoryCode.JOB_POSTING))
        .thenReturn(List.of(pref1, pref2));

    ArgumentCaptor<AgentCollectionRequest> captor =
        ArgumentCaptor.forClass(AgentCollectionRequest.class);
    when(agentClient.triggerDailyCollection(captor.capture())).thenReturn(agentResponse(List.of()));
    when(candidatePoolService.upsertJobPostings(any(), any()))
        .thenReturn(new CandidatePoolUpsertResult(0, 0, 0));

    dailyCollectionService.triggerDailyCollection(TEST_DATE, List.of("JOB_POSTING"));

    AgentCollectionRequest sent = captor.getValue();
    assertThat(sent.seedKeywords().companySizes()).containsExactlyInAnyOrder("대기업", "스타트업");
    assertThat(sent.seedKeywords().industries()).containsExactlyInAnyOrder("IT/소프트웨어", "핀테크", "게임");
  }

  @Test
  void triggerDailyCollection_emptyJobPostingsResponse_returnsZeroCounts() {
    CollectionJob job = pendingJob();
    when(collectionJobService.createPending(any(), any(), any())).thenReturn(job);
    when(userBriefingPreferenceRepository.findAllByCategoryCodeAndActiveTrue(any()))
        .thenReturn(List.of());
    when(agentClient.triggerDailyCollection(any())).thenReturn(agentResponse(List.of()));
    when(candidatePoolService.upsertJobPostings(any(), any()))
        .thenReturn(new CandidatePoolUpsertResult(0, 0, 0));

    DailyCollectionResult result =
        dailyCollectionService.triggerDailyCollection(TEST_DATE, List.of("JOB_POSTING"));

    assertThat(result.status()).isEqualTo("COMPLETED");
    assertThat(result.agentStats()).isNotNull();
    assertThat(result.agentStats().finalCount()).isEqualTo(0);
    assertThat(result.savedCount()).isEqualTo(0);
  }

  // ── resolveCompanyProfiles — direct normalized_name match ────────────────────

  @Test
  void resolveCompanyProfiles_matchesCompanyByNormalizedName() {
    CollectionJob job = pendingJob();
    when(collectionJobService.createPending(any(), any(), any())).thenReturn(job);

    UserBriefingPreference pref = mock(UserBriefingPreference.class);
    when(pref.getPreference()).thenReturn(Map.of("companies", List.of("네이버", "카카오")));
    when(userBriefingPreferenceRepository.findAllByCategoryCodeAndActiveTrue(
            BriefingCategoryCode.JOB_POSTING))
        .thenReturn(List.of(pref));

    Company naver = mockCompany(1L, "네이버", "네이버");
    when(companyRepository.findActiveByNormalizedNames(any())).thenReturn(List.of(naver));

    ArgumentCaptor<AgentCollectionRequest> captor =
        ArgumentCaptor.forClass(AgentCollectionRequest.class);
    when(agentClient.triggerDailyCollection(captor.capture())).thenReturn(agentResponse(List.of()));
    when(candidatePoolService.upsertJobPostings(any(), any()))
        .thenReturn(new CandidatePoolUpsertResult(0, 0, 0));

    dailyCollectionService.triggerDailyCollection(TEST_DATE, List.of("JOB_POSTING"));

    AgentCollectionRequest sent = captor.getValue();
    assertThat(sent.companyProfiles()).hasSize(1);
    assertThat(sent.companyProfiles().get(0).id()).isEqualTo(1L);
    assertThat(sent.companyProfiles().get(0).canonicalName()).isEqualTo("네이버");
    assertThat(sent.companyProfiles().get(0).normalizedName()).isEqualTo("네이버");
    assertThat(sent.companyProfiles().get(0).companySize()).isNull();
    assertThat(sent.companyProfiles().get(0).industryCodes()).isEmpty();
  }

  @Test
  void resolveCompanyProfiles_uppercaseInput_normalizesBeforeDirectLookup() {
    CollectionJob job = pendingJob();
    when(collectionJobService.createPending(any(), any(), any())).thenReturn(job);

    UserBriefingPreference pref = mock(UserBriefingPreference.class);
    // user entered "KAKAO" — normalizer converts to "kakao"
    when(pref.getPreference()).thenReturn(Map.of("companies", List.of("KAKAO")));
    when(userBriefingPreferenceRepository.findAllByCategoryCodeAndActiveTrue(
            BriefingCategoryCode.JOB_POSTING))
        .thenReturn(List.of(pref));

    Company kakao = mockCompany(2L, "Kakao", "kakao");
    when(companyRepository.findActiveByNormalizedNames(List.of("kakao")))
        .thenReturn(List.of(kakao));

    ArgumentCaptor<AgentCollectionRequest> captor =
        ArgumentCaptor.forClass(AgentCollectionRequest.class);
    when(agentClient.triggerDailyCollection(captor.capture())).thenReturn(agentResponse(List.of()));
    when(candidatePoolService.upsertJobPostings(any(), any()))
        .thenReturn(new CandidatePoolUpsertResult(0, 0, 0));

    dailyCollectionService.triggerDailyCollection(TEST_DATE, List.of("JOB_POSTING"));

    assertThat(captor.getValue().companyProfiles()).hasSize(1);
    assertThat(captor.getValue().companyProfiles().get(0).id()).isEqualTo(2L);
  }

  @Test
  void resolveCompanyProfiles_leadingTrailingSpaces_normalizesBeforeDirectLookup() {
    CollectionJob job = pendingJob();
    when(collectionJobService.createPending(any(), any(), any())).thenReturn(job);

    UserBriefingPreference pref = mock(UserBriefingPreference.class);
    when(pref.getPreference()).thenReturn(Map.of("companies", List.of("  토스  ")));
    when(userBriefingPreferenceRepository.findAllByCategoryCodeAndActiveTrue(
            BriefingCategoryCode.JOB_POSTING))
        .thenReturn(List.of(pref));

    Company toss = mockCompany(3L, "토스", "토스");
    when(companyRepository.findActiveByNormalizedNames(List.of("토스"))).thenReturn(List.of(toss));

    ArgumentCaptor<AgentCollectionRequest> captor =
        ArgumentCaptor.forClass(AgentCollectionRequest.class);
    when(agentClient.triggerDailyCollection(captor.capture())).thenReturn(agentResponse(List.of()));
    when(candidatePoolService.upsertJobPostings(any(), any()))
        .thenReturn(new CandidatePoolUpsertResult(0, 0, 0));

    dailyCollectionService.triggerDailyCollection(TEST_DATE, List.of("JOB_POSTING"));

    assertThat(captor.getValue().companyProfiles()).hasSize(1);
    assertThat(captor.getValue().companyProfiles().get(0).id()).isEqualTo(3L);
  }

  // ── resolveCompanyProfiles — alias match ──────────────────────────────────────

  @Test
  void resolveCompanyProfiles_aliasMatch_returnsCompanyWhenDirectMatchMisses() {
    CollectionJob job = pendingJob();
    when(collectionJobService.createPending(any(), any(), any())).thenReturn(job);

    UserBriefingPreference pref = mock(UserBriefingPreference.class);
    // user entered Korean alias — direct match will miss, alias match should succeed
    when(pref.getPreference()).thenReturn(Map.of("companies", List.of("비바리퍼블리카")));
    when(userBriefingPreferenceRepository.findAllByCategoryCodeAndActiveTrue(
            BriefingCategoryCode.JOB_POSTING))
        .thenReturn(List.of(pref));

    // direct match returns nothing
    when(companyRepository.findActiveByNormalizedNames(any())).thenReturn(List.of());

    Company toss = mockCompany(3L, "토스", "토스");
    CompanyAlias alias = mockAlias(toss, "비바리퍼블리카");
    when(companyAliasRepository.findAllByNormalizedAliasIn(List.of("비바리퍼블리카")))
        .thenReturn(List.of(alias));

    ArgumentCaptor<AgentCollectionRequest> captor =
        ArgumentCaptor.forClass(AgentCollectionRequest.class);
    when(agentClient.triggerDailyCollection(captor.capture())).thenReturn(agentResponse(List.of()));
    when(candidatePoolService.upsertJobPostings(any(), any()))
        .thenReturn(new CandidatePoolUpsertResult(0, 0, 0));

    dailyCollectionService.triggerDailyCollection(TEST_DATE, List.of("JOB_POSTING"));

    assertThat(captor.getValue().companyProfiles()).hasSize(1);
    assertThat(captor.getValue().companyProfiles().get(0).id()).isEqualTo(3L);
    assertThat(captor.getValue().companyProfiles().get(0).canonicalName()).isEqualTo("토스");
  }

  @Test
  void resolveCompanyProfiles_aliasMatchUppercase_normalizesBeforeAliasLookup() {
    CollectionJob job = pendingJob();
    when(collectionJobService.createPending(any(), any(), any())).thenReturn(job);

    UserBriefingPreference pref = mock(UserBriefingPreference.class);
    // user entered uppercase alias "TOSS"; normalized → "toss" → alias lookup
    when(pref.getPreference()).thenReturn(Map.of("companies", List.of("TOSS")));
    when(userBriefingPreferenceRepository.findAllByCategoryCodeAndActiveTrue(
            BriefingCategoryCode.JOB_POSTING))
        .thenReturn(List.of(pref));

    when(companyRepository.findActiveByNormalizedNames(any())).thenReturn(List.of());

    Company toss = mockCompany(3L, "토스", "토스");
    CompanyAlias alias = mockAlias(toss, "toss");
    when(companyAliasRepository.findAllByNormalizedAliasIn(List.of("toss")))
        .thenReturn(List.of(alias));

    ArgumentCaptor<AgentCollectionRequest> captor =
        ArgumentCaptor.forClass(AgentCollectionRequest.class);
    when(agentClient.triggerDailyCollection(captor.capture())).thenReturn(agentResponse(List.of()));
    when(candidatePoolService.upsertJobPostings(any(), any()))
        .thenReturn(new CandidatePoolUpsertResult(0, 0, 0));

    dailyCollectionService.triggerDailyCollection(TEST_DATE, List.of("JOB_POSTING"));

    assertThat(captor.getValue().companyProfiles()).hasSize(1);
    assertThat(captor.getValue().companyProfiles().get(0).id()).isEqualTo(3L);
  }

  // ── resolveCompanyProfiles — deduplication ────────────────────────────────────

  @Test
  void resolveCompanyProfiles_directAndAliasSameCompany_deduplicatedById() {
    CollectionJob job = pendingJob();
    when(collectionJobService.createPending(any(), any(), any())).thenReturn(job);

    UserBriefingPreference pref = mock(UserBriefingPreference.class);
    // user entered canonical name AND alias for the same company
    when(pref.getPreference()).thenReturn(Map.of("companies", List.of("토스", "비바리퍼블리카")));
    when(userBriefingPreferenceRepository.findAllByCategoryCodeAndActiveTrue(
            BriefingCategoryCode.JOB_POSTING))
        .thenReturn(List.of(pref));

    Company toss = mockCompany(3L, "토스", "토스");
    // direct match finds "토스"
    when(companyRepository.findActiveByNormalizedNames(any())).thenReturn(List.of(toss));

    // alias match also returns the same company via "비바리퍼블리카"
    CompanyAlias alias = mockAlias(toss, "비바리퍼블리카");
    when(companyAliasRepository.findAllByNormalizedAliasIn(any())).thenReturn(List.of(alias));

    ArgumentCaptor<AgentCollectionRequest> captor =
        ArgumentCaptor.forClass(AgentCollectionRequest.class);
    when(agentClient.triggerDailyCollection(captor.capture())).thenReturn(agentResponse(List.of()));
    when(candidatePoolService.upsertJobPostings(any(), any()))
        .thenReturn(new CandidatePoolUpsertResult(0, 0, 0));

    dailyCollectionService.triggerDailyCollection(TEST_DATE, List.of("JOB_POSTING"));

    // must appear exactly once
    assertThat(captor.getValue().companyProfiles()).hasSize(1);
    assertThat(captor.getValue().companyProfiles().get(0).id()).isEqualTo(3L);
  }

  @Test
  void resolveCompanyProfiles_multipleAliasesSameCompany_deduplicatedById() {
    CollectionJob job = pendingJob();
    when(collectionJobService.createPending(any(), any(), any())).thenReturn(job);

    UserBriefingPreference pref = mock(UserBriefingPreference.class);
    when(pref.getPreference()).thenReturn(Map.of("companies", List.of("TOSS", "비바리퍼블리카")));
    when(userBriefingPreferenceRepository.findAllByCategoryCodeAndActiveTrue(
            BriefingCategoryCode.JOB_POSTING))
        .thenReturn(List.of(pref));

    when(companyRepository.findActiveByNormalizedNames(any())).thenReturn(List.of());

    Company toss = mockCompany(3L, "토스", "토스");
    CompanyAlias alias1 = mockAlias(toss, "toss");
    CompanyAlias alias2 = mockAlias(toss, "비바리퍼블리카");
    when(companyAliasRepository.findAllByNormalizedAliasIn(any()))
        .thenReturn(List.of(alias1, alias2));

    ArgumentCaptor<AgentCollectionRequest> captor =
        ArgumentCaptor.forClass(AgentCollectionRequest.class);
    when(agentClient.triggerDailyCollection(captor.capture())).thenReturn(agentResponse(List.of()));
    when(candidatePoolService.upsertJobPostings(any(), any()))
        .thenReturn(new CandidatePoolUpsertResult(0, 0, 0));

    dailyCollectionService.triggerDailyCollection(TEST_DATE, List.of("JOB_POSTING"));

    assertThat(captor.getValue().companyProfiles()).hasSize(1);
    assertThat(captor.getValue().companyProfiles().get(0).id()).isEqualTo(3L);
  }

  // ── resolveCompanyProfiles — unregistered / empty ────────────────────────────

  @Test
  void resolveCompanyProfiles_unregisteredCompany_omittedFromProfiles() {
    CollectionJob job = pendingJob();
    when(collectionJobService.createPending(any(), any(), any())).thenReturn(job);

    UserBriefingPreference pref = mock(UserBriefingPreference.class);
    when(pref.getPreference()).thenReturn(Map.of("companies", List.of("없는회사")));
    when(userBriefingPreferenceRepository.findAllByCategoryCodeAndActiveTrue(
            BriefingCategoryCode.JOB_POSTING))
        .thenReturn(List.of(pref));

    when(companyRepository.findActiveByNormalizedNames(any())).thenReturn(List.of());
    when(companyAliasRepository.findAllByNormalizedAliasIn(any())).thenReturn(List.of());

    ArgumentCaptor<AgentCollectionRequest> captor =
        ArgumentCaptor.forClass(AgentCollectionRequest.class);
    when(agentClient.triggerDailyCollection(captor.capture())).thenReturn(agentResponse(List.of()));
    when(candidatePoolService.upsertJobPostings(any(), any()))
        .thenReturn(new CandidatePoolUpsertResult(0, 0, 0));

    dailyCollectionService.triggerDailyCollection(TEST_DATE, List.of("JOB_POSTING"));

    assertThat(captor.getValue().companyProfiles()).isEmpty();
    assertThat(captor.getValue().officialCompanySources()).isEmpty();
    // unregistered name still in seedKeywords
    assertThat(captor.getValue().seedKeywords().companies()).containsExactly("없는회사");
  }

  @Test
  void resolveCompanyProfiles_emptyCompanies_skipsAllDbQueries() {
    CollectionJob job = pendingJob();
    when(collectionJobService.createPending(any(), any(), any())).thenReturn(job);
    when(userBriefingPreferenceRepository.findAllByCategoryCodeAndActiveTrue(any()))
        .thenReturn(List.of());
    when(agentClient.triggerDailyCollection(any())).thenReturn(agentResponse(List.of()));
    when(candidatePoolService.upsertJobPostings(any(), any()))
        .thenReturn(new CandidatePoolUpsertResult(0, 0, 0));

    dailyCollectionService.triggerDailyCollection(TEST_DATE, List.of("JOB_POSTING"));

    verify(companyRepository, never()).findActiveByNormalizedNames(any());
    verify(companyAliasRepository, never()).findAllByNormalizedAliasIn(any());
    verify(companySourceRepository, never()).findActiveByCompanyIds(any(), any());
  }

  // ── resolveCompanyProfiles — industryCodes parsing (regression) ──────────────

  @Test
  void resolveCompanyProfiles_populatesIndustryCodes() {
    CollectionJob job = pendingJob();
    when(collectionJobService.createPending(any(), any(), any())).thenReturn(job);

    UserBriefingPreference pref = mock(UserBriefingPreference.class);
    when(pref.getPreference()).thenReturn(Map.of("companies", List.of("카카오")));
    when(userBriefingPreferenceRepository.findAllByCategoryCodeAndActiveTrue(
            BriefingCategoryCode.JOB_POSTING))
        .thenReturn(List.of(pref));

    Company kakao = mock(Company.class);
    when(kakao.getId()).thenReturn(2L);
    when(kakao.getCanonicalName()).thenReturn("카카오");
    when(kakao.getNormalizedName()).thenReturn("카카오");
    when(kakao.getCompanySize()).thenReturn(null);
    when(kakao.getIndustryCodes()).thenReturn("IT/소프트웨어, 핀테크 , 게임");
    when(companyRepository.findActiveByNormalizedNames(any())).thenReturn(List.of(kakao));

    ArgumentCaptor<AgentCollectionRequest> captor =
        ArgumentCaptor.forClass(AgentCollectionRequest.class);
    when(agentClient.triggerDailyCollection(captor.capture())).thenReturn(agentResponse(List.of()));
    when(candidatePoolService.upsertJobPostings(any(), any()))
        .thenReturn(new CandidatePoolUpsertResult(0, 0, 0));

    dailyCollectionService.triggerDailyCollection(TEST_DATE, List.of("JOB_POSTING"));

    assertThat(captor.getValue().companyProfiles()).hasSize(1);
    assertThat(captor.getValue().companyProfiles().get(0).industryCodes())
        .containsExactlyInAnyOrder("IT/소프트웨어", "핀테크", "게임");
  }

  // ── resolveCompanyProfiles — seed-realistic alias verification ──────────────

  @Test
  void resolveCompanyProfiles_seedAlias_NAVER_resolvesToNaver() {
    CollectionJob job = pendingJob();
    when(collectionJobService.createPending(any(), any(), any())).thenReturn(job);

    UserBriefingPreference pref = mock(UserBriefingPreference.class);
    // "NAVER" typed by user → normalized 'naver' → alias match → 네이버
    when(pref.getPreference()).thenReturn(Map.of("companies", List.of("NAVER")));
    when(userBriefingPreferenceRepository.findAllByCategoryCodeAndActiveTrue(
            BriefingCategoryCode.JOB_POSTING))
        .thenReturn(List.of(pref));

    when(companyRepository.findActiveByNormalizedNames(any())).thenReturn(List.of());

    Company naver = mock(Company.class);
    when(naver.getId()).thenReturn(1L);
    when(naver.getCanonicalName()).thenReturn("네이버");
    when(naver.getNormalizedName()).thenReturn("네이버");
    when(naver.getCompanySize()).thenReturn("대기업");
    when(naver.getIndustryCodes()).thenReturn("IT/소프트웨어");
    CompanyAlias naverAlias = mockAlias(naver, "naver");
    when(companyAliasRepository.findAllByNormalizedAliasIn(List.of("naver")))
        .thenReturn(List.of(naverAlias));

    ArgumentCaptor<AgentCollectionRequest> captor =
        ArgumentCaptor.forClass(AgentCollectionRequest.class);
    when(agentClient.triggerDailyCollection(captor.capture())).thenReturn(agentResponse(List.of()));
    when(candidatePoolService.upsertJobPostings(any(), any()))
        .thenReturn(new CandidatePoolUpsertResult(0, 0, 0));

    dailyCollectionService.triggerDailyCollection(TEST_DATE, List.of("JOB_POSTING"));

    assertThat(captor.getValue().companyProfiles()).hasSize(1);
    assertThat(captor.getValue().companyProfiles().get(0).id()).isEqualTo(1L);
    assertThat(captor.getValue().companyProfiles().get(0).canonicalName()).isEqualTo("네이버");
    assertThat(captor.getValue().companyProfiles().get(0).companySize()).isEqualTo("대기업");
    assertThat(captor.getValue().companyProfiles().get(0).industryCodes())
        .containsExactly("IT/소프트웨어");
  }

  @Test
  void resolveCompanyProfiles_seedAlias_배달의민족_resolvesToUahanBrothers() {
    CollectionJob job = pendingJob();
    when(collectionJobService.createPending(any(), any(), any())).thenReturn(job);

    UserBriefingPreference pref = mock(UserBriefingPreference.class);
    // "배달의민족" typed by user → alias match → 우아한형제들
    when(pref.getPreference()).thenReturn(Map.of("companies", List.of("배달의민족")));
    when(userBriefingPreferenceRepository.findAllByCategoryCodeAndActiveTrue(
            BriefingCategoryCode.JOB_POSTING))
        .thenReturn(List.of(pref));

    when(companyRepository.findActiveByNormalizedNames(any())).thenReturn(List.of());

    Company woowa = mockCompany(12L, "우아한형제들", "우아한형제들");
    CompanyAlias baedalAlias = mockAlias(woowa, "배달의민족");
    when(companyAliasRepository.findAllByNormalizedAliasIn(List.of("배달의민족")))
        .thenReturn(List.of(baedalAlias));

    ArgumentCaptor<AgentCollectionRequest> captor =
        ArgumentCaptor.forClass(AgentCollectionRequest.class);
    when(agentClient.triggerDailyCollection(captor.capture())).thenReturn(agentResponse(List.of()));
    when(candidatePoolService.upsertJobPostings(any(), any()))
        .thenReturn(new CandidatePoolUpsertResult(0, 0, 0));

    dailyCollectionService.triggerDailyCollection(TEST_DATE, List.of("JOB_POSTING"));

    assertThat(captor.getValue().companyProfiles()).hasSize(1);
    assertThat(captor.getValue().companyProfiles().get(0).id()).isEqualTo(12L);
    assertThat(captor.getValue().companyProfiles().get(0).canonicalName()).isEqualTo("우아한형제들");
  }

  @Test
  void resolveCompanyProfiles_seedCompanySize_propagatesToProfile() {
    CollectionJob job = pendingJob();
    when(collectionJobService.createPending(any(), any(), any())).thenReturn(job);

    UserBriefingPreference pref = mock(UserBriefingPreference.class);
    when(pref.getPreference()).thenReturn(Map.of("companies", List.of("삼성전자")));
    when(userBriefingPreferenceRepository.findAllByCategoryCodeAndActiveTrue(
            BriefingCategoryCode.JOB_POSTING))
        .thenReturn(List.of(pref));

    Company samsung = mock(Company.class);
    when(samsung.getId()).thenReturn(10L);
    when(samsung.getCanonicalName()).thenReturn("삼성전자");
    when(samsung.getNormalizedName()).thenReturn("삼성전자");
    when(samsung.getCompanySize()).thenReturn("대기업");
    when(samsung.getIndustryCodes()).thenReturn("IT/소프트웨어");
    when(companyRepository.findActiveByNormalizedNames(List.of("삼성전자")))
        .thenReturn(List.of(samsung));

    ArgumentCaptor<AgentCollectionRequest> captor =
        ArgumentCaptor.forClass(AgentCollectionRequest.class);
    when(agentClient.triggerDailyCollection(captor.capture())).thenReturn(agentResponse(List.of()));
    when(candidatePoolService.upsertJobPostings(any(), any()))
        .thenReturn(new CandidatePoolUpsertResult(0, 0, 0));

    dailyCollectionService.triggerDailyCollection(TEST_DATE, List.of("JOB_POSTING"));

    assertThat(captor.getValue().companyProfiles()).hasSize(1);
    assertThat(captor.getValue().companyProfiles().get(0).companySize()).isEqualTo("대기업");
    assertThat(captor.getValue().companyProfiles().get(0).industryCodes())
        .containsExactly("IT/소프트웨어");
  }

  // ── resolveOfficialSources (regression) ──────────────────────────────────────

  @Test
  void resolveOfficialSources_loadsActiveSourcesForMatchedCompanies() {
    CollectionJob job = pendingJob();
    when(collectionJobService.createPending(any(), any(), any())).thenReturn(job);

    UserBriefingPreference pref = mock(UserBriefingPreference.class);
    when(pref.getPreference()).thenReturn(Map.of("companies", List.of("카카오")));
    when(userBriefingPreferenceRepository.findAllByCategoryCodeAndActiveTrue(
            BriefingCategoryCode.JOB_POSTING))
        .thenReturn(List.of(pref));

    Company kakao = mockCompany(2L, "카카오", "카카오");
    when(companyRepository.findActiveByNormalizedNames(any())).thenReturn(List.of(kakao));

    CompanySource source = mock(CompanySource.class);
    when(source.getCompany()).thenReturn(kakao);
    when(source.getSourceType()).thenReturn("CAREERS_PAGE");
    when(source.getSourceUrl()).thenReturn("https://careers.kakao.com/sitemap.xml");
    when(source.getAdapterType()).thenReturn("SITEMAP");
    when(source.getConfigJson()).thenReturn(null);
    when(companySourceRepository.findActiveByCompanyIds(List.of(2L), "ACTIVE"))
        .thenReturn(List.of(source));

    ArgumentCaptor<AgentCollectionRequest> captor =
        ArgumentCaptor.forClass(AgentCollectionRequest.class);
    when(agentClient.triggerDailyCollection(captor.capture())).thenReturn(agentResponse(List.of()));
    when(candidatePoolService.upsertJobPostings(any(), any()))
        .thenReturn(new CandidatePoolUpsertResult(0, 0, 0));

    dailyCollectionService.triggerDailyCollection(TEST_DATE, List.of("JOB_POSTING"));

    AgentCollectionRequest sent = captor.getValue();
    assertThat(sent.officialCompanySources()).hasSize(1);
    assertThat(sent.officialCompanySources().get(0).companyId()).isEqualTo(2L);
    assertThat(sent.officialCompanySources().get(0).sourceUrl())
        .isEqualTo("https://careers.kakao.com/sitemap.xml");
    assertThat(sent.officialCompanySources().get(0).adapterType()).isEqualTo("SITEMAP");
  }

  @Test
  void resolveOfficialSources_emptyCompanyList_skipsDbQuery() {
    CollectionJob job = pendingJob();
    when(collectionJobService.createPending(any(), any(), any())).thenReturn(job);
    when(userBriefingPreferenceRepository.findAllByCategoryCodeAndActiveTrue(any()))
        .thenReturn(List.of());
    when(agentClient.triggerDailyCollection(any())).thenReturn(agentResponse(List.of()));
    when(candidatePoolService.upsertJobPostings(any(), any()))
        .thenReturn(new CandidatePoolUpsertResult(0, 0, 0));

    dailyCollectionService.triggerDailyCollection(TEST_DATE, List.of("JOB_POSTING"));

    verify(companySourceRepository, never()).findActiveByCompanyIds(any(), any());
  }

  // ── CollectionOptions contract ───────────────────────────────────────────────

  @Test
  void triggerDailyCollection_sendsAllFiveOptionFields() {
    CollectionProperties props = new CollectionProperties(5, 200, 80, 80, 400);
    DailyCollectionService svc =
        new DailyCollectionService(
            collectionJobService,
            userBriefingPreferenceRepository,
            agentClient,
            candidatePoolService,
            companyRepository,
            companyAliasRepository,
            companySourceRepository,
            normalizer,
            props);

    CollectionJob job = pendingJob();
    when(collectionJobService.createPending(any(), any(), any())).thenReturn(job);
    when(userBriefingPreferenceRepository.findAllByCategoryCodeAndActiveTrue(any()))
        .thenReturn(List.of());

    ArgumentCaptor<AgentCollectionRequest> captor =
        ArgumentCaptor.forClass(AgentCollectionRequest.class);
    when(agentClient.triggerDailyCollection(captor.capture())).thenReturn(agentResponse(List.of()));
    when(candidatePoolService.upsertJobPostings(any(), any()))
        .thenReturn(new CandidatePoolUpsertResult(0, 0, 0));

    svc.triggerDailyCollection(TEST_DATE, List.of("JOB_POSTING"));

    AgentCollectionOptions opts = captor.getValue().options();
    assertThat(opts.lookbackDays()).isEqualTo(5);
    assertThat(opts.discoveryLimitPerSource()).isEqualTo(200);
    assertThat(opts.detailFetchLimitPerSource()).isEqualTo(80);
    assertThat(opts.maxResultsPerSource()).isEqualTo(80);
    assertThat(opts.maxTotalResults()).isEqualTo(400);
  }

  @Test
  void triggerDailyCollection_agentStats_populatedInResult() {
    CollectionJob job = pendingJob();
    when(collectionJobService.createPending(any(), any(), any())).thenReturn(job);
    when(userBriefingPreferenceRepository.findAllByCategoryCodeAndActiveTrue(any()))
        .thenReturn(List.of());

    AgentCollectionStats stats = new AgentCollectionStats(100, 50, 45, 5, 2, 10, 28);
    AgentCollectionResponse resp =
        new AgentCollectionResponse(
            null, TEST_DATE.toString(), List.of(), List.of(), List.of(), stats, List.of("warn1"));
    when(agentClient.triggerDailyCollection(any())).thenReturn(resp);
    when(candidatePoolService.upsertJobPostings(any(), any()))
        .thenReturn(new CandidatePoolUpsertResult(20, 20, 8));

    DailyCollectionResult result =
        dailyCollectionService.triggerDailyCollection(TEST_DATE, List.of("JOB_POSTING"));

    assertThat(result.status()).isEqualTo("COMPLETED");
    assertThat(result.agentStats().discoveredCount()).isEqualTo(100);
    assertThat(result.agentStats().fetchedCount()).isEqualTo(50);
    assertThat(result.agentStats().parsedCount()).isEqualTo(45);
    assertThat(result.agentStats().duplicateCount()).isEqualTo(5);
    assertThat(result.agentStats().filteredCount()).isEqualTo(2);
    assertThat(result.agentStats().truncatedCount()).isEqualTo(10);
    assertThat(result.agentStats().finalCount()).isEqualTo(28);
    assertThat(result.savedCount()).isEqualTo(20);
    assertThat(result.persistenceDuplicateCount()).isEqualTo(8);
    assertThat(result.warnings()).containsExactly("warn1");
  }
}
