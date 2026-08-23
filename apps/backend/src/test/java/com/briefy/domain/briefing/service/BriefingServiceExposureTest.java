package com.briefy.domain.briefing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.briefy.domain.briefing.policy.CandidateType;
import com.briefy.domain.briefing.repository.BriefingArticleRepository;
import com.briefy.domain.briefing.repository.BriefingJobRepository;
import com.briefy.domain.briefing.repository.BriefingReportRepository;
import com.briefy.domain.candidatepool.entity.JobPosting;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

/**
 * Unit tests for exposure penalty, urgency bonus, CandidateType classification, and Top-30
 * quota-based candidate selection.
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

  @InjectMocks private BriefingService briefingService;

  private static final ZoneId KST = ZoneId.of("Asia/Seoul");
  private static final LocalDate TODAY = LocalDate.now(KST);

  private AgentBriefingResponse mockAgentResponse;

  @BeforeEach
  void setUp() {
    when(briefingArticleRepository.findRecentExposuresByUserId(any(), any())).thenReturn(List.of());
    when(briefingJobRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    mockAgentResponse =
        new AgentBriefingResponse(
            "브리핑 제목",
            "요약",
            "## 신규 공고\n...",
            List.of(
                new AgentBriefingResponse.AgentArticle(
                    "테스트", "원티드", "https://example.com/1", "요약", "이유", null)),
            new AgentBriefingResponse.TokenUsage(100, 50));
  }

  // ---------------------------------------------------------------------------
  // Exposure penalty tests
  // ---------------------------------------------------------------------------

  @Test
  void exposure_yesterday_appliesHigherPenaltyThan_fiveDaysAgo() {
    String url = "https://example.com/jobs/1";
    JobPosting posting1 = posting("회사A", "백엔드 개발자", url, null, null, null);
    JobPosting posting2 = posting("회사B", "백엔드 개발자", "https://example.com/jobs/2", null, null, null);

    // posting1 exposed yesterday; posting2 exposed 5 days ago
    when(briefingArticleRepository.findRecentExposuresByUserId(any(), any()))
        .thenReturn(
            List.of(
                exposure(url, TODAY.minusDays(1)),
                exposure("https://example.com/jobs/2", TODAY.minusDays(5))));

    List<AgentCandidateJobPosting> candidates =
        candidatesFor(Map.of(), List.of(posting1, posting2));

    AgentCandidateJobPosting candidate1 =
        candidates.stream().filter(c -> "회사A".equals(c.companyName())).findFirst().orElseThrow();
    AgentCandidateJobPosting candidate2 =
        candidates.stream().filter(c -> "회사B".equals(c.companyName())).findFirst().orElseThrow();

    // posting1 (yesterday) penalised by 40, posting2 (5 days ago) penalised by 10 → posting2 scores
    // higher
    assertThat(candidate2.preScore()).isGreaterThan(candidate1.preScore());
  }

  @Test
  void exposure_sevenOrMoreDaysAgo_appliesNoPenalty() {
    String url = "https://example.com/jobs/old";
    JobPosting posting = posting("회사Z", "개발자", url, null, null, null);

    when(briefingArticleRepository.findRecentExposuresByUserId(any(), any()))
        .thenReturn(List.of(exposure(url, TODAY.minusDays(7))));

    List<AgentCandidateJobPosting> candidates = candidatesFor(Map.of(), List.of(posting));
    assertThat(candidates).hasSize(1);

    // No exposure penalty → score equals base score (SCORE_RECENT applies since
    // collectedDate=today)
    JobPosting noExposure =
        posting("회사X", "개발자", "https://example.com/jobs/fresh", null, null, null);
    List<AgentCandidateJobPosting> noExposureCandidates =
        candidatesFor(Map.of(), List.of(noExposure));

    assertThat(candidates.get(0).preScore()).isEqualTo(noExposureCandidates.get(0).preScore());
  }

  @Test
  void exposure_urlWithQueryFragment_recognisedAsSamePosting() {
    // Base URL and a URL with query/fragment should canonicalize to the same value
    String baseUrl = "https://example.com/jobs/123";
    String withQuery = "https://example.com/jobs/123?utm_source=email&ref=test";

    // Posting uses baseUrl; exposure recorded with withQuery variant
    JobPosting p = posting("회사Q", "개발자", baseUrl, null, null, null);
    when(briefingArticleRepository.findRecentExposuresByUserId(any(), any()))
        .thenReturn(List.of(exposure(withQuery, TODAY.minusDays(1))));

    List<AgentCandidateJobPosting> candidates = candidatesFor(Map.of(), List.of(p));
    assertThat(candidates).hasSize(1);

    // Exposure was yesterday → penalty -40 → score lower than base
    JobPosting fresh = posting("회사F", "개발자", "https://example.com/jobs/999", null, null, null);
    List<AgentCandidateJobPosting> freshCandidates = candidatesFor(Map.of(), List.of(fresh));

    assertThat(freshCandidates.get(0).preScore()).isGreaterThan(candidates.get(0).preScore());
  }

  @Test
  void exposure_deadlineOneDayAway_reducesExposurePenalty() {
    String url = "https://example.com/jobs/urgent";
    // Deadline tomorrow → urgency bonus +25; exposed yesterday → penalty -40; net = -15
    JobPosting p = posting("회사U", "개발자", url, TODAY.plusDays(1), null, null);
    when(briefingArticleRepository.findRecentExposuresByUserId(any(), any()))
        .thenReturn(List.of(exposure(url, TODAY.minusDays(1))));

    List<AgentCandidateJobPosting> candidates = candidatesFor(Map.of(), List.of(p));
    assertThat(candidates).hasSize(1);

    // Baseline: no exposure, no deadline → base + 0 = base
    // Exposed with 1-day deadline: base + urgency(25) - penalty(40) = base - 15
    // Net gap = base - (base - 15) = 15, proving urgency bonus partially offsets penalty
    JobPosting noExposure =
        posting("회사NE", "개발자", "https://example.com/jobs/999", null, null, null);
    List<AgentCandidateJobPosting> noExpCandidates = candidatesFor(Map.of(), List.of(noExposure));

    assertThat(noExpCandidates.get(0).preScore() - candidates.get(0).preScore()).isEqualTo(15);
  }

  @Test
  void exposure_allCandidatesRecentlyExposed_noneAreCompletelyExcluded() {
    List<JobPosting> postings =
        List.of(
            posting("회사1", "개발자", "https://e.com/1", null, null, null),
            posting("회사2", "개발자", "https://e.com/2", null, null, null),
            posting("회사3", "개발자", "https://e.com/3", null, null, null));

    // All exposed yesterday
    when(briefingArticleRepository.findRecentExposuresByUserId(any(), any()))
        .thenReturn(
            List.of(
                exposure("https://e.com/1", TODAY.minusDays(1)),
                exposure("https://e.com/2", TODAY.minusDays(1)),
                exposure("https://e.com/3", TODAY.minusDays(1))));

    List<AgentCandidateJobPosting> candidates = candidatesFor(Map.of(), postings);

    // All candidates are still present (exposure causes penalty, not exclusion)
    assertThat(candidates).hasSize(3);
  }

  // ---------------------------------------------------------------------------
  // CandidateType classification
  // ---------------------------------------------------------------------------

  @Test
  void candidateType_publishedAtWithinThreeDays_isNEW() {
    JobPosting p =
        posting("회사A", "개발자", "https://e.com/a", null, TODAY.minusDays(2).atStartOfDay(), null);

    CandidateType type = briefingService.classifyCandidateType(p, TODAY);
    assertThat(type).isEqualTo(CandidateType.NEW);
  }

  @Test
  void candidateType_publishedAtNull_collectedDateWithinThreeDays_isNEW() {
    // publishedAt is null → use collectedDate as firstSeenAt
    JobPosting p = posting("회사B", "개발자", "https://e.com/b", null, null, TODAY.minusDays(1));

    CandidateType type = briefingService.classifyCandidateType(p, TODAY);
    assertThat(type).isEqualTo(CandidateType.NEW);
  }

  @Test
  void candidateType_publishedAtNull_collectedDateOld_deadlineWithinSevenDays_isURGENT() {
    JobPosting p =
        posting("회사C", "개발자", "https://e.com/c", TODAY.plusDays(5), null, TODAY.minusDays(10));

    CandidateType type = briefingService.classifyCandidateType(p, TODAY);
    assertThat(type).isEqualTo(CandidateType.URGENT);
  }

  @Test
  void candidateType_notNewNotUrgent_isEVERGREEN() {
    JobPosting p =
        posting("회사D", "개발자", "https://e.com/d", TODAY.plusDays(30), null, TODAY.minusDays(10));

    CandidateType type = briefingService.classifyCandidateType(p, TODAY);
    assertThat(type).isEqualTo(CandidateType.EVERGREEN);
  }

  @Test
  void candidateType_sentInAgentRequest() {
    // Verify that candidateType is populated in the DTO sent to the agent
    LocalDate oldDate = TODAY.minusDays(10);

    // OLD posting with imminent deadline → URGENT
    JobPosting urgent = posting("회사U", "개발자", "https://e.com/u", TODAY.plusDays(3), null, oldDate);

    List<AgentCandidateJobPosting> candidates = candidatesFor(Map.of(), List.of(urgent));

    assertThat(candidates).hasSize(1);
    assertThat(candidates.get(0).candidateType()).isEqualTo("URGENT");
  }

  // ---------------------------------------------------------------------------
  // Top-30 quota tests
  // ---------------------------------------------------------------------------

  @Test
  void quota_exactlyFillsEachGroup() {
    LocalDate old = TODAY.minusDays(10);
    List<JobPosting> newPosts =
        buildPostings("새회사", 12, null, TODAY.minusDays(1).atStartOfDay(), null);
    List<JobPosting> urgentPosts = buildPostings("급회사", 10, TODAY.plusDays(5), null, old);
    List<JobPosting> evergreenPosts = buildPostings("상시회사", 8, TODAY.plusDays(60), null, old);

    List<JobPosting> all = new ArrayList<>();
    all.addAll(newPosts);
    all.addAll(urgentPosts);
    all.addAll(evergreenPosts);

    List<AgentCandidateJobPosting> candidates = candidatesFor(Map.of(), all);

    long newCount = candidates.stream().filter(c -> "NEW".equals(c.candidateType())).count();
    long urgentCount = candidates.stream().filter(c -> "URGENT".equals(c.candidateType())).count();
    long evergreenCount =
        candidates.stream().filter(c -> "EVERGREEN".equals(c.candidateType())).count();

    assertThat(newCount).isEqualTo(12);
    assertThat(urgentCount).isEqualTo(10);
    assertThat(evergreenCount).isEqualTo(8);
    assertThat(candidates).hasSize(30);
  }

  @Test
  void quota_insufficientNewGroup_fillsFromLeftovers() {
    LocalDate old = TODAY.minusDays(10);
    // Only 3 NEW postings (quota is 12)
    List<JobPosting> newPosts =
        buildPostings("새회사", 3, null, TODAY.minusDays(1).atStartOfDay(), null);
    // 20 EVERGREEN postings
    List<JobPosting> evergreenPosts = buildPostings("상시회사", 20, TODAY.plusDays(60), null, old);

    List<JobPosting> all = new ArrayList<>();
    all.addAll(newPosts);
    all.addAll(evergreenPosts);

    List<AgentCandidateJobPosting> candidates = candidatesFor(Map.of(), all);

    // 3 NEW + 0 URGENT + 8 EVERGREEN = 11 from quota; fill 19 more from leftovers (EVERGREEN)
    // Total min(11 + leftovers, 30) → limited by 23 total postings → 23
    assertThat(candidates).hasSize(23);

    // All 3 NEW postings should be included
    long newCount = candidates.stream().filter(c -> "NEW".equals(c.candidateType())).count();
    assertThat(newCount).isEqualTo(3);
  }

  @Test
  void quota_noDuplicatesAcrossGroups() {
    // Each posting belongs to exactly one group — verify no URL appears twice
    LocalDate old = TODAY.minusDays(10);
    List<JobPosting> all = new ArrayList<>();
    all.addAll(buildPostings("새회사", 5, null, TODAY.minusDays(1).atStartOfDay(), null));
    all.addAll(buildPostings("급회사", 5, TODAY.plusDays(3), null, old));
    all.addAll(buildPostings("상시회사", 5, TODAY.plusDays(60), null, old));

    List<AgentCandidateJobPosting> candidates = candidatesFor(Map.of(), all);

    long distinctUrls =
        candidates.stream().map(AgentCandidateJobPosting::sourceUrl).distinct().count();
    assertThat(distinctUrls).isEqualTo(candidates.size());
  }

  @Test
  void quota_finalResultNeverExceedsThirty() {
    // 50 postings from different companies, all EVERGREEN
    LocalDate old = TODAY.minusDays(10);
    List<JobPosting> all = buildPostings("회사", 50, TODAY.plusDays(60), null, old);

    List<AgentCandidateJobPosting> candidates = candidatesFor(Map.of(), all);

    assertThat(candidates.size()).isLessThanOrEqualTo(30);
  }

  // ---------------------------------------------------------------------------
  // Diversity tests
  // ---------------------------------------------------------------------------

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
  void diversity_targetedCompanyAllowedUpToThree() {
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
    assertThat(naverCount).isGreaterThan(2).isLessThanOrEqualTo(3);
  }

  @Test
  void diversity_companyLimitRespectedDuringFillUp() {
    // Fill-up phase must still obey the per-company cap
    LocalDate old = TODAY.minusDays(10);

    // Only 1 NEW (quota=12): forces fill-up. Fill-up pool has 4 more from same company.
    List<JobPosting> all = new ArrayList<>();
    all.add(
        posting(
            "채움회사",
            "NEW개발자",
            "https://e.com/fill-new",
            null,
            TODAY.minusDays(1).atStartOfDay(),
            null));
    all.add(posting("채움회사", "EVER1", "https://e.com/fill1", null, null, old));
    all.add(posting("채움회사", "EVER2", "https://e.com/fill2", null, null, old));
    all.add(posting("채움회사", "EVER3", "https://e.com/fill3", null, null, old));
    all.add(posting("채움회사", "EVER4", "https://e.com/fill4", null, null, old));

    List<AgentCandidateJobPosting> candidates = candidatesFor(Map.of(), all);

    long companyCount = candidates.stream().filter(c -> "채움회사".equals(c.companyName())).count();
    assertThat(companyCount).isLessThanOrEqualTo(2);
  }

  // ---------------------------------------------------------------------------
  // Urgency bonus unit tests
  // ---------------------------------------------------------------------------

  @Test
  void urgencyBonus_deadlineToday_returnsCritical() {
    int bonus = briefingService.computeUrgencyBonus(TODAY, TODAY);
    assertThat(bonus).isEqualTo(25);
  }

  @Test
  void urgencyBonus_deadlineTomorrow_returnsCritical() {
    int bonus = briefingService.computeUrgencyBonus(TODAY.plusDays(1), TODAY);
    assertThat(bonus).isEqualTo(25);
  }

  @Test
  void urgencyBonus_deadlineInThreeDays_returnsNear() {
    int bonus = briefingService.computeUrgencyBonus(TODAY.plusDays(3), TODAY);
    assertThat(bonus).isEqualTo(15);
  }

  @Test
  void urgencyBonus_deadlineInFourDays_returnsZero() {
    int bonus = briefingService.computeUrgencyBonus(TODAY.plusDays(4), TODAY);
    assertThat(bonus).isEqualTo(0);
  }

  @Test
  void urgencyBonus_nullDeadline_returnsZero() {
    int bonus = briefingService.computeUrgencyBonus(null, TODAY);
    assertThat(bonus).isEqualTo(0);
  }

  // ---------------------------------------------------------------------------
  // Exposure penalty unit tests
  // ---------------------------------------------------------------------------

  @Test
  void exposurePenalty_exposedYesterday_returnsFortyPenalty() {
    String url = "https://e.com/p1";
    Map<String, LocalDate> map = Map.of("https://e.com/p1", TODAY.minusDays(1));
    assertThat(briefingService.computeExposurePenalty(url, map, TODAY)).isEqualTo(40);
  }

  @Test
  void exposurePenalty_exposedThreeDaysAgo_returnsTwentyFivePenalty() {
    String url = "https://e.com/p2";
    Map<String, LocalDate> map = Map.of("https://e.com/p2", TODAY.minusDays(3));
    assertThat(briefingService.computeExposurePenalty(url, map, TODAY)).isEqualTo(25);
  }

  @Test
  void exposurePenalty_exposedSixDaysAgo_returnsTenPenalty() {
    String url = "https://e.com/p3";
    Map<String, LocalDate> map = Map.of("https://e.com/p3", TODAY.minusDays(6));
    assertThat(briefingService.computeExposurePenalty(url, map, TODAY)).isEqualTo(10);
  }

  @Test
  void exposurePenalty_exposedSevenDaysAgo_returnsZero() {
    String url = "https://e.com/p4";
    Map<String, LocalDate> map = Map.of("https://e.com/p4", TODAY.minusDays(7));
    assertThat(briefingService.computeExposurePenalty(url, map, TODAY)).isEqualTo(0);
  }

  @Test
  void exposurePenalty_urlWithQueryAndFragment_matchesBaseUrl() {
    // Base URL is what we look up; exposure was recorded with a query-bearing variant.
    // Both canonicalize to https://example.com/jobs/42
    String baseUrl = "https://example.com/jobs/42";
    String withQuery = "https://example.com/jobs/42?ref=email#top";
    // Map key must be canonical too
    Map<String, LocalDate> map = Map.of("https://example.com/jobs/42", TODAY.minusDays(2));

    // baseUrl → canonical is the same key → penalty 25
    assertThat(briefingService.computeExposurePenalty(baseUrl, map, TODAY)).isEqualTo(25);
    // withQuery → canonical strips query/fragment → same key → penalty 25
    assertThat(briefingService.computeExposurePenalty(withQuery, map, TODAY)).isEqualTo(25);
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

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
    when(agentClient.generate(captor.capture())).thenReturn(mockAgentResponse);

    com.briefy.domain.briefing.entity.BriefingReport mockReport =
        mock(com.briefy.domain.briefing.entity.BriefingReport.class);
    when(mockReport.getId()).thenReturn(1L);
    when(briefingReportRepository.save(any())).thenReturn(mockReport);

    briefingService.generateBriefing(1L);
    return captor.getValue().candidatePool().jobPostings();
  }

  /** Creates a posting with controlled publishedAt and collectedDate for type classification. */
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

  /**
   * Builds {@code count} postings from companies named "{prefix}0", "{prefix}1", … Each posting
   * gets a unique URL so company diversity limits do not interfere with count assertions.
   */
  private static List<JobPosting> buildPostings(
      String companyPrefix,
      int count,
      LocalDate deadline,
      LocalDateTime publishedAt,
      LocalDate collectedDate) {
    return IntStream.range(0, count)
        .mapToObj(
            i ->
                posting(
                    companyPrefix + i,
                    "개발자" + i,
                    "https://e.com/" + companyPrefix + i,
                    deadline,
                    publishedAt,
                    collectedDate))
        .toList();
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
