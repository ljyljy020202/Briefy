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
import com.briefy.domain.briefing.repository.BriefingJobRepository;
import com.briefy.domain.briefing.repository.BriefingReportRepository;
import com.briefy.domain.briefingpreference.entity.BriefingCategory;
import com.briefy.domain.briefingpreference.entity.BriefingCategoryCode;
import com.briefy.domain.briefingpreference.entity.UserBriefingPreference;
import com.briefy.domain.briefingpreference.repository.UserBriefingPreferenceRepository;
import com.briefy.domain.candidatepool.entity.JobPosting;
import com.briefy.domain.candidatepool.service.CandidatePoolService;
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
  @Mock private UserBriefingPreferenceRepository userBriefingPreferenceRepository;
  @Mock private AgentClient agentClient;
  @Mock private CandidatePoolService candidatePoolService;

  @InjectMocks private BriefingService briefingService;

  private UserBriefingPreference mockPref;
  private AgentBriefingResponse mockAgentResponse;

  @BeforeEach
  void setUp() {
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
    when(candidatePoolService.findJobPostingsByDate(any())).thenReturn(List.of(posting));

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
    when(candidatePoolService.findJobPostingsByDate(any())).thenReturn(postings);

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
    when(candidatePoolService.findJobPostingsByDate(any())).thenReturn(List.of());
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
    JobPosting highScore = samplePosting("네이버", "백엔드 개발자 채용", "서울", null);
    JobPosting lowScore = samplePosting("카카오", "프론트엔드 개발자", "서울", null);
    when(candidatePoolService.findJobPostingsByDate(any()))
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
}
