package com.briefy.domain.briefing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.briefy.config.BriefingProperties;
import com.briefy.domain.briefing.dto.BriefingDetailResponse;
import com.briefy.domain.briefing.dto.BriefingListItem;
import com.briefy.domain.briefing.dto.GenerateResult;
import com.briefy.domain.briefing.entity.BriefingJob;
import com.briefy.domain.briefing.entity.BriefingReport;
import com.briefy.domain.briefing.repository.BriefingArticleRepository;
import com.briefy.domain.briefing.repository.BriefingJobRepository;
import com.briefy.domain.briefing.repository.BriefingReportRepository;
import com.briefy.domain.candidatepool.entity.JobPosting;
import com.briefy.domain.candidatepool.service.CandidatePoolService;
import com.briefy.domain.company.entity.Company;
import com.briefy.domain.preference.entity.BriefingCategory;
import com.briefy.domain.preference.entity.BriefingCategoryCode;
import com.briefy.domain.preference.entity.UserBriefingPreference;
import com.briefy.domain.preference.repository.UserBriefingPreferenceRepository;
import com.briefy.domain.user.repository.UserRepository;
import com.briefy.global.exception.BusinessException;
import com.briefy.global.exception.ErrorCode;
import com.briefy.global.response.PageResult;
import com.briefy.infra.agent.AgentClient;
import com.briefy.infra.agent.dto.AgentBriefingRequest;
import com.briefy.infra.agent.dto.AgentBriefingResponse;
import com.briefy.infra.agent.dto.AgentCandidateJobPosting;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BriefingServiceTest {

  @Mock private BriefingJobRepository briefingJobRepository;
  @Mock private BriefingReportRepository briefingReportRepository;
  @Mock private BriefingArticleRepository briefingArticleRepository;
  @Mock private UserBriefingPreferenceRepository userBriefingPreferenceRepository;
  @Mock private AgentClient agentClient;
  @Mock private CandidatePoolService candidatePoolService;
  @Mock private UserRepository userRepository;
  @Mock private BriefingJobPersistenceService briefingJobPersistenceService;
  @Mock private BriefingProperties briefingProperties;

  @InjectMocks private BriefingService briefingService;

  private UserBriefingPreference mockPref;
  private AgentBriefingResponse mockAgentResponse;

  @BeforeEach
  void setUp() {
    when(briefingArticleRepository.findRecentExposuresByUserId(any(), any())).thenReturn(List.of());

    // 기본값: 오늘 브리핑 없음, Job 생성 성공, claimForProcessing 성공
    when(briefingJobPersistenceService.findTodayReport(any(), any()))
        .thenReturn(java.util.Optional.empty());
    when(briefingJobPersistenceService.createOrGet(any(BriefingJob.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(briefingJobPersistenceService.claimForProcessing(any())).thenReturn(true);
    // 기본 Job findById: PENDING job 반환 (buildReport에서 사용)
    when(briefingJobRepository.findById(any()))
        .thenAnswer(
            inv -> java.util.Optional.of(BriefingJob.createManual(1L, java.time.LocalDate.now())));
    // 기본 saveReportAndComplete (4-arg): 두 번째 인수(report)를 그대로 반환
    when(briefingJobPersistenceService.saveReportAndComplete(
            any(), any(BriefingReport.class), any(), any()))
        .thenAnswer(inv -> inv.getArgument(1));
    // BriefingProperties defaults
    when(briefingProperties.agentRetryMaxAttempts()).thenReturn(0);
    when(briefingProperties.agentRetryBackoffSeconds()).thenReturn(0);
    when(briefingProperties.jobMaxRetryCount()).thenReturn(3);

    BriefingCategory category = mock(BriefingCategory.class);
    when(category.getCode()).thenReturn(BriefingCategoryCode.JOB_POSTING);

    mockPref = mock(UserBriefingPreference.class);
    when(mockPref.getCategory()).thenReturn(category);
    when(mockPref.getPreference()).thenReturn(Map.of("roles", List.of("백엔드 개발자")));

    mockAgentResponse =
        new AgentBriefingResponse(
            "오늘의 채용 브리핑",
            "채용 공고 1건이 매칭되었습니다.",
            "## 신규 공고\n...",
            List.of(
                new AgentBriefingResponse.AgentArticle(
                    "네이버 백엔드 개발자",
                    "채용 플랫폼",
                    "https://example.com/job/1",
                    "네이버 백엔드 개발자 공고",
                    "목표 회사와 스킬이 매칭됩니다.",
                    "2026-06-26T00:00:00")),
            new AgentBriefingResponse.TokenUsage(1000, 500),
            null,
            null,
            null,
            null);
  }

  // ── Core generate flow ───────────────────────────────────────────────────

  @Test
  void generateBriefing_success_returnsCompletedResult() {
    when(userBriefingPreferenceRepository.findAllByUserIdAndActiveTrue(1L))
        .thenReturn(List.of(mockPref));
    when(agentClient.generate(
            any(AgentBriefingRequest.class), any(Integer.class), any(Integer.class)))
        .thenReturn(mockAgentResponse);

    BriefingJob stubJob = BriefingJob.createManual(1L, java.time.LocalDate.now());
    when(briefingJobPersistenceService.createOrGet(any(BriefingJob.class))).thenReturn(stubJob);
    when(briefingJobRepository.findById(any())).thenReturn(java.util.Optional.of(stubJob));

    BriefingReport mockReport = mock(BriefingReport.class);
    when(mockReport.getId()).thenReturn(100L);
    when(briefingJobPersistenceService.saveReportAndComplete(
            any(), any(BriefingReport.class), any(), any()))
        .thenReturn(mockReport);

    GenerateResult result = briefingService.generateBriefing(1L);

    assertThat(result.briefingReportId()).isEqualTo(100L);
    assertThat(result.status()).isEqualTo("COMPLETED");
    verify(briefingJobPersistenceService)
        .saveReportAndComplete(any(), any(BriefingReport.class), any(), any());
  }

  @Test
  void generateBriefing_agentThrows_marksJobFailedAndRethrows() {
    when(userBriefingPreferenceRepository.findAllByUserIdAndActiveTrue(1L))
        .thenReturn(List.of(mockPref));
    BriefingJob stubJob = BriefingJob.createManual(1L, java.time.LocalDate.now());
    when(briefingJobPersistenceService.createOrGet(any(BriefingJob.class))).thenReturn(stubJob);
    when(briefingJobRepository.findById(any())).thenReturn(java.util.Optional.of(stubJob));
    when(agentClient.generate(
            any(AgentBriefingRequest.class), any(Integer.class), any(Integer.class)))
        .thenThrow(new BusinessException(ErrorCode.AGENT_SERVER_ERROR));

    assertThatThrownBy(() -> briefingService.generateBriefing(1L))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            e ->
                assertThat(((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.AGENT_SERVER_ERROR));

    verify(briefingJobPersistenceService).recordFailure(any(), any());
    verify(briefingJobPersistenceService, never())
        .saveReportAndComplete(any(), any(BriefingReport.class), any(), any());
  }

  // ── List / detail ────────────────────────────────────────────────────────

  @Test
  void listBriefings_returnsPaginatedItems() {
    BriefingReport report = mock(BriefingReport.class);
    when(report.getId()).thenReturn(1L);
    when(report.getTitle()).thenReturn("오늘의 채용 브리핑");
    when(report.getSummary()).thenReturn("요약");
    when(report.getReportDate()).thenReturn(LocalDate.of(2026, 6, 26));
    when(report.getArticleCount()).thenReturn(2);
    when(report.getCreatedAt()).thenReturn(null);

    var page = new PageImpl<>(List.of(report), PageRequest.of(0, 10), 1);
    when(briefingReportRepository.findAllByUserIdOrderByReportDateDesc(eq(1L), any(Pageable.class)))
        .thenReturn(page);

    PageResult<BriefingListItem> result = briefingService.listBriefings(1L, 0, 10);

    assertThat(result.content()).hasSize(1);
    assertThat(result.content().get(0).title()).isEqualTo("오늘의 채용 브리핑");
    assertThat(result.totalElements()).isEqualTo(1L);
    assertThat(result.page()).isEqualTo(0);
  }

  @Test
  void listBriefings_capsPageSizeAt50() {
    Page<BriefingReport> page = new PageImpl<>(List.of(), PageRequest.of(0, 50), 0);
    when(briefingReportRepository.findAllByUserIdOrderByReportDateDesc(eq(1L), any(Pageable.class)))
        .thenReturn(page);

    briefingService.listBriefings(1L, 0, 200);

    verify(briefingReportRepository)
        .findAllByUserIdOrderByReportDateDesc(eq(1L), eq(PageRequest.of(0, 50)));
  }

  @Test
  void getBriefingDetail_success_returnsDetailWithArticles() {
    BriefingReport report = mock(BriefingReport.class);
    when(report.getId()).thenReturn(1L);
    when(report.getUserId()).thenReturn(1L);
    when(report.getTitle()).thenReturn("오늘의 채용 브리핑");
    when(report.getSummary()).thenReturn("요약");
    when(report.getContent()).thenReturn("## 신규 공고\n...");
    when(report.getReportDate()).thenReturn(LocalDate.of(2026, 6, 26));
    when(report.getArticles()).thenReturn(List.of());
    when(briefingReportRepository.findById(1L)).thenReturn(Optional.of(report));

    BriefingDetailResponse response = briefingService.getBriefingDetail(1L, 1L);

    assertThat(response.title()).isEqualTo("오늘의 채용 브리핑");
    assertThat(response.reportDate()).isEqualTo("2026-06-26");
    assertThat(response.articles()).isEmpty();
  }

  @Test
  void getBriefingDetail_notFound_throwsBriefingReportNotFound() {
    when(briefingReportRepository.findById(99L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> briefingService.getBriefingDetail(1L, 99L))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            e ->
                assertThat(((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.BRIEFING_REPORT_NOT_FOUND));
  }

  @Test
  void getBriefingDetail_notOwner_throwsForbidden() {
    BriefingReport report = mock(BriefingReport.class);
    when(report.getUserId()).thenReturn(2L);
    when(briefingReportRepository.findById(1L)).thenReturn(Optional.of(report));

    assertThatThrownBy(() -> briefingService.getBriefingDetail(1L, 1L))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
  }

  // ── Agent request contract ───────────────────────────────────────────────

  @Test
  void generateBriefing_includesCandidatePoolInAgentRequest() {
    when(userBriefingPreferenceRepository.findAllByUserIdAndActiveTrue(1L))
        .thenReturn(List.of(mockPref));

    JobPosting posting = samplePosting("네이버", "백엔드 개발자", "서울", LocalDate.now().plusDays(3));
    when(candidatePoolService.findEligibleJobPostingsForBriefing(any()))
        .thenReturn(List.of(posting));

    ArgumentCaptor<AgentBriefingRequest> captor =
        ArgumentCaptor.forClass(AgentBriefingRequest.class);
    when(agentClient.generate(captor.capture(), any(Integer.class), any(Integer.class)))
        .thenReturn(mockAgentResponse);

    briefingService.generateBriefing(1L);

    var pool = captor.getValue().candidatePool();
    assertThat(pool).isNotNull();
    assertThat(pool.jobPostings()).hasSize(1);
    assertThat(pool.companyIssues()).isEmpty();
    assertThat(pool.industryIssues()).isEmpty();
  }

  @Test
  void generateBriefing_limitsTo7Candidates_whenMoreExist() {
    when(userBriefingPreferenceRepository.findAllByUserIdAndActiveTrue(1L))
        .thenReturn(List.of(mockPref));

    List<JobPosting> postings =
        IntStream.range(0, 20)
            .mapToObj(i -> samplePosting("회사" + i, "백엔드 개발자", "서울", null))
            .toList();
    when(candidatePoolService.findEligibleJobPostingsForBriefing(any())).thenReturn(postings);

    ArgumentCaptor<AgentBriefingRequest> captor =
        ArgumentCaptor.forClass(AgentBriefingRequest.class);
    when(agentClient.generate(captor.capture(), any(Integer.class), any(Integer.class)))
        .thenReturn(mockAgentResponse);

    briefingService.generateBriefing(1L);

    assertThat(captor.getValue().candidatePool().jobPostings()).hasSizeLessThanOrEqualTo(7);
  }

  @Test
  void generateBriefing_emptyCandidatePool_doesNotCrash() {
    when(userBriefingPreferenceRepository.findAllByUserIdAndActiveTrue(1L))
        .thenReturn(List.of(mockPref));
    when(candidatePoolService.findEligibleJobPostingsForBriefing(any())).thenReturn(List.of());
    when(agentClient.generate(any(), any(Integer.class), any(Integer.class)))
        .thenReturn(mockAgentResponse);

    GenerateResult result = briefingService.generateBriefing(1L);

    assertThat(result.status()).isEqualTo("COMPLETED");
    verify(agentClient)
        .generate(
            argThat(req -> req.candidatePool().jobPostings().isEmpty()),
            any(Integer.class),
            any(Integer.class));
  }

  // ── New contract: rank, isNew, isUrgent, scoreBreakdown, matchEvidence ──

  @Test
  void candidatePool_rankIsSequentialFrom1() {
    when(userBriefingPreferenceRepository.findAllByUserIdAndActiveTrue(1L))
        .thenReturn(List.of(mockPref));

    List<JobPosting> postings =
        IntStream.range(0, 5)
            .mapToObj(i -> samplePosting("회사" + i, "백엔드 개발자", "서울", null))
            .toList();
    when(candidatePoolService.findEligibleJobPostingsForBriefing(any())).thenReturn(postings);

    ArgumentCaptor<AgentBriefingRequest> captor =
        ArgumentCaptor.forClass(AgentBriefingRequest.class);
    when(agentClient.generate(captor.capture(), any(Integer.class), any(Integer.class)))
        .thenReturn(mockAgentResponse);

    briefingService.generateBriefing(1L);

    List<AgentCandidateJobPosting> candidates = captor.getValue().candidatePool().jobPostings();
    for (int i = 0; i < candidates.size(); i++) {
      assertThat(candidates.get(i).rank()).isEqualTo(i + 1);
    }
  }

  @Test
  void candidatePool_selectorOrderMatchesAgentRequestOrder() {
    // High score (백엔드 role match = +30) should be rank 1; low score should be rank 2.
    when(userBriefingPreferenceRepository.findAllByUserIdAndActiveTrue(1L))
        .thenReturn(List.of(mockPref));

    JobPosting highScore = samplePosting("네이버", "백엔드 개발자", "서울", null);
    JobPosting lowScore = samplePosting("카카오", "Software Engineer", "서울", null);
    when(candidatePoolService.findEligibleJobPostingsForBriefing(any()))
        .thenReturn(List.of(lowScore, highScore));

    ArgumentCaptor<AgentBriefingRequest> captor =
        ArgumentCaptor.forClass(AgentBriefingRequest.class);
    when(agentClient.generate(captor.capture(), any(Integer.class), any(Integer.class)))
        .thenReturn(mockAgentResponse);

    briefingService.generateBriefing(1L);

    List<AgentCandidateJobPosting> candidates = captor.getValue().candidatePool().jobPostings();
    assertThat(candidates).hasSizeGreaterThanOrEqualTo(2);
    assertThat(candidates.get(0).companyName()).isEqualTo("네이버");
    assertThat(candidates.get(0).rank()).isEqualTo(1);
    assertThat(candidates.get(1).companyName()).isEqualTo("카카오");
    assertThat(candidates.get(1).rank()).isEqualTo(2);
  }

  @Test
  void candidatePool_isNewAndIsUrgentBothPossiblyTrue() {
    when(userBriefingPreferenceRepository.findAllByUserIdAndActiveTrue(1L))
        .thenReturn(List.of(mockPref));

    // Collected today (isNew) + deadline in 3 days (isUrgent)
    JobPosting newAndUrgent = samplePosting("토스", "백엔드 개발자", "서울", LocalDate.now().plusDays(3));
    when(candidatePoolService.findEligibleJobPostingsForBriefing(any()))
        .thenReturn(List.of(newAndUrgent));

    ArgumentCaptor<AgentBriefingRequest> captor =
        ArgumentCaptor.forClass(AgentBriefingRequest.class);
    when(agentClient.generate(captor.capture(), any(Integer.class), any(Integer.class)))
        .thenReturn(mockAgentResponse);

    briefingService.generateBriefing(1L);

    AgentCandidateJobPosting c = captor.getValue().candidatePool().jobPostings().get(0);
    assertThat(c.isNew()).isTrue();
    assertThat(c.isUrgent()).isTrue();
  }

  @Test
  void candidatePool_scoreBreakdownPresent() {
    when(userBriefingPreferenceRepository.findAllByUserIdAndActiveTrue(1L))
        .thenReturn(List.of(mockPref));

    when(candidatePoolService.findEligibleJobPostingsForBriefing(any()))
        .thenReturn(List.of(samplePosting("네이버", "백엔드 개발자", "서울", null)));

    ArgumentCaptor<AgentBriefingRequest> captor =
        ArgumentCaptor.forClass(AgentBriefingRequest.class);
    when(agentClient.generate(captor.capture(), any(Integer.class), any(Integer.class)))
        .thenReturn(mockAgentResponse);

    briefingService.generateBriefing(1L);

    AgentCandidateJobPosting c = captor.getValue().candidatePool().jobPostings().get(0);
    assertThat(c.scoreBreakdown()).isNotNull();
    assertThat(c.scoreBreakdown().adjustedScore())
        .isEqualTo(c.scoreBreakdown().relevanceScore() - c.scoreBreakdown().exposurePenalty());
  }

  @Test
  void candidatePool_matchEvidencePresent() {
    when(userBriefingPreferenceRepository.findAllByUserIdAndActiveTrue(1L))
        .thenReturn(List.of(mockPref));

    when(candidatePoolService.findEligibleJobPostingsForBriefing(any()))
        .thenReturn(List.of(samplePosting("네이버", "백엔드 개발자", "서울", null)));

    ArgumentCaptor<AgentBriefingRequest> captor =
        ArgumentCaptor.forClass(AgentBriefingRequest.class);
    when(agentClient.generate(captor.capture(), any(Integer.class), any(Integer.class)))
        .thenReturn(mockAgentResponse);

    briefingService.generateBriefing(1L);

    AgentCandidateJobPosting c = captor.getValue().candidatePool().jobPostings().get(0);
    assertThat(c.matchEvidence()).isNotNull();
    assertThat(c.matchEvidence().matchedRoles()).isNotNull();
    assertThat(c.matchEvidence().matchedSkills()).isNotNull();
  }

  // ── Eligibility filtering ────────────────────────────────────────────────

  @Test
  void selectCandidates_expiredPosting_isFiltered() {
    JobPosting active = samplePosting("네이버", "백엔드 개발자", "서울", LocalDate.now().plusDays(3));
    JobPosting expired = samplePosting("카카오", "백엔드 개발자", "서울", LocalDate.now().minusDays(1));

    List<AgentCandidateJobPosting> candidates = candidatesFor(Map.of(), List.of(active, expired));

    assertThat(candidates).hasSize(1);
    assertThat(candidates.get(0).companyName()).isEqualTo("네이버");
  }

  @Test
  void selectCandidates_explicitExpLevelMismatch_isFiltered() {
    JobPosting match = samplePostingWithMeta("네이버", "백엔드 개발자", "서울", null, "신입", null);
    JobPosting mismatch = samplePostingWithMeta("카카오", "백엔드 개발자", "서울", null, "경력", null);

    List<AgentCandidateJobPosting> candidates =
        candidatesFor(Map.of("experienceLevels", List.of("신입")), List.of(match, mismatch));

    assertThat(candidates.stream().map(AgentCandidateJobPosting::companyName).toList())
        .containsOnly("네이버")
        .doesNotContain("카카오");
  }

  @Test
  void selectCandidates_nullExpLevel_isNotFiltered() {
    JobPosting nullExp = samplePostingWithMeta("네이버", "백엔드 개발자", "서울", null, null, null);

    List<AgentCandidateJobPosting> candidates =
        candidatesFor(Map.of("experienceLevels", List.of("신입")), List.of(nullExp));

    assertThat(candidates).hasSize(1);
  }

  @Test
  void selectCandidates_explicitEmpTypeMismatch_isFiltered() {
    JobPosting match = samplePostingWithMeta("네이버", "백엔드 개발자", "서울", null, null, "정규직");
    JobPosting mismatch = samplePostingWithMeta("카카오", "백엔드 개발자", "서울", null, null, "계약직");

    List<AgentCandidateJobPosting> candidates =
        candidatesFor(Map.of("employmentTypes", List.of("정규직")), List.of(match, mismatch));

    assertThat(candidates.stream().map(AgentCandidateJobPosting::companyName).toList())
        .containsOnly("네이버");
  }

  @Test
  void selectCandidates_nullEmpType_isNotFiltered() {
    JobPosting nullEmp = samplePostingWithMeta("네이버", "백엔드 개발자", "서울", null, null, null);

    List<AgentCandidateJobPosting> candidates =
        candidatesFor(Map.of("employmentTypes", List.of("정규직")), List.of(nullEmp));

    assertThat(candidates).hasSize(1);
  }

  // ── Scoring / ordering ───────────────────────────────────────────────────

  @Test
  void selectCandidates_highScoreCandidate_sentFirst() {
    // "백엔드 개발자" title → MATCH (+30); "개발자" → AMBIGUOUS (+0)
    JobPosting highScore = samplePosting("네이버", "백엔드 개발자", "서울", null);
    JobPosting lowScore = samplePosting("카카오", "개발자", "서울", null);

    List<AgentCandidateJobPosting> candidates =
        candidatesFor(Map.of("roles", List.of("백엔드 개발자")), List.of(lowScore, highScore));

    assertThat(candidates).hasSizeGreaterThanOrEqualTo(2);
    assertThat(candidates.get(0).companyName()).isEqualTo("네이버");
    assertThat(candidates.get(0).scoreBreakdown().adjustedScore())
        .isGreaterThan(candidates.get(1).scoreBreakdown().adjustedScore());
  }

  @Test
  void selectCandidates_targetedCompanyBoost_appliesHigherScore() {
    JobPosting targeted = samplePosting("네이버", "백엔드 개발자", "서울", null);
    JobPosting notTargeted = samplePosting("카카오", "백엔드 개발자", "서울", null);

    List<AgentCandidateJobPosting> candidates =
        candidatesFor(Map.of("companies", List.of("네이버")), List.of(notTargeted, targeted));

    assertThat(candidates.get(0).companyName()).isEqualTo("네이버");
    assertThat(candidates.get(0).scoreBreakdown().adjustedScore())
        .isGreaterThan(candidates.get(1).scoreBreakdown().adjustedScore());
  }

  @Test
  void selectCandidates_subsidiaryCompanyMatch_receivesTargetBonus() {
    JobPosting subsidiary = samplePosting("토스인컴", "백엔드 개발자", "서울", null);
    JobPosting unrelated = samplePosting("당근마켓", "백엔드 개발자", "서울", null);

    List<AgentCandidateJobPosting> candidates =
        candidatesFor(Map.of("companies", List.of("토스")), List.of(unrelated, subsidiary));

    assertThat(candidates.get(0).companyName()).isEqualTo("토스인컴");
    assertThat(candidates.get(0).scoreBreakdown().companyScore()).isGreaterThan(0);
  }

  @Test
  void selectCandidates_noLinkedCompany_stillEligibleAndScored() {
    JobPosting noLinked = samplePosting("스타트업", "백엔드 개발자", "서울", null);

    List<AgentCandidateJobPosting> candidates =
        candidatesFor(
            Map.of("companySizes", List.of("대기업"), "industries", List.of("IT")), List.of(noLinked));

    assertThat(candidates).hasSize(1);
  }

  @Test
  void selectCandidates_industryScoredWhenLinkedCompanyMatches() {
    Company itCompany = Company.create("네이버", "네이버", "대기업", "IT,전자상거래");
    JobPosting withIndustry = samplePostingWithLinkedCompany("네이버", "백엔드 개발자", itCompany);
    JobPosting withoutIndustry = samplePosting("카카오", "백엔드 개발자", "서울", null);

    List<AgentCandidateJobPosting> candidates =
        candidatesFor(Map.of("industries", List.of("IT")), List.of(withoutIndustry, withIndustry));

    assertThat(candidates.get(0).companyName()).isEqualTo("네이버");
    assertThat(candidates.get(0).scoreBreakdown().industryScore()).isGreaterThan(0);
  }

  // ── Company cap (always 2, no targeted-company exception) ────────────────

  @Test
  void selectCandidates_diversitySelection_capsCompanyAt2() {
    List<JobPosting> postings =
        List.of(
            samplePosting("삼성", "개발자 A", "서울", null),
            samplePosting("삼성", "개발자 B", "서울", null),
            samplePosting("삼성", "개발자 C", "서울", null),
            samplePosting("삼성", "개발자 D", "서울", null),
            samplePosting("카카오", "백엔드 개발자", "서울", null));

    List<AgentCandidateJobPosting> candidates = candidatesFor(Map.of(), postings);

    long samsungCount = candidates.stream().filter(c -> "삼성".equals(c.companyName())).count();
    assertThat(samsungCount).isLessThanOrEqualTo(2);
  }

  // ── Role eligibility ────────────────────────────────────────────────────

  @Test
  void roleFilter_backend_posting_passes_for_backend_user() {
    JobPosting backend = samplePostingFull("네이버", "백엔드 개발자", null, null, null);
    List<AgentCandidateJobPosting> candidates =
        candidatesFor(Map.of("roles", List.of("백엔드 개발자")), List.of(backend));
    assertThat(candidates).hasSize(1);
  }

  @Test
  void roleFilter_marketing_intern_excluded_for_backend_user() {
    JobPosting marketing = samplePostingFull("스타트업", "마케팅 인턴", null, null, null);
    List<AgentCandidateJobPosting> candidates =
        candidatesFor(Map.of("roles", List.of("백엔드 개발자")), List.of(marketing));
    assertThat(candidates).isEmpty();
  }

  @Test
  void roleFilter_ambiguous_software_engineer_passes() {
    JobPosting ambiguous = samplePostingFull("스타트업", "Software Engineer", null, null, null);
    List<AgentCandidateJobPosting> candidates =
        candidatesFor(Map.of("roles", List.of("백엔드 개발자")), List.of(ambiguous));
    assertThat(candidates).hasSize(1);
  }

  // ── Experience eligibility ───────────────────────────────────────────────

  @Test
  void expFilter_newGrad_entry_posting_passes() {
    JobPosting posting = samplePostingFull("회사", "백엔드 개발자", null, "경력 무관", null);
    List<AgentCandidateJobPosting> candidates =
        candidatesFor(Map.of("experienceLevels", List.of("신입")), List.of(posting));
    assertThat(candidates).hasSize(1);
  }

  @Test
  void expFilter_newGrad_3yr_required_is_excluded() {
    JobPosting experienced = samplePostingFull("회사", "백엔드 개발자", null, "3년 이상", null);
    List<AgentCandidateJobPosting> candidates =
        candidatesFor(Map.of("experienceLevels", List.of("신입")), List.of(experienced));
    assertThat(candidates).isEmpty();
  }

  @Test
  void expFilter_newGrad_5yr_required_is_excluded() {
    JobPosting experienced = samplePostingFull("회사", "백엔드 개발자", null, "5년 이상", null);
    List<AgentCandidateJobPosting> candidates =
        candidatesFor(Map.of("experienceLevels", List.of("신입")), List.of(experienced));
    assertThat(candidates).isEmpty();
  }

  @Test
  void expFilter_newGrad_1to2yr_passes() {
    JobPosting junior = samplePostingFull("회사A", "백엔드 개발자", null, "1~2년", null);
    List<AgentCandidateJobPosting> candidates =
        candidatesFor(Map.of("experienceLevels", List.of("신입")), List.of(junior));
    assertThat(candidates).hasSize(1);
  }

  // ── Helpers ──────────────────────────────────────────────────────────────

  private List<AgentCandidateJobPosting> candidatesFor(
      Map<String, Object> prefMap, List<JobPosting> postings) {
    BriefingCategory category = mock(BriefingCategory.class);
    when(category.getCode()).thenReturn(BriefingCategoryCode.JOB_POSTING);
    UserBriefingPreference pref = mock(UserBriefingPreference.class);
    when(pref.getCategory()).thenReturn(category);
    when(pref.getPreference()).thenReturn(prefMap);

    when(userBriefingPreferenceRepository.findAllByUserIdAndActiveTrue(1L))
        .thenReturn(List.of(pref));
    BriefingJob stubJob = BriefingJob.createManual(1L, java.time.LocalDate.now());
    when(briefingJobPersistenceService.createOrGet(any(BriefingJob.class))).thenReturn(stubJob);
    when(briefingJobRepository.findById(any())).thenReturn(java.util.Optional.of(stubJob));
    when(candidatePoolService.findEligibleJobPostingsForBriefing(any())).thenReturn(postings);

    BriefingReport savedReport = mock(BriefingReport.class);
    when(savedReport.getId()).thenReturn(1L);

    ArgumentCaptor<AgentBriefingRequest> captor =
        ArgumentCaptor.forClass(AgentBriefingRequest.class);
    when(agentClient.generate(captor.capture(), any(Integer.class), any(Integer.class)))
        .thenReturn(mockAgentResponse);
    when(briefingJobPersistenceService.saveReportAndComplete(
            any(), any(BriefingReport.class), any(), any()))
        .thenReturn(savedReport);

    briefingService.generateBriefing(1L);
    return captor.getValue().candidatePool().jobPostings();
  }

  private BriefingReport mockBriefingReport() {
    BriefingReport r = mock(BriefingReport.class);
    when(r.getId()).thenReturn(1L);
    return r;
  }

  private JobPosting samplePosting(
      String company, String title, String location, LocalDate deadline) {
    return JobPosting.create(
        title,
        company,
        "원티드",
        "https://example.com/" + company.hashCode() + "/" + title.hashCode(),
        location,
        deadline,
        null,
        null,
        null,
        null,
        null,
        "hash-" + company + "-" + title,
        LocalDate.now(),
        null);
  }

  private JobPosting samplePostingWithMeta(
      String company,
      String title,
      String location,
      LocalDate deadline,
      String experienceLevel,
      String employmentType) {
    return JobPosting.create(
        title,
        company,
        "원티드",
        "https://example.com/" + company.hashCode() + "/" + title.hashCode(),
        location,
        deadline,
        null,
        null,
        null,
        employmentType,
        experienceLevel,
        "hash-" + company + "-" + title,
        LocalDate.now(),
        null);
  }

  private JobPosting samplePostingFull(
      String company, String title, String rolesJson, String experienceLevel, String skills) {
    return JobPosting.create(
        title,
        company,
        "원티드",
        "https://example.com/" + company.hashCode() + "/" + title.hashCode(),
        "서울",
        null,
        null,
        rolesJson,
        skills,
        null,
        experienceLevel,
        "hash-" + company + "-" + title,
        LocalDate.now(),
        null);
  }

  private JobPosting samplePostingWithLinkedCompany(
      String company, String title, Company linkedCompany) {
    JobPosting posting =
        JobPosting.create(
            title,
            company,
            "원티드",
            "https://example.com/" + company.hashCode() + "/" + title.hashCode(),
            "서울",
            null,
            null,
            null,
            null,
            null,
            null,
            "hash-" + company + "-" + title,
            LocalDate.now(),
            null);
    posting.linkCompany(linkedCompany);
    return posting;
  }
}
