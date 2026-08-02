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

import com.briefy.domain.briefing.client.AgentClient;
import com.briefy.domain.briefing.client.dto.AgentBriefingRequest;
import com.briefy.domain.briefing.client.dto.AgentBriefingResponse;
import com.briefy.domain.briefing.client.dto.AgentCandidateJobPosting;
import com.briefy.domain.briefing.dto.BriefingDetailResponse;
import com.briefy.domain.briefing.dto.BriefingListItem;
import com.briefy.domain.briefing.dto.GenerateResult;
import com.briefy.domain.briefing.entity.BriefingJob;
import com.briefy.domain.briefing.entity.BriefingReport;
import com.briefy.domain.briefing.repository.BriefingArticleRepository;
import com.briefy.domain.briefing.repository.BriefingJobRepository;
import com.briefy.domain.briefing.repository.BriefingReportRepository;
import com.briefy.domain.briefingpreference.entity.BriefingCategory;
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

  @InjectMocks private BriefingService briefingService;

  private UserBriefingPreference mockPref;
  private AgentBriefingResponse mockAgentResponse;

  @BeforeEach
  void setUp() {
    // No prior exposures by default; individual tests can override.
    when(briefingArticleRepository.findRecentExposuresByUserId(any(), any())).thenReturn(List.of());

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
            new AgentBriefingResponse.TokenUsage(1000, 500));
  }

  @Test
  void generateBriefing_success_returnsCompletedResult() {
    when(userBriefingPreferenceRepository.findAllByUserIdAndActiveTrue(1L))
        .thenReturn(List.of(mockPref));
    when(briefingJobRepository.save(any(BriefingJob.class))).thenAnswer(inv -> inv.getArgument(0));
    when(agentClient.generate(any(AgentBriefingRequest.class))).thenReturn(mockAgentResponse);

    BriefingReport mockReport = mock(BriefingReport.class);
    when(mockReport.getId()).thenReturn(100L);
    when(briefingReportRepository.save(any(BriefingReport.class))).thenReturn(mockReport);

    GenerateResult result = briefingService.generateBriefing(1L);

    assertThat(result.briefingReportId()).isEqualTo(100L);
    assertThat(result.status()).isEqualTo("COMPLETED");
    verify(briefingReportRepository).save(any(BriefingReport.class));
  }

  @Test
  void generateBriefing_agentThrows_marksJobFailedAndRethrows() {
    when(userBriefingPreferenceRepository.findAllByUserIdAndActiveTrue(1L))
        .thenReturn(List.of(mockPref));
    when(briefingJobRepository.save(any(BriefingJob.class))).thenAnswer(inv -> inv.getArgument(0));
    when(agentClient.generate(any(AgentBriefingRequest.class)))
        .thenThrow(new BusinessException(ErrorCode.AGENT_SERVER_ERROR));

    assertThatThrownBy(() -> briefingService.generateBriefing(1L))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            e ->
                assertThat(((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.AGENT_SERVER_ERROR));

    verify(briefingReportRepository, never()).save(any());
  }

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

  @Test
  void generateBriefing_includesCandidatePoolInAgentRequest() {
    when(userBriefingPreferenceRepository.findAllByUserIdAndActiveTrue(1L))
        .thenReturn(List.of(mockPref));
    when(briefingJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    JobPosting posting = samplePosting("네이버", "백엔드 개발자", "서울", LocalDate.now().plusDays(3));
    when(candidatePoolService.findEligibleJobPostingsForBriefing(any()))
        .thenReturn(List.of(posting));

    ArgumentCaptor<AgentBriefingRequest> captor =
        ArgumentCaptor.forClass(AgentBriefingRequest.class);
    when(agentClient.generate(captor.capture())).thenReturn(mockAgentResponse);

    BriefingReport mockReport = mock(BriefingReport.class);
    when(mockReport.getId()).thenReturn(1L);
    when(briefingReportRepository.save(any())).thenReturn(mockReport);

    briefingService.generateBriefing(1L);

    var pool = captor.getValue().candidatePool();
    assertThat(pool).isNotNull();
    assertThat(pool.jobPostings()).hasSize(1);
    assertThat(pool.companyIssues()).isEmpty();
    assertThat(pool.industryIssues()).isEmpty();
  }

  @Test
  void generateBriefing_limitsTo30Candidates_whenMoreExist() {
    when(userBriefingPreferenceRepository.findAllByUserIdAndActiveTrue(1L))
        .thenReturn(List.of(mockPref));
    when(briefingJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    List<JobPosting> postings =
        IntStream.range(0, 35)
            .mapToObj(i -> samplePosting("회사" + i, "개발자 " + i, "서울", null))
            .toList();
    when(candidatePoolService.findEligibleJobPostingsForBriefing(any())).thenReturn(postings);

    ArgumentCaptor<AgentBriefingRequest> captor =
        ArgumentCaptor.forClass(AgentBriefingRequest.class);
    when(agentClient.generate(captor.capture())).thenReturn(mockAgentResponse);

    BriefingReport mockReport = mock(BriefingReport.class);
    when(mockReport.getId()).thenReturn(1L);
    when(briefingReportRepository.save(any())).thenReturn(mockReport);

    briefingService.generateBriefing(1L);

    assertThat(captor.getValue().candidatePool().jobPostings()).hasSize(30);
  }

  @Test
  void generateBriefing_emptyCandidatePool_doesNotCrash() {
    when(userBriefingPreferenceRepository.findAllByUserIdAndActiveTrue(1L))
        .thenReturn(List.of(mockPref));
    when(briefingJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(candidatePoolService.findEligibleJobPostingsForBriefing(any())).thenReturn(List.of());
    when(agentClient.generate(any())).thenReturn(mockAgentResponse);

    BriefingReport mockReport = mock(BriefingReport.class);
    when(mockReport.getId()).thenReturn(1L);
    when(briefingReportRepository.save(any())).thenReturn(mockReport);

    GenerateResult result = briefingService.generateBriefing(1L);

    assertThat(result.status()).isEqualTo("COMPLETED");
    verify(agentClient).generate(argThat(req -> req.candidatePool().jobPostings().isEmpty()));
  }

  @Test
  void generateBriefing_highScoreCandidate_sentFirst() {
    when(userBriefingPreferenceRepository.findAllByUserIdAndActiveTrue(1L))
        .thenReturn(List.of(mockPref));
    when(briefingJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    // mockPref preference: {roles: ["백엔드 개발자"]} → title match → +30
    // "개발자" is AMBIGUOUS (no role bonus); "백엔드 개발자 채용" is MATCH (+30)
    JobPosting highScore = samplePosting("네이버", "백엔드 개발자 채용", "서울", null);
    JobPosting lowScore = samplePosting("카카오", "개발자", "서울", null);
    when(candidatePoolService.findEligibleJobPostingsForBriefing(any()))
        .thenReturn(List.of(lowScore, highScore));

    ArgumentCaptor<AgentBriefingRequest> captor =
        ArgumentCaptor.forClass(AgentBriefingRequest.class);
    when(agentClient.generate(captor.capture())).thenReturn(mockAgentResponse);

    BriefingReport mockReport = mock(BriefingReport.class);
    when(mockReport.getId()).thenReturn(1L);
    when(briefingReportRepository.save(any())).thenReturn(mockReport);

    briefingService.generateBriefing(1L);

    List<AgentCandidateJobPosting> candidates = captor.getValue().candidatePool().jobPostings();
    assertThat(candidates).hasSize(2);
    assertThat(candidates.get(0).title()).contains("백엔드");
    assertThat(candidates.get(0).preScore()).isGreaterThan(candidates.get(1).preScore());
  }

  // ── Eligibility filtering ────────────────────────────────────────────────

  @Test
  void selectCandidates_expiredPosting_isFiltered() {
    JobPosting active = samplePosting("네이버", "백엔드 개발자", "서울", LocalDate.now().plusDays(3));
    JobPosting expired = samplePosting("카카오", "프론트엔드 개발자", "서울", LocalDate.now().minusDays(1));

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

  // ── Scoring: company metadata ────────────────────────────────────────────

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
    assertThat(candidates.get(0).preScore()).isGreaterThan(candidates.get(1).preScore());
  }

  @Test
  void selectCandidates_companySizeScoredWhenLinkedCompanyMatches() {
    Company largeCompany = Company.create("네이버", "네이버", "대기업", null);
    JobPosting withSize = samplePostingWithLinkedCompany("네이버", "백엔드 개발자", largeCompany);
    JobPosting withoutSize = samplePosting("스타트업", "백엔드 개발자", "서울", null);

    List<AgentCandidateJobPosting> candidates =
        candidatesFor(Map.of("companySizes", List.of("대기업")), List.of(withoutSize, withSize));

    assertThat(candidates.get(0).companyName()).isEqualTo("네이버");
    assertThat(candidates.get(0).preScore()).isGreaterThan(candidates.get(1).preScore());
  }

  @Test
  void selectCandidates_targetedCompanyBoost_appliesHigherScore() {
    JobPosting targeted = samplePosting("네이버", "백엔드 개발자", "서울", null);
    JobPosting notTargeted = samplePosting("카카오", "백엔드 개발자", "서울", null);

    List<AgentCandidateJobPosting> candidates =
        candidatesFor(Map.of("companies", List.of("네이버")), List.of(notTargeted, targeted));

    assertThat(candidates.get(0).companyName()).isEqualTo("네이버");
    assertThat(candidates.get(0).preScore()).isGreaterThan(candidates.get(1).preScore());
  }

  // ── Diversity selection ──────────────────────────────────────────────────

  @Test
  void selectCandidates_diversitySelection_capsNonTargetedCompanyAt2() {
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

  @Test
  void selectCandidates_diversitySelection_allowsMoreFromTargetedCompany() {
    List<JobPosting> postings =
        List.of(
            samplePosting("네이버", "개발자 A", "서울", null),
            samplePosting("네이버", "개발자 B", "서울", null),
            samplePosting("네이버", "개발자 C", "서울", null),
            samplePosting("네이버", "개발자 D", "서울", null));

    List<AgentCandidateJobPosting> candidates =
        candidatesFor(Map.of("companies", List.of("네이버")), postings);

    long naverCount = candidates.stream().filter(c -> "네이버".equals(c.companyName())).count();
    assertThat(naverCount).isGreaterThan(2);
  }

  @Test
  void selectCandidates_preScoreComputed_alwaysTrueForBackendBuiltCandidates() {
    List<JobPosting> postings =
        List.of(
            samplePosting("네이버", "백엔드 개발자", "서울", null),
            samplePosting("카카오", "프론트엔드 개발자", "서울", null));

    List<AgentCandidateJobPosting> candidates = candidatesFor(Map.of(), postings);

    assertThat(candidates).isNotEmpty();
    assertThat(candidates).allSatisfy(c -> assertThat(c.preScoreComputed()).isTrue());
  }

  @Test
  void selectCandidates_preScoreComputedTrue_regardlessOfScore() {
    // preScoreComputed must be true even when preference has no matches (low/zero score).
    // Note: SCORE_RECENT(+5) may apply since samplePosting uses LocalDate.now().
    JobPosting posting = samplePosting("무관회사", "무관직무", "무관지역", null);
    List<AgentCandidateJobPosting> candidates = candidatesFor(Map.of(), List.of(posting));

    assertThat(candidates).hasSize(1);
    assertThat(candidates.get(0).preScoreComputed()).isTrue();
  }

  @Test
  void selectCandidates_deterministicOrdering() {
    List<JobPosting> postings =
        List.of(
            samplePosting("나", "백엔드 개발자", "서울", null),
            samplePosting("가", "백엔드 개발자", "서울", null),
            samplePosting("다", "백엔드 개발자", "서울", null));

    List<AgentCandidateJobPosting> first = candidatesFor(Map.of(), postings);
    List<AgentCandidateJobPosting> second = candidatesFor(Map.of(), postings);

    assertThat(first.stream().map(AgentCandidateJobPosting::companyName).toList())
        .isEqualTo(second.stream().map(AgentCandidateJobPosting::companyName).toList());
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
  void roleFilter_marketing_with_sql_skill_excluded_for_backend_user() {
    // SQL in skills must NOT cause a marketing posting to be treated as a backend posting.
    JobPosting marketing = samplePostingFull("스타트업", "마케팅 분석가", null, null, "SQL,Python");
    List<AgentCandidateJobPosting> candidates =
        candidatesFor(Map.of("roles", List.of("백엔드 개발자")), List.of(marketing));
    assertThat(candidates).isEmpty();
  }

  @Test
  void roleFilter_ambiguous_software_engineer_passes_with_lower_score() {
    JobPosting ambiguous = samplePostingFull("스타트업", "Software Engineer", null, null, null);
    JobPosting backend = samplePostingFull("네이버", "백엔드 개발자", null, null, null);

    List<AgentCandidateJobPosting> candidates =
        candidatesFor(Map.of("roles", List.of("백엔드 개발자")), List.of(ambiguous, backend));

    // Both pass (ambiguous is not excluded)
    assertThat(candidates).hasSize(2);
    // Explicit backend match scores higher than ambiguous posting
    assertThat(candidates.get(0).title()).contains("백엔드");
    assertThat(candidates.get(0).preScore()).isGreaterThan(candidates.get(1).preScore());
  }

  // ── Experience eligibility — new grad user ────────────────────────────────

  @Test
  void expFilter_newGrad_entry_posting_passes() {
    JobPosting posting = samplePostingFull("회사", "백엔드 개발자", null, "경력 무관", null);
    List<AgentCandidateJobPosting> candidates =
        candidatesFor(Map.of("experienceLevels", List.of("신입")), List.of(posting));
    assertThat(candidates).hasSize(1);
  }

  @Test
  void expFilter_newGrad_mixed_posting_passes() {
    JobPosting posting = samplePostingFull("회사", "백엔드 개발자", null, "신입/경력", null);
    List<AgentCandidateJobPosting> candidates =
        candidatesFor(Map.of("experienceLevels", List.of("신입")), List.of(posting));
    assertThat(candidates).hasSize(1);
  }

  @Test
  void expFilter_newGrad_1to2yr_passes_with_lower_score() {
    JobPosting junior = samplePostingFull("회사A", "백엔드 개발자", null, "1~2년", null);
    JobPosting fresh = samplePostingFull("회사B", "백엔드 개발자", null, "신입", null);

    List<AgentCandidateJobPosting> candidates =
        candidatesFor(Map.of("experienceLevels", List.of("신입")), List.of(junior, fresh));

    // Both pass — junior posting gets lower experience bonus
    assertThat(candidates).hasSize(2);
    // "신입" posting gets PASS_FULL (+15 exp score), "1~2년" gets PASS_PARTIAL (+0 exp score)
    assertThat(candidates.get(0).companyName()).isEqualTo("회사B");
    assertThat(candidates.get(0).preScore()).isGreaterThan(candidates.get(1).preScore());
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
  void expFilter_unknown_experience_posting_passes_with_partial_score() {
    JobPosting unknown = samplePostingFull("회사A", "백엔드 개발자", null, null, null);
    JobPosting fresh = samplePostingFull("회사B", "백엔드 개발자", null, "신입", null);

    List<AgentCandidateJobPosting> candidates =
        candidatesFor(Map.of("experienceLevels", List.of("신입")), List.of(unknown, fresh));

    // UNKNOWN passes but gets no experience bonus → lower score than NEW_GRAD posting
    assertThat(candidates).hasSize(2);
    assertThat(candidates.get(0).companyName()).isEqualTo("회사B");
  }

  @Test
  void expFilter_legacy_intern_in_expLevels_treated_as_new_grad() {
    // "인턴" stored in experienceLevels (legacy data) → treated as new-grad preference
    JobPosting experienced = samplePostingFull("회사", "백엔드 개발자", null, "5년 이상", null);
    JobPosting entry = samplePostingFull("회사2", "백엔드 개발자", null, "경력 무관", null);

    List<AgentCandidateJobPosting> exp5yr =
        candidatesFor(Map.of("experienceLevels", List.of("인턴")), List.of(experienced));
    List<AgentCandidateJobPosting> entryResult =
        candidatesFor(Map.of("experienceLevels", List.of("인턴")), List.of(entry));

    assertThat(exp5yr).isEmpty();
    assertThat(entryResult).hasSize(1);
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
    when(briefingJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(candidatePoolService.findEligibleJobPostingsForBriefing(any())).thenReturn(postings);

    ArgumentCaptor<AgentBriefingRequest> captor =
        ArgumentCaptor.forClass(AgentBriefingRequest.class);
    when(agentClient.generate(captor.capture())).thenReturn(mockAgentResponse);

    BriefingReport mockReport = mock(BriefingReport.class);
    when(mockReport.getId()).thenReturn(1L);
    when(briefingReportRepository.save(any())).thenReturn(mockReport);

    briefingService.generateBriefing(1L);
    return captor.getValue().candidatePool().jobPostings();
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

  /** Creates a posting with full control over title, roles JSON, and experienceLevel. */
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
