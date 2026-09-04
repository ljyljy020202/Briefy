package com.briefy.domain.briefing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.briefy.config.BriefingProperties;
import com.briefy.config.ClassificationMode;
import com.briefy.config.ClassificationProperties;
import com.briefy.domain.briefing.repository.BriefingArticleRepository;
import com.briefy.domain.briefing.repository.BriefingJobRepository;
import com.briefy.domain.briefing.repository.BriefingReportRepository;
import com.briefy.domain.candidatepool.entity.JobPosting;
import com.briefy.domain.candidatepool.repository.JobPostingAnalysisRepository;
import com.briefy.domain.candidatepool.service.CandidatePoolService;
import com.briefy.domain.preference.entity.BriefingCategory;
import com.briefy.domain.preference.entity.BriefingCategoryCode;
import com.briefy.domain.preference.entity.UserBriefingPreference;
import com.briefy.domain.preference.repository.UserBriefingPreferenceRepository;
import com.briefy.domain.user.repository.UserRepository;
import com.briefy.infra.agent.AgentClient;
import com.briefy.infra.agent.dto.AgentBriefingRequest;
import com.briefy.infra.agent.dto.AgentBriefingResponse;
import com.briefy.infra.agent.dto.AgentCandidateJobPosting;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Integration tests for exposure-penalty effect on ranking, isNew/isUrgent flags, and per-company
 * diversity cap in the Top-7 selection pipeline.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BriefingServiceExposureTest {

  @Mock private BriefingJobRepository briefingJobRepository;
  @Mock private BriefingReportRepository briefingReportRepository;
  @Mock private BriefingArticleRepository briefingArticleRepository;
  @Mock private UserBriefingPreferenceRepository userBriefingPreferenceRepository;
  @Mock private AgentClient agentClient;
  @Mock private CandidatePoolService candidatePoolService;
  @Mock private UserRepository userRepository;
  @Mock private BriefingJobPersistenceService briefingJobPersistenceService;
  @Mock private BriefingProperties briefingProperties;
  @Mock private JobPostingAnalysisRepository jobPostingAnalysisRepository;

  @Spy
  private ClassificationProperties classificationProperties =
      new ClassificationProperties(
          ClassificationMode.OFF,
          "1.0.0",
          new ClassificationProperties.Worker(60000, 100, 600, 5, 2, 90, 700, 5, 60));

  private BriefingService briefingService;

  private static final ZoneId KST = ZoneId.of("Asia/Seoul");
  private static final LocalDate TODAY = LocalDate.now(KST);

  private AgentBriefingResponse mockAgentResponse;

  @BeforeEach
  void setUp() {
    briefingService =
        new BriefingService(
            briefingJobRepository,
            briefingReportRepository,
            briefingArticleRepository,
            userBriefingPreferenceRepository,
            agentClient,
            candidatePoolService,
            userRepository,
            briefingJobPersistenceService,
            briefingProperties,
            jobPostingAnalysisRepository,
            classificationProperties);

    when(jobPostingAnalysisRepository.findAllByJobPostingIdIn(any())).thenReturn(List.of());
    when(briefingArticleRepository.findRecentExposuresByUserId(any(), any())).thenReturn(List.of());
    when(briefingJobPersistenceService.findTodayReport(any(), any()))
        .thenReturn(java.util.Optional.empty());
    when(briefingJobPersistenceService.createOrGet(any()))
        .thenAnswer(
            inv ->
                com.briefy.domain.briefing.entity.BriefingJob.createManual(
                    1L, java.time.LocalDate.now(KST)));
    when(briefingJobPersistenceService.claimForProcessing(any())).thenReturn(true);
    when(briefingJobRepository.findById(any()))
        .thenAnswer(
            inv ->
                java.util.Optional.of(
                    com.briefy.domain.briefing.entity.BriefingJob.createManual(
                        1L, java.time.LocalDate.now(KST))));

    when(briefingProperties.agentRetryMaxAttempts()).thenReturn(0);
    when(briefingProperties.agentRetryBackoffSeconds()).thenReturn(0);
    when(briefingProperties.jobMaxRetryCount()).thenReturn(3);

    mockAgentResponse =
        new AgentBriefingResponse(
            "브리핑 제목",
            "요약",
            "## 신규 공고\n...",
            List.of(
                new AgentBriefingResponse.AgentArticle(
                    "테스트", "원티드", "https://example.com/1", "요약", "이유", null)),
            new AgentBriefingResponse.TokenUsage(100, 50),
            null,
            null,
            null,
            null);
  }

  // ── Exposure penalty integration ─────────────────────────────────────────

  @Test
  void exposure_yesterday_appliesHigherPenaltyThan_fiveDaysAgo() {
    String url1 = "https://example.com/jobs/1";
    String url2 = "https://example.com/jobs/2";
    JobPosting posting1 = posting("회사A", "백엔드 개발자", url1, null, null, null);
    JobPosting posting2 = posting("회사B", "백엔드 개발자", url2, null, null, null);

    when(briefingArticleRepository.findRecentExposuresByUserId(any(), any()))
        .thenReturn(
            List.of(
                exposure(url1, TODAY.minusDays(1)), // YESTERDAY → penalty 25
                exposure(url2, TODAY.minusDays(5)))); // STALE → penalty 10

    List<AgentCandidateJobPosting> candidates =
        candidatesFor(Map.of(), List.of(posting1, posting2));

    AgentCandidateJobPosting candidate1 =
        candidates.stream().filter(c -> "회사A".equals(c.companyName())).findFirst().orElseThrow();
    AgentCandidateJobPosting candidate2 =
        candidates.stream().filter(c -> "회사B".equals(c.companyName())).findFirst().orElseThrow();

    // 5-day penalty (10) yields higher adjustedScore than 1-day penalty (25)
    assertThat(candidate2.scoreBreakdown().adjustedScore())
        .isGreaterThan(candidate1.scoreBreakdown().adjustedScore());
  }

  @Test
  void exposure_sevenOrMoreDaysAgo_appliesNoPenalty() {
    String url = "https://example.com/jobs/old";
    JobPosting p = posting("회사Z", "개발자", url, null, null, null);

    when(briefingArticleRepository.findRecentExposuresByUserId(any(), any()))
        .thenReturn(List.of(exposure(url, TODAY.minusDays(7)))); // 7d → no penalty

    List<AgentCandidateJobPosting> candidates = candidatesFor(Map.of(), List.of(p));

    assertThat(candidates).hasSize(1);
    assertThat(candidates.get(0).scoreBreakdown().exposurePenalty()).isEqualTo(0);
  }

  @Test
  void exposure_urlWithQueryFragment_recognisedAsSamePosting() {
    String baseUrl = "https://example.com/jobs/123";
    String withQuery = "https://example.com/jobs/123?utm_source=email&ref=test";

    // Posting URL is clean; exposure was recorded under the query-bearing variant.
    // Both should canonicalize to the same key.
    JobPosting exposed = posting("회사Q", "개발자", baseUrl, null, null, null);
    when(briefingArticleRepository.findRecentExposuresByUserId(any(), any()))
        .thenReturn(List.of(exposure(withQuery, TODAY.minusDays(1)))); // YESTERDAY penalty 30

    List<AgentCandidateJobPosting> exposedCandidates = candidatesFor(Map.of(), List.of(exposed));

    // Verify penalty was applied (not zero)
    assertThat(exposedCandidates.get(0).scoreBreakdown().exposurePenalty()).isEqualTo(30);

    // Fresh posting with no exposure should score higher
    JobPosting fresh = posting("회사F", "개발자", "https://example.com/jobs/999", null, null, null);
    List<AgentCandidateJobPosting> freshCandidates = candidatesFor(Map.of(), List.of(fresh));

    assertThat(freshCandidates.get(0).scoreBreakdown().adjustedScore())
        .isGreaterThan(exposedCandidates.get(0).scoreBreakdown().adjustedScore());
  }

  @Test
  void exposure_allCandidatesRecentlyExposed_noneAreCompletelyExcluded() {
    List<JobPosting> postings =
        List.of(
            posting("회사1", "개발자", "https://e.com/1", null, null, null),
            posting("회사2", "개발자", "https://e.com/2", null, null, null),
            posting("회사3", "개발자", "https://e.com/3", null, null, null));

    when(briefingArticleRepository.findRecentExposuresByUserId(any(), any()))
        .thenReturn(
            List.of(
                exposure("https://e.com/1", TODAY.minusDays(1)),
                exposure("https://e.com/2", TODAY.minusDays(1)),
                exposure("https://e.com/3", TODAY.minusDays(1))));

    // Exposure penalty reduces score but does not exclude candidates
    List<AgentCandidateJobPosting> candidates = candidatesFor(Map.of(), postings);
    assertThat(candidates).hasSize(3);
  }

  // ── isNew / isUrgent flags ────────────────────────────────────────────────

  @Test
  void flags_postingCollectedToday_isNewTrue() {
    JobPosting p = posting("회사N", "개발자", "https://e.com/new", null, null, TODAY);
    assertThat(candidatesFor(Map.of(), List.of(p)).get(0).isNew()).isTrue();
  }

  @Test
  void flags_postingCollectedTenDaysAgo_isNewFalse() {
    JobPosting p = posting("회사O", "개발자", "https://e.com/old", null, null, TODAY.minusDays(10));
    assertThat(candidatesFor(Map.of(), List.of(p)).get(0).isNew()).isFalse();
  }

  @Test
  void flags_deadlineWithinSevenDays_isUrgentTrue() {
    JobPosting p =
        posting("회사U", "개발자", "https://e.com/urg", TODAY.plusDays(5), null, TODAY.minusDays(10));
    assertThat(candidatesFor(Map.of(), List.of(p)).get(0).isUrgent()).isTrue();
  }

  @Test
  void flags_noDeadline_isUrgentFalse() {
    JobPosting p = posting("회사V", "개발자", "https://e.com/noDl", null, null, TODAY.minusDays(10));
    assertThat(candidatesFor(Map.of(), List.of(p)).get(0).isUrgent()).isFalse();
  }

  @Test
  void flags_newAndUrgent_bothTrueIndependently() {
    // Collected today (isNew) + deadline in 3 days (isUrgent) → both true simultaneously
    JobPosting p = posting("회사NU", "개발자", "https://e.com/nu", TODAY.plusDays(3), null, TODAY);
    AgentCandidateJobPosting c = candidatesFor(Map.of(), List.of(p)).get(0);
    assertThat(c.isNew()).isTrue();
    assertThat(c.isUrgent()).isTrue();
  }

  // ── Diversity cap ─────────────────────────────────────────────────────────

  @Test
  void diversity_regularCompanyLimitedToTwo() {
    LocalDate old = TODAY.minusDays(10);
    List<JobPosting> samsungPosts =
        List.of(
            posting("삼성", "개발자A", "https://e.com/s1", null, null, old),
            posting("삼성", "개발자B", "https://e.com/s2", null, null, old),
            posting("삼성", "개발자C", "https://e.com/s3", null, null, old),
            posting("카카오", "개발자D", "https://e.com/k1", null, null, old));

    List<AgentCandidateJobPosting> candidates = candidatesFor(Map.of(), samsungPosts);

    long samsungCount = candidates.stream().filter(c -> "삼성".equals(c.companyName())).count();
    assertThat(samsungCount).isLessThanOrEqualTo(2);
  }

  @Test
  void diversity_targetedCompanyStillCappedAtTwo() {
    // MAX_PER_COMPANY=2 applies regardless of whether the company is in the user's target list
    LocalDate old = TODAY.minusDays(10);
    List<JobPosting> naverPosts =
        List.of(
            posting("네이버", "개발자A", "https://e.com/n1", null, null, old),
            posting("네이버", "개발자B", "https://e.com/n2", null, null, old),
            posting("네이버", "개발자C", "https://e.com/n3", null, null, old),
            posting("네이버", "개발자D", "https://e.com/n4", null, null, old));

    List<AgentCandidateJobPosting> candidates =
        candidatesFor(Map.of("companies", List.of("네이버")), naverPosts);

    long naverCount = candidates.stream().filter(c -> "네이버".equals(c.companyName())).count();
    assertThat(naverCount).isLessThanOrEqualTo(2);
  }

  @Test
  void diversity_companyCapRespectedAcrossAllCandidates() {
    // 5 postings from one company: only 2 should be selected regardless of isNew/score
    LocalDate old = TODAY.minusDays(10);
    List<JobPosting> all =
        List.of(
            posting(
                "채움회사",
                "NEW개발자",
                "https://e.com/fill-new",
                null,
                TODAY.minusDays(1).atStartOfDay(),
                null),
            posting("채움회사", "EVER1", "https://e.com/fill1", null, null, old),
            posting("채움회사", "EVER2", "https://e.com/fill2", null, null, old),
            posting("채움회사", "EVER3", "https://e.com/fill3", null, null, old),
            posting("채움회사", "EVER4", "https://e.com/fill4", null, null, old));

    List<AgentCandidateJobPosting> candidates = candidatesFor(Map.of(), all);

    long companyCount = candidates.stream().filter(c -> "채움회사".equals(c.companyName())).count();
    assertThat(companyCount).isLessThanOrEqualTo(2);
  }

  @Test
  void diversity_noDuplicates_allUrlsDistinct() {
    LocalDate old = TODAY.minusDays(10);
    List<JobPosting> all =
        List.of(
            posting(
                "새회사A", "개발자", "https://e.com/a1", null, TODAY.minusDays(1).atStartOfDay(), null),
            posting(
                "새회사B", "개발자", "https://e.com/b1", null, TODAY.minusDays(1).atStartOfDay(), null),
            posting("급회사A", "개발자", "https://e.com/c1", TODAY.plusDays(3), null, old),
            posting("급회사B", "개발자", "https://e.com/d1", TODAY.plusDays(5), null, old),
            posting("상시회사A", "개발자", "https://e.com/e1", null, null, old),
            posting("상시회사B", "개발자", "https://e.com/f1", null, null, old));

    List<AgentCandidateJobPosting> candidates = candidatesFor(Map.of(), all);

    long distinctUrls =
        candidates.stream().map(AgentCandidateJobPosting::sourceUrl).distinct().count();
    assertThat(distinctUrls).isEqualTo(candidates.size());
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
    when(candidatePoolService.findEligibleJobPostingsForBriefing(any())).thenReturn(postings);

    ArgumentCaptor<AgentBriefingRequest> captor =
        ArgumentCaptor.forClass(AgentBriefingRequest.class);
    when(agentClient.generate(captor.capture(), any(Integer.class), any(Integer.class)))
        .thenReturn(mockAgentResponse);

    com.briefy.domain.briefing.entity.BriefingReport mockReport =
        mock(com.briefy.domain.briefing.entity.BriefingReport.class);
    when(mockReport.getId()).thenReturn(1L);
    when(briefingJobPersistenceService.saveReportAndComplete(any(), any(), any(), any()))
        .thenReturn(mockReport);

    briefingService.generateBriefing(1L);
    return captor.getValue().candidatePool().jobPostings();
  }

  private static JobPosting posting(
      String company,
      String title,
      String url,
      LocalDate deadline,
      LocalDateTime publishedAt,
      LocalDate collectedDate) {
    return JobPosting.create(
        title,
        company,
        "원티드",
        url != null ? url : "https://e.com/" + company.hashCode() + "/" + title.hashCode(),
        "서울",
        deadline,
        null,
        null,
        null,
        null,
        null,
        "hash-" + company + "-" + title,
        collectedDate != null ? collectedDate : TODAY,
        publishedAt);
  }

  private static BriefingArticleRepository.ExposedUrlInfo exposure(String url, LocalDate date) {
    return new BriefingArticleRepository.ExposedUrlInfo() {
      @Override
      public String getUrl() {
        return url;
      }

      @Override
      public LocalDate getLastExposedDate() {
        return date;
      }
    };
  }
}
