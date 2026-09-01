package com.briefy.domain.collection.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.briefy.config.CollectionProperties;
import com.briefy.domain.candidatepool.dto.CandidatePoolUpsertResult;
import com.briefy.domain.candidatepool.service.CandidatePoolPersistenceService;
import com.briefy.domain.candidatepool.service.CandidatePoolService;
import com.briefy.domain.collection.dto.DailyCollectionResult;
import com.briefy.domain.collection.entity.CollectionJob;
import com.briefy.domain.collection.entity.CollectionJobStatus;
import com.briefy.domain.company.repository.CompanyAliasRepository;
import com.briefy.domain.company.repository.CompanyRepository;
import com.briefy.domain.company.repository.CompanySourceRepository;
import com.briefy.domain.company.service.CompanyNameNormalizer;
import com.briefy.domain.preference.entity.BriefingCategoryCode;
import com.briefy.domain.preference.repository.UserBriefingPreferenceRepository;
import com.briefy.global.exception.BusinessException;
import com.briefy.global.exception.ErrorCode;
import com.briefy.infra.agent.AgentClient;
import com.briefy.infra.agent.dto.AgentCollectionResponse;
import com.briefy.infra.agent.dto.AgentCollectionStats;
import com.briefy.infra.agent.dto.AgentSourceOutcome;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CollectionJobRetryTest {

  @Mock private CollectionJobService collectionJobService;
  @Mock private UserBriefingPreferenceRepository userBriefingPreferenceRepository;
  @Mock private AgentClient agentClient;
  @Mock private CandidatePoolService candidatePoolService;
  @Mock private CandidatePoolPersistenceService candidatePoolPersistenceService;
  @Mock private CompanyRepository companyRepository;
  @Mock private CompanyAliasRepository companyAliasRepository;
  @Mock private CompanySourceRepository companySourceRepository;

  private static final int JOB_MAX_RETRY_COUNT = 3;
  private static final CollectionProperties PROPS =
      new CollectionProperties(7, 300, 100, 100, 500, 2, 5, JOB_MAX_RETRY_COUNT);

  private final CompanyNameNormalizer normalizer = new CompanyNameNormalizer();
  private DailyCollectionService svc;
  private static final LocalDate TEST_DATE = LocalDate.of(2026, 8, 1);
  private static final Long JOB_ID = 99L;

  @BeforeEach
  void setUp() {
    svc =
        new DailyCollectionService(
            collectionJobService,
            userBriefingPreferenceRepository,
            agentClient,
            candidatePoolService,
            candidatePoolPersistenceService,
            companyRepository,
            companyAliasRepository,
            companySourceRepository,
            normalizer,
            PROPS);
    when(companyRepository.findActiveByNormalizedNames(any())).thenReturn(List.of());
    when(companyAliasRepository.findAllByNormalizedAliasIn(any())).thenReturn(List.of());
    when(companySourceRepository.findActiveByCompanyIds(any(), any())).thenReturn(List.of());
    when(userBriefingPreferenceRepository.findAllByCategoryCodeAndActiveTrue(
            BriefingCategoryCode.JOB_POSTING))
        .thenReturn(List.of());
  }

  private CollectionJob failedJob(int retryCount) {
    CollectionJob job = mock(CollectionJob.class);
    when(job.getId()).thenReturn(JOB_ID);
    when(job.getStatus()).thenReturn(CollectionJobStatus.FAILED);
    when(job.getCollectionDate()).thenReturn(TEST_DATE);
    when(job.getRetryCount()).thenReturn(retryCount);
    return job;
  }

  private CollectionJob jobWithStatus(CollectionJobStatus status) {
    CollectionJob job = mock(CollectionJob.class);
    when(job.getId()).thenReturn(JOB_ID);
    when(job.getStatus()).thenReturn(status);
    return job;
  }

  private AgentCollectionResponse successResponse() {
    return new AgentCollectionResponse(
        JOB_ID,
        TEST_DATE.toString(),
        List.of(),
        List.of(),
        List.of(),
        new AgentCollectionStats(0, 0, 0, 0, 0, 0, 0),
        List.of(new AgentSourceOutcome("saramin", true, 0, null)),
        List.of());
  }

  // 1. FAILED 상태 job retry → retryCount 증가 후 재실행
  @Test
  void retryFailedJob_claimsAndExecutes() {
    CollectionJob job = failedJob(1);
    when(collectionJobService.findOrThrow(JOB_ID)).thenReturn(job);
    when(collectionJobService.claimForRetry(JOB_ID, JOB_MAX_RETRY_COUNT)).thenReturn(true);
    when(agentClient.triggerDailyCollection(any(), anyInt(), anyInt()))
        .thenReturn(successResponse());
    when(candidatePoolPersistenceService.saveAtomically(any(), any()))
        .thenReturn(CandidatePoolUpsertResult.of(0, 0, 0));

    DailyCollectionResult result = svc.retryCollectionJob(JOB_ID);

    assertThat(result.collectionJobId()).isEqualTo(JOB_ID);
    assertThat(result.status()).isEqualTo("COMPLETED");
  }

  // 2. 최대 retry 횟수 초과 → COLLECTION_JOB_MAX_RETRY_EXCEEDED
  @Test
  void retryFailedJob_maxRetryExceeded_throwsMaxRetryExceeded() {
    CollectionJob job = failedJob(JOB_MAX_RETRY_COUNT); // retryCount == max
    when(collectionJobService.findOrThrow(JOB_ID)).thenReturn(job);
    // claimForRetry 실패 (retryCount >= maxRetries → DB update returns 0)
    when(collectionJobService.claimForRetry(JOB_ID, JOB_MAX_RETRY_COUNT)).thenReturn(false);
    // 재조회 시 retryCount == max → COLLECTION_JOB_MAX_RETRY_EXCEEDED
    CollectionJob freshJob = failedJob(JOB_MAX_RETRY_COUNT);
    when(collectionJobService.findOrThrow(JOB_ID)).thenReturn(job).thenReturn(freshJob);

    assertThatThrownBy(() -> svc.retryCollectionJob(JOB_ID))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            ex -> {
              BusinessException be = (BusinessException) ex;
              assertThat(be.getErrorCode()).isEqualTo(ErrorCode.COLLECTION_JOB_MAX_RETRY_EXCEEDED);
            });
  }

  // 3. COMPLETED 상태 retry 거부
  @Test
  void retryCompletedJob_throwsRetryNotAllowed() {
    CollectionJob completedJob = jobWithStatus(CollectionJobStatus.COMPLETED);
    when(collectionJobService.findOrThrow(JOB_ID)).thenReturn(completedJob);

    assertThatThrownBy(() -> svc.retryCollectionJob(JOB_ID))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            ex ->
                assertThat(((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.COLLECTION_JOB_RETRY_NOT_ALLOWED));
  }

  // 4. PARTIAL_SUCCESS 상태 retry 거부
  @Test
  void retryPartialSuccessJob_throwsRetryNotAllowed() {
    CollectionJob partialJob = jobWithStatus(CollectionJobStatus.PARTIAL_SUCCESS);
    when(collectionJobService.findOrThrow(JOB_ID)).thenReturn(partialJob);

    assertThatThrownBy(() -> svc.retryCollectionJob(JOB_ID))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            ex ->
                assertThat(((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.COLLECTION_JOB_RETRY_NOT_ALLOWED));
  }

  // 5. PROCESSING 상태 retry 거부
  @Test
  void retryProcessingJob_throwsRetryNotAllowed() {
    CollectionJob processingJob = jobWithStatus(CollectionJobStatus.PROCESSING);
    when(collectionJobService.findOrThrow(JOB_ID)).thenReturn(processingJob);

    assertThatThrownBy(() -> svc.retryCollectionJob(JOB_ID))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            ex ->
                assertThat(((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.COLLECTION_JOB_RETRY_NOT_ALLOWED));
  }

  // 6. 동시 retry 요청 → claimForRetry false → ALREADY_ACTIVE
  @Test
  void concurrentRetry_onlyOneSucceeds_otherGetsAlreadyActive() {
    CollectionJob job = failedJob(0);
    CollectionJob freshJob = failedJob(0); // retryCount < max → ALREADY_ACTIVE (not MAX_EXCEEDED)
    when(collectionJobService.findOrThrow(JOB_ID)).thenReturn(job).thenReturn(freshJob);
    // 경쟁에서 패배한 쪽: claimForRetry = false
    when(collectionJobService.claimForRetry(JOB_ID, JOB_MAX_RETRY_COUNT)).thenReturn(false);

    assertThatThrownBy(() -> svc.retryCollectionJob(JOB_ID))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            ex ->
                assertThat(((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.COLLECTION_JOB_ALREADY_ACTIVE));
  }
}
