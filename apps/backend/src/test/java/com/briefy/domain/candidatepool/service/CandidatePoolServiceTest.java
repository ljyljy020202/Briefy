package com.briefy.domain.candidatepool.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.briefy.domain.candidatepool.dto.CandidatePoolUpsertResult;
import com.briefy.domain.candidatepool.dto.CollectedJobPostingData;
import com.briefy.domain.candidatepool.entity.JobPosting;
import com.briefy.domain.candidatepool.entity.JobPostingAnalysis;
import com.briefy.domain.candidatepool.entity.JobPostingSource;
import com.briefy.domain.candidatepool.entity.analysis.ClassificationStatus;
import com.briefy.domain.candidatepool.repository.JobPostingAnalysisRepository;
import com.briefy.domain.candidatepool.repository.JobPostingRepository;
import com.briefy.domain.candidatepool.repository.JobPostingSourceRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CandidatePoolServiceTest {

  @Mock private JobPostingRepository jobPostingRepository;
  @Mock private JobPostingSourceRepository jobPostingSourceRepository;
  @Mock private JobPostingAnalysisRepository jobPostingAnalysisRepository;

  private CandidatePoolService candidatePoolService;

  private static final LocalDate COLLECTED_DATE = LocalDate.of(2026, 6, 30);
  private static final String CLASSIFIER_VERSION = "1.0.0";

  @BeforeEach
  void setUp() {
    candidatePoolService =
        new CandidatePoolService(
            jobPostingRepository, jobPostingSourceRepository,
            jobPostingAnalysisRepository, CLASSIFIER_VERSION);
    // 분석 관련 기본 stub — 기존 테스트에서 분석 로직을 무시할 수 있도록 lenient 설정
    lenient()
        .when(jobPostingAnalysisRepository.findByJobPostingId(any()))
        .thenReturn(Optional.empty());
    lenient()
        .when(jobPostingAnalysisRepository.save(any(JobPostingAnalysis.class)))
        .thenAnswer(inv -> inv.getArgument(0));
  }

  /** Creates CollectedJobPostingData with no Agent-provided keys (all null). */
  private CollectedJobPostingData data(String url, String contentHash) {
    return new CollectedJobPostingData(
        "백엔드 개발자",
        "네이버",
        "원티드",
        url,
        "서울",
        LocalDate.of(2026, 7, 15),
        "채용 공고 설명",
        "[\"백엔드 개발자\"]",
        "[\"Java\", \"Spring Boot\"]",
        "정규직",
        "신입",
        contentHash,
        null,
        null,
        null,
        null,
        null);
  }

  private JobPosting existingPosting(String url) {
    return JobPosting.create(
        "백엔드 개발자",
        "네이버",
        "원티드",
        url,
        "서울",
        null,
        "기존 설명",
        null,
        null,
        null,
        null,
        "oldhash",
        LocalDate.of(2026, 6, 1),
        null);
  }

  @Test
  void upsertJobPostings_newPosting_savedAndCountsAreCorrect() {
    CollectedJobPostingData posting = data("https://example.com/job/1", "hash1");
    stubSourceNotFound("원티드", "https://example.com/job/1");
    when(jobPostingRepository.findFirstByUrl("https://example.com/job/1"))
        .thenReturn(Optional.empty());
    when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(inv -> inv.getArgument(0));

    CandidatePoolUpsertResult result =
        candidatePoolService.upsertJobPostings(List.of(posting), COLLECTED_DATE);

    assertThat(result.collectedCount()).isEqualTo(1);
    assertThat(result.savedCount()).isEqualTo(1);
    assertThat(result.duplicateCount()).isEqualTo(0);
    assertThat(result.newIds()).hasSize(1);
    assertThat(result.updatedIds()).isEmpty();
    verify(jobPostingRepository).save(any(JobPosting.class));
  }

  @Test
  void upsertJobPostings_exactSourceRecord_touchedAndCountedAsDuplicate() {
    String url = "https://example.com/job/2";
    String sourceKey = CandidatePoolService.buildSourceRecordKey("원티드", null, url);
    JobPostingSource existingSource = buildMockSource();
    when(jobPostingSourceRepository.findBySourceRecordKey(sourceKey))
        .thenReturn(Optional.of(existingSource));

    CollectedJobPostingData incoming = data(url, "newhash");
    CandidatePoolUpsertResult result =
        candidatePoolService.upsertJobPostings(List.of(incoming), COLLECTED_DATE);

    assertThat(result.savedCount()).isEqualTo(0);
    assertThat(result.duplicateCount()).isEqualTo(1);
    verify(jobPostingRepository, never()).save(any());
  }

  @Test
  void upsertJobPostings_canonicalByUrl_attachesNewSource() {
    String url = "https://example.com/job/3";
    JobPosting existing = existingPosting(url);
    LocalDate newDeadline = LocalDate.of(2026, 7, 20);

    CollectedJobPostingData incoming =
        new CollectedJobPostingData(
            "백엔드 개발자",
            "네이버",
            "원티드",
            url,
            "서울",
            newDeadline,
            "새 공고 설명",
            null,
            null,
            null,
            null,
            "newhash",
            null,
            null,
            null,
            null,
            null);

    stubSourceNotFound("원티드", url);
    when(jobPostingRepository.findFirstByUrl(url)).thenReturn(Optional.of(existing));

    CandidatePoolUpsertResult result =
        candidatePoolService.upsertJobPostings(List.of(incoming), COLLECTED_DATE);

    assertThat(result.savedCount()).isEqualTo(0);
    assertThat(result.duplicateCount()).isEqualTo(1);
    assertThat(existing.getDeadline()).isEqualTo(newDeadline);
    assertThat(existing.getDescription()).isEqualTo("새 공고 설명");
    assertThat(existing.getContentHash()).isEqualTo("newhash");
    assertThat(existing.getCollectedDate()).isEqualTo(COLLECTED_DATE);
    verify(jobPostingRepository, never()).save(any());
    verify(jobPostingSourceRepository).save(any(JobPostingSource.class));
  }

  @Test
  void upsertJobPostings_canonicalByFingerprint_attachesNewSource() {
    String url = "https://example.com/job/4";
    String contentHash = "contenthash4";
    String canonicalFingerprint = "fingerprint4";
    JobPosting existing = existingPosting("https://example.com/original-url");

    stubSourceNotFound("원티드", url);
    when(jobPostingRepository.findFirstByCanonicalFingerprint(canonicalFingerprint))
        .thenReturn(Optional.of(existing));

    CollectedJobPostingData incoming =
        new CollectedJobPostingData(
            "백엔드 개발자",
            "네이버",
            "원티드",
            url,
            "서울",
            LocalDate.of(2026, 7, 15),
            "채용 공고 설명",
            "[\"백엔드 개발자\"]",
            "[\"Java\", \"Spring Boot\"]",
            "정규직",
            "신입",
            contentHash,
            null,
            null,
            null,
            canonicalFingerprint,
            null);

    CandidatePoolUpsertResult result =
        candidatePoolService.upsertJobPostings(List.of(incoming), COLLECTED_DATE);

    assertThat(result.savedCount()).isEqualTo(0);
    assertThat(result.duplicateCount()).isEqualTo(1);
    verify(jobPostingRepository, never()).save(any());
    verify(jobPostingSourceRepository).save(any(JobPostingSource.class));
  }

  @Test
  void upsertJobPostings_nullCanonicalFingerprint_skipsFingerprintLookup() {
    String url = "https://example.com/job/4b";
    JobPosting existing = existingPosting(url);

    stubSourceNotFound("원티드", url);
    when(jobPostingRepository.findFirstByUrl(url)).thenReturn(Optional.of(existing));

    CollectedJobPostingData incoming = data(url, "somehash");

    CandidatePoolUpsertResult result =
        candidatePoolService.upsertJobPostings(List.of(incoming), COLLECTED_DATE);

    assertThat(result.duplicateCount()).isEqualTo(1);
    verify(jobPostingRepository, never()).findFirstByCanonicalFingerprint(any());
  }

  @Test
  void upsertJobPostings_agentProvidedSourceRecordKey_usedWithoutRecomputation() {
    String agentKey = "agent_provided_key_" + "a".repeat(44);
    CollectedJobPostingData incoming =
        new CollectedJobPostingData(
            "백엔드 개발자",
            "네이버",
            "원티드",
            "https://example.com/job/5a",
            "서울",
            LocalDate.of(2026, 7, 15),
            "설명",
            null,
            null,
            null,
            null,
            "hash5a",
            null,
            agentKey,
            null,
            null,
            null);

    when(jobPostingSourceRepository.findBySourceRecordKey(agentKey)).thenReturn(Optional.empty());
    when(jobPostingRepository.findFirstByUrl("https://example.com/job/5a"))
        .thenReturn(Optional.empty());
    when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(inv -> inv.getArgument(0));

    candidatePoolService.upsertJobPostings(List.of(incoming), COLLECTED_DATE);

    verify(jobPostingSourceRepository).findBySourceRecordKey(agentKey);
  }

  @Test
  void upsertJobPostings_mixedBatch_correctCountsAndSaveCallCount() {
    CollectedJobPostingData newPosting = data("https://example.com/job/5", "hash5");
    CollectedJobPostingData sourceDup = data("https://example.com/job/6", "hash6");

    CollectedJobPostingData fingerprintDup =
        new CollectedJobPostingData(
            "백엔드 개발자",
            "네이버",
            "원티드",
            "https://example.com/job/7",
            "서울",
            LocalDate.of(2026, 7, 15),
            "채용 공고 설명",
            "[\"백엔드 개발자\"]",
            "[\"Java\", \"Spring Boot\"]",
            "정규직",
            "신입",
            "hash7",
            null,
            null,
            null,
            "fingerprint7",
            null);

    stubSourceNotFound("원티드", "https://example.com/job/5");
    when(jobPostingRepository.findFirstByUrl("https://example.com/job/5"))
        .thenReturn(Optional.empty());
    when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(inv -> inv.getArgument(0));

    String sourceKey6 =
        CandidatePoolService.buildSourceRecordKey("원티드", null, "https://example.com/job/6");
    when(jobPostingSourceRepository.findBySourceRecordKey(sourceKey6))
        .thenReturn(Optional.of(buildMockSource()));

    stubSourceNotFound("원티드", "https://example.com/job/7");
    when(jobPostingRepository.findFirstByCanonicalFingerprint("fingerprint7"))
        .thenReturn(Optional.of(existingPosting("https://example.com/other")));

    CandidatePoolUpsertResult result =
        candidatePoolService.upsertJobPostings(
            List.of(newPosting, sourceDup, fingerprintDup), COLLECTED_DATE);

    assertThat(result.collectedCount()).isEqualTo(3);
    assertThat(result.savedCount()).isEqualTo(1);
    assertThat(result.duplicateCount()).isEqualTo(2);
    verify(jobPostingRepository, times(1)).save(any(JobPosting.class));
  }

  @Test
  void upsertJobPostings_emptyList_returnsZeroCounts() {
    CandidatePoolUpsertResult result =
        candidatePoolService.upsertJobPostings(List.of(), COLLECTED_DATE);

    assertThat(result.collectedCount()).isEqualTo(0);
    assertThat(result.savedCount()).isEqualTo(0);
    assertThat(result.duplicateCount()).isEqualTo(0);
    assertThat(result.newIds()).isEmpty();
    assertThat(result.updatedIds()).isEmpty();
    assertThat(result.touchedIds()).isEmpty();
    verify(jobPostingRepository, never()).save(any());
  }

  @Test
  void upsertJobPostings_nullUrl_createsPostingWithoutSourceRecord() {
    CollectedJobPostingData noUrl =
        new CollectedJobPostingData(
            "백엔드 개발자",
            "네이버",
            "원티드",
            null,
            "서울",
            null,
            null,
            null,
            null,
            null,
            null,
            "hashNoUrl",
            null,
            null,
            null,
            null,
            null);

    when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(inv -> inv.getArgument(0));

    CandidatePoolUpsertResult result =
        candidatePoolService.upsertJobPostings(List.of(noUrl), COLLECTED_DATE);

    assertThat(result.savedCount()).isEqualTo(1);
    verify(jobPostingSourceRepository, never()).save(any());
  }

  @Test
  void findEligibleJobPostingsForBriefing_delegatesToRepository() {
    List<JobPosting> expected =
        List.of(
            JobPosting.create(
                "풀스택 개발자",
                "카카오",
                null,
                "https://example.com/job/8",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                COLLECTED_DATE,
                null));
    when(jobPostingRepository.findEligibleJobPostingsForBriefing(any(), any()))
        .thenReturn(expected);

    List<JobPosting> result =
        candidatePoolService.findEligibleJobPostingsForBriefing(COLLECTED_DATE);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).getCompany()).isEqualTo("카카오");
    verify(jobPostingRepository)
        .findEligibleJobPostingsForBriefing(COLLECTED_DATE, List.of("fixture"));
  }

  // ---------------------------------------------------------------------------
  // buildSourceRecordKey — contract tests
  // ---------------------------------------------------------------------------

  @Test
  void buildSourceRecordKey_nullUrlAndNullExternalId_returnsNull() {
    assertThat(CandidatePoolService.buildSourceRecordKey("원티드", null, null)).isNull();
  }

  @Test
  void buildSourceRecordKey_sameInputs_returnsStableKey() {
    String key1 = CandidatePoolService.buildSourceRecordKey("원티드", null, "https://example.com");
    String key2 = CandidatePoolService.buildSourceRecordKey("원티드", null, "https://example.com");
    assertThat(key1).isEqualTo(key2).hasSize(64);
  }

  @Test
  void buildSourceRecordKey_differentSources_returnsDifferentKeys() {
    String wanted = CandidatePoolService.buildSourceRecordKey("원티드", null, "https://example.com");
    String saramin = CandidatePoolService.buildSourceRecordKey("사람인", null, "https://example.com");
    assertThat(wanted).isNotEqualTo(saramin);
  }

  @Test
  void buildSourceRecordKey_nullSource_returnsDifferentKeyFromNamedSource() {
    String noSource = CandidatePoolService.buildSourceRecordKey(null, null, "https://example.com");
    String withSource =
        CandidatePoolService.buildSourceRecordKey("원티드", null, "https://example.com");
    assertThat(noSource).isNotNull().hasSize(64).isNotEqualTo(withSource);
  }

  @Test
  void buildSourceRecordKey_sourceExternalId_preferredOverUrl() {
    String withExternalId =
        CandidatePoolService.buildSourceRecordKey("원티드", "EXT-001", "https://example.com/job/1");
    String withoutExternalId =
        CandidatePoolService.buildSourceRecordKey("원티드", null, "https://example.com/job/1");
    assertThat(withExternalId).isNotEqualTo(withoutExternalId).hasSize(64);
  }

  @Test
  void buildSourceRecordKey_sameExternalIdDifferentUrls_returnsSameKey() {
    String key1 = CandidatePoolService.buildSourceRecordKey("원티드", "EXT-001", "https://url-a.com");
    String key2 = CandidatePoolService.buildSourceRecordKey("원티드", "EXT-001", "https://url-b.com");
    assertThat(key1).isEqualTo(key2);
  }

  @Test
  void buildSourceRecordKey_urlWithQueryParams_stripsQuery() {
    String clean =
        CandidatePoolService.buildSourceRecordKey("원티드", null, "https://example.com/job/1");
    String withQuery =
        CandidatePoolService.buildSourceRecordKey(
            "원티드", null, "https://example.com/job/1?ref=homepage");
    assertThat(clean).isEqualTo(withQuery);
  }

  @Test
  void buildSourceRecordKey_urlWithTrailingSlash_treatedSameAsWithout() {
    String withSlash =
        CandidatePoolService.buildSourceRecordKey("원티드", null, "https://example.com/jobs/");
    String withoutSlash =
        CandidatePoolService.buildSourceRecordKey("원티드", null, "https://example.com/jobs");
    assertThat(withSlash).isEqualTo(withoutSlash);
  }

  // ── Metadata enrichment via upsert ────────────────────────────────────────

  private CollectedJobPostingData dataWithMetadata(
      String url,
      String contentHash,
      String roles,
      String experienceLevel,
      String employmentType,
      String location) {
    return new CollectedJobPostingData(
        "백엔드 개발자",
        "네이버",
        "원티드",
        url,
        location,
        LocalDate.of(2026, 7, 15),
        "설명",
        roles,
        null,
        employmentType,
        experienceLevel,
        contentHash,
        null,
        null,
        null,
        null,
        null);
  }

  private JobPosting postingWithNullMetadata(String url) {
    return JobPosting.create(
        "백엔드 개발자",
        "네이버",
        "원티드",
        url,
        null,
        null,
        "기존 설명",
        null,
        null,
        null,
        null,
        "oldhash",
        LocalDate.of(2026, 6, 1),
        null);
  }

  @Test
  void upsertJobPostings_canonicalByUrl_enrichesNullRoles() {
    String url = "https://example.com/job/enrich1";
    JobPosting existing = postingWithNullMetadata(url);

    CollectedJobPostingData incoming =
        dataWithMetadata(url, "newhash", "[\"Backend Engineering\"]", "3년 이상", "정규직", "서울");

    stubSourceNotFound("원티드", url);
    when(jobPostingRepository.findFirstByUrl(url)).thenReturn(Optional.of(existing));

    candidatePoolService.upsertJobPostings(List.of(incoming), COLLECTED_DATE);

    assertThat(existing.getRoles()).isEqualTo("[\"Backend Engineering\"]");
    assertThat(existing.getExperienceLevel()).isEqualTo("3년 이상");
    assertThat(existing.getEmploymentType()).isEqualTo("정규직");
    assertThat(existing.getLocation()).isEqualTo("서울");
  }

  @Test
  void upsertJobPostings_canonicalByUrl_doesNotOverwriteExistingRolesWithNull() {
    String url = "https://example.com/job/enrich2";
    JobPosting existing = existingPosting(url);

    CollectedJobPostingData seedData =
        dataWithMetadata(url, "hash_seed", "[\"Backend\"]", "신입", "정규직", "서울");
    stubSourceNotFound("원티드", url);
    when(jobPostingRepository.findFirstByUrl(url)).thenReturn(Optional.of(existing));
    candidatePoolService.upsertJobPostings(List.of(seedData), COLLECTED_DATE);
    assertThat(existing.getRoles()).isEqualTo("[\"Backend\"]");

    CollectedJobPostingData nullRoles = dataWithMetadata(url, "hash2", null, null, null, null);
    when(jobPostingSourceRepository.findBySourceRecordKey(any())).thenReturn(Optional.empty());
    when(jobPostingRepository.findFirstByUrl(url)).thenReturn(Optional.of(existing));
    candidatePoolService.upsertJobPostings(List.of(nullRoles), COLLECTED_DATE);

    assertThat(existing.getRoles()).isEqualTo("[\"Backend\"]");
  }

  @Test
  void upsertJobPostings_sameContentHash_stillEnrichesMetadata() {
    String url = "https://example.com/job/enrich3";
    JobPosting existing = postingWithNullMetadata(url);

    CollectedJobPostingData incoming =
        dataWithMetadata(url, "oldhash", "[\"Backend Engineering\"]", "3년 이상", "정규직", "서울");

    stubSourceNotFound("원티드", url);
    when(jobPostingRepository.findFirstByUrl(url)).thenReturn(Optional.of(existing));

    candidatePoolService.upsertJobPostings(List.of(incoming), COLLECTED_DATE);

    assertThat(existing.getRoles()).isEqualTo("[\"Backend Engineering\"]");
    assertThat(existing.getExperienceLevel()).isEqualTo("3년 이상");
  }

  @Test
  void buildSourceRecordKey_uppercaseUrl_sameAsLowercase() {
    String upper =
        CandidatePoolService.buildSourceRecordKey("원티드", null, "HTTPS://EXAMPLE.COM/job/1");
    String lower =
        CandidatePoolService.buildSourceRecordKey("원티드", null, "https://example.com/job/1");
    assertThat(upper).isEqualTo(lower);
  }

  // ── Cross-language hash fixture ────────────────────────────────────────────

  private static final String CROSS_LANG_URL_KEY =
      "dfcbdeefa29fe693c628dbe93941ba2b10bc58cf8a6ff16f4675577b390ed00e";
  private static final String CROSS_LANG_EXT_KEY =
      "80a037a4f5b02f9d29257900f862005202cabc58b2aab0006a47b16ac64c089e";

  @Test
  void buildSourceRecordKey_url_matches_cross_language_fixture() {
    assertThat(
            CandidatePoolService.buildSourceRecordKey("원티드", null, "https://wanted.co.kr/wd/123"))
        .isEqualTo(CROSS_LANG_URL_KEY);
  }

  @Test
  void buildSourceRecordKey_externalId_matches_cross_language_fixture() {
    assertThat(CandidatePoolService.buildSourceRecordKey("점핏", "EXT-007", "https://jumpit.com/j/1"))
        .isEqualTo(CROSS_LANG_EXT_KEY);
  }

  // ── Stage 2: 변경 감지 및 분류 대상 등록 ─────────────────────────────────────

  @Test
  void sameSource_contentHashChanged_callsUpdateFromSameSource_marksUpdated() {
    String url = "https://example.com/job/change1";
    String sourceKey = CandidatePoolService.buildSourceRecordKey("원티드", null, url);

    // 기존 소스: sourceContentHash = "oldhash"
    JobPosting existingPost = existingPosting(url); // roles=null
    JobPostingSource existingSource =
        JobPostingSource.create(
            existingPost,
            "원티드",
            null,
            url,
            sourceKey,
            "oldhash",
            null,
            COLLECTED_DATE.minusDays(1));
    when(jobPostingSourceRepository.findBySourceRecordKey(sourceKey))
        .thenReturn(Optional.of(existingSource));

    // 수신 데이터: contentHash="newhash", roles 포함
    CollectedJobPostingData incoming =
        new CollectedJobPostingData(
            "백엔드 개발자",
            "네이버",
            "원티드",
            url,
            "서울",
            LocalDate.of(2026, 8, 1),
            "새 공고 설명",
            "[\"Backend\"]",
            null,
            "정규직",
            "3년 이상",
            "newhash",
            null,
            null,
            null,
            null,
            null);

    // 기존 분석 행 없음 → 새로 PENDING 생성
    when(jobPostingAnalysisRepository.findByJobPostingId(any())).thenReturn(Optional.empty());

    CandidatePoolUpsertResult result =
        candidatePoolService.upsertJobPostings(List.of(incoming), COLLECTED_DATE);

    // contentHash 변경 → updateFromSameSource() 경로 → roles 갱신
    assertThat(existingPost.getRoles()).isEqualTo("[\"Backend\"]");
    assertThat(existingPost.getExperienceLevel()).isEqualTo("3년 이상");
    assertThat(existingPost.getContentHash()).isEqualTo("newhash");
    // 저장 카운트는 duplicate (Step 1 경로)
    assertThat(result.duplicateCount()).isEqualTo(1);
    assertThat(result.savedCount()).isEqualTo(0);
    // 분석 PENDING 생성 확인
    verify(jobPostingAnalysisRepository).save(any(JobPostingAnalysis.class));
  }

  @Test
  void sameSource_contentHashUnchanged_preservesExistingRoles() {
    String url = "https://example.com/job/change2";
    String sourceKey = CandidatePoolService.buildSourceRecordKey("원티드", null, url);

    // 기존 소스: sourceContentHash = "samehash"
    JobPosting existingPost =
        JobPosting.create(
            "백엔드 개발자",
            "네이버",
            "원티드",
            url,
            "서울",
            null,
            "기존 설명",
            "[\"Backend\"]",
            null,
            "정규직",
            "3년 이상",
            "samehash",
            COLLECTED_DATE.minusDays(1),
            null);
    JobPostingSource existingSource =
        JobPostingSource.create(
            existingPost,
            "원티드",
            null,
            url,
            sourceKey,
            "samehash",
            null,
            COLLECTED_DATE.minusDays(1));
    when(jobPostingSourceRepository.findBySourceRecordKey(sourceKey))
        .thenReturn(Optional.of(existingSource));

    // 수신 데이터: contentHash="samehash", roles=null (추출 실패)
    CollectedJobPostingData incoming =
        new CollectedJobPostingData(
            "백엔드 개발자",
            "네이버",
            "원티드",
            url,
            "서울",
            null,
            null,
            null,
            null,
            null,
            null,
            "samehash",
            null,
            null,
            null,
            null,
            null);

    candidatePoolService.upsertJobPostings(List.of(incoming), COLLECTED_DATE);

    // null 값으로 기존 유효 roles 덮어쓰지 않음
    assertThat(existingPost.getRoles()).isEqualTo("[\"Backend\"]");
  }

  @Test
  void sameSource_nullDescription_preservesExistingDescription() {
    String url = "https://example.com/job/desc1";
    String sourceKey = CandidatePoolService.buildSourceRecordKey("원티드", null, url);

    JobPosting existingPost =
        JobPosting.create(
            "백엔드 개발자",
            "네이버",
            "원티드",
            url,
            "서울",
            null,
            "기존 상세 설명",
            null,
            null,
            null,
            null,
            "oldhash",
            COLLECTED_DATE.minusDays(1),
            null);
    JobPostingSource existingSource =
        JobPostingSource.create(
            existingPost,
            "원티드",
            null,
            url,
            sourceKey,
            "oldhash",
            null,
            COLLECTED_DATE.minusDays(1));
    when(jobPostingSourceRepository.findBySourceRecordKey(sourceKey))
        .thenReturn(Optional.of(existingSource));

    // 수신 데이터: contentHash 변경, description=null (추출 실패)
    CollectedJobPostingData incoming =
        new CollectedJobPostingData(
            "백엔드 개발자", "네이버", "원티드", url, "서울", null, null, null, null, null, null, "newhash", null,
            null, null, null, null);

    candidatePoolService.upsertJobPostings(List.of(incoming), COLLECTED_DATE);

    // null description으로 기존 설명 덮어쓰지 않음
    assertThat(existingPost.getDescription()).isEqualTo("기존 상세 설명");
  }

  @Test
  void analysisInputHashChanged_triggersReclassification() {
    String url = "https://example.com/job/analysis1";
    String sourceKey = CandidatePoolService.buildSourceRecordKey("원티드", null, url);

    JobPosting existingPost =
        JobPosting.create(
            "백엔드 개발자",
            "네이버",
            "원티드",
            url,
            "서울",
            null,
            "기존 설명",
            null,
            null,
            null,
            null,
            "oldhash",
            COLLECTED_DATE.minusDays(1),
            null);
    JobPostingSource existingSource =
        JobPostingSource.create(
            existingPost,
            "원티드",
            null,
            url,
            sourceKey,
            "oldhash",
            null,
            COLLECTED_DATE.minusDays(1));
    when(jobPostingSourceRepository.findBySourceRecordKey(sourceKey))
        .thenReturn(Optional.of(existingSource));

    // 기존 분석 행: 다른 hash로 SUCCEEDED 상태
    JobPostingAnalysis existingAnalysis =
        JobPostingAnalysis.pending(null, "old_analysis_hash", CLASSIFIER_VERSION);
    when(jobPostingAnalysisRepository.findByJobPostingId(any()))
        .thenReturn(Optional.of(existingAnalysis));

    // 수신 데이터: contentHash 변경, 새 경력 정보 포함 → analysisInputHash 변경 예상
    CollectedJobPostingData incoming =
        new CollectedJobPostingData(
            "백엔드 개발자",
            "네이버",
            "원티드",
            url,
            "서울",
            null,
            "기존 설명",
            "[\"Backend\"]",
            null,
            null,
            "5년 이상",
            "newhash",
            null,
            null,
            null,
            null,
            null);

    candidatePoolService.upsertJobPostings(List.of(incoming), COLLECTED_DATE);

    // 분석 hash가 변경됨 → resetForNewInput 호출됨 → PENDING 상태 확인
    assertThat(existingAnalysis.getClassificationStatus()).isEqualTo(ClassificationStatus.PENDING);
    assertThat(existingAnalysis.getClaimToken()).isNull();
  }

  @Test
  void newPosting_registeredAsPending_inNewIds() {
    CollectedJobPostingData posting = data("https://example.com/job/new1", "hash_new");
    stubSourceNotFound("원티드", "https://example.com/job/new1");
    when(jobPostingRepository.findFirstByUrl("https://example.com/job/new1"))
        .thenReturn(Optional.empty());
    when(jobPostingRepository.save(any(JobPosting.class))).thenAnswer(inv -> inv.getArgument(0));

    CandidatePoolUpsertResult result =
        candidatePoolService.upsertJobPostings(List.of(posting), COLLECTED_DATE);

    assertThat(result.newIds()).hasSize(1);
    assertThat(result.updatedIds()).isEmpty();
    assertThat(result.touchedIds()).hasSize(1);
    verify(jobPostingAnalysisRepository).save(any(JobPostingAnalysis.class));
  }

  @Test
  void crossSource_nullRoles_doesNotOverwriteExistingRoles() {
    String originalUrl = "https://wanted.co.kr/job/cross1";
    String newUrl = "https://jumpit.com/job/cross1";
    String fp = "fingerprint_cross1";

    JobPosting existingPost =
        JobPosting.create(
            "백엔드 개발자",
            "네이버",
            "원티드",
            originalUrl,
            "서울",
            null,
            "기존 설명",
            "[\"Backend\"]",
            null,
            "정규직",
            "3년 이상",
            "hash1",
            COLLECTED_DATE.minusDays(1),
            null);

    stubSourceNotFound("점핏", newUrl);
    when(jobPostingRepository.findFirstByCanonicalFingerprint(fp))
        .thenReturn(Optional.of(existingPost));

    CollectedJobPostingData crossSource =
        new CollectedJobPostingData(
            "백엔드 개발자", "네이버", "점핏", newUrl, null, null, null, null, null, null, null, "hash2", null,
            null, null, fp, null);

    candidatePoolService.upsertJobPostings(List.of(crossSource), COLLECTED_DATE);

    // 교차 소스의 null roles → 기존 roles 보존
    assertThat(existingPost.getRoles()).isEqualTo("[\"Backend\"]");
  }

  // ── Cross-language hash fixture ────────────────────────────────────────────

  private void stubSourceNotFound(String source, String url) {
    String key = CandidatePoolService.buildSourceRecordKey(source, null, url);
    when(jobPostingSourceRepository.findBySourceRecordKey(key)).thenReturn(Optional.empty());
  }

  private JobPostingSource buildMockSource() {
    JobPosting jp =
        JobPosting.create(
            "백엔드",
            "네이버",
            "원티드",
            "https://x.com",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            COLLECTED_DATE,
            null);
    return JobPostingSource.create(
        jp, "원티드", null, "https://x.com", "key", null, null, COLLECTED_DATE);
  }
}
