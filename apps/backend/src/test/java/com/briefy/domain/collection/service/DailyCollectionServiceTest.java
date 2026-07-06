package com.briefy.domain.collection.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.briefy.domain.briefing.client.AgentClient;
import com.briefy.domain.briefing.client.dto.AgentCollectedJobPosting;
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
import com.briefy.domain.company.entity.CompanySource;
import com.briefy.domain.company.repository.CompanyRepository;
import com.briefy.domain.company.repository.CompanySourceRepository;
import com.briefy.global.exception.BusinessException;
import com.briefy.global.exception.ErrorCode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
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
  @Mock private CompanySourceRepository companySourceRepository;

  @InjectMocks private DailyCollectionService dailyCollectionService;

  private static final LocalDate TEST_DATE = LocalDate.of(2026, 6, 30);

  @BeforeEach
  void setUpCompanyRepos() {
    when(companyRepository.findActiveByNormalizedNames(any())).thenReturn(List.of());
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
        "a".repeat(64));
  }

  private AgentCollectionResponse agentResponse(List<AgentCollectedJobPosting> postings) {
    int count = postings.size();
    return new AgentCollectionResponse(
        null,
        TEST_DATE.toString(),
        postings,
        List.of(),
        List.of(),
        new AgentCollectionStats(count, 0, count, 0, 0),
        List.of());
  }

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
    assertThat(result.collectedCount()).isEqualTo(1);
    assertThat(result.savedCount()).isEqualTo(1);
    assertThat(result.deduplicatedCount()).isEqualTo(0);
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
    assertThat(result.collectedCount()).isEqualTo(0);
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
  void triggerScheduledDailyCollection_skipsWhenAlreadyActive() {
    when(collectionJobService.isAlreadyActiveForDate(TEST_DATE)).thenReturn(true);

    DailyCollectionResult result =
        dailyCollectionService.triggerScheduledDailyCollection(TEST_DATE);

    assertThat(result.status()).isEqualTo("SKIPPED");
    assertThat(result.collectionJobId()).isNull();
    verify(collectionJobService, never()).createPending(any(), any(), any());
  }

  @Test
  void triggerScheduledDailyCollection_executesWhenNotActive() {
    when(collectionJobService.isAlreadyActiveForDate(TEST_DATE)).thenReturn(false);
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
    assertThat(result.collectedCount()).isEqualTo(0);
    assertThat(result.savedCount()).isEqualTo(0);
  }

  @Test
  void resolveCompanyProfiles_matchesCompanyByNormalizedName() {
    CollectionJob job = pendingJob();
    when(collectionJobService.createPending(any(), any(), any())).thenReturn(job);

    UserBriefingPreference pref = mock(UserBriefingPreference.class);
    when(pref.getPreference()).thenReturn(Map.of("companies", List.of("네이버", "카카오")));
    when(userBriefingPreferenceRepository.findAllByCategoryCodeAndActiveTrue(
            BriefingCategoryCode.JOB_POSTING))
        .thenReturn(List.of(pref));

    Company naver = mock(Company.class);
    when(naver.getId()).thenReturn(1L);
    when(naver.getCanonicalName()).thenReturn("네이버");
    when(naver.getNormalizedName()).thenReturn("네이버");
    when(naver.getCompanySize()).thenReturn("대기업");
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
    assertThat(sent.companyProfiles().get(0).companySize()).isEqualTo("대기업");
  }

  @Test
  void resolveCompanyProfiles_noMatchingCompanies_sendsEmptyList() {
    CollectionJob job = pendingJob();
    when(collectionJobService.createPending(any(), any(), any())).thenReturn(job);

    UserBriefingPreference pref = mock(UserBriefingPreference.class);
    when(pref.getPreference()).thenReturn(Map.of("companies", List.of("없는회사")));
    when(userBriefingPreferenceRepository.findAllByCategoryCodeAndActiveTrue(
            BriefingCategoryCode.JOB_POSTING))
        .thenReturn(List.of(pref));

    when(companyRepository.findActiveByNormalizedNames(any())).thenReturn(List.of());

    ArgumentCaptor<AgentCollectionRequest> captor =
        ArgumentCaptor.forClass(AgentCollectionRequest.class);
    when(agentClient.triggerDailyCollection(captor.capture())).thenReturn(agentResponse(List.of()));
    when(candidatePoolService.upsertJobPostings(any(), any()))
        .thenReturn(new CandidatePoolUpsertResult(0, 0, 0));

    dailyCollectionService.triggerDailyCollection(TEST_DATE, List.of("JOB_POSTING"));

    assertThat(captor.getValue().companyProfiles()).isEmpty();
    assertThat(captor.getValue().officialCompanySources()).isEmpty();
  }

  @Test
  void resolveOfficialSources_loadsActiveSourcesForMatchedCompanies() {
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
}
