package com.briefy.domain.collection.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.briefy.config.CollectionProperties;
import com.briefy.domain.candidatepool.dto.CandidatePoolUpsertResult;
import com.briefy.domain.candidatepool.service.CandidatePoolPersistenceService;
import com.briefy.domain.candidatepool.service.CandidatePoolService;
import com.briefy.domain.collection.dto.DailyCollectionResult;
import com.briefy.domain.collection.entity.CollectionJob;
import com.briefy.domain.collection.entity.CollectionTriggerType;
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
class DailyCollectionPartialFailureTest {

  @Mock private CollectionJobService collectionJobService;
  @Mock private UserBriefingPreferenceRepository userBriefingPreferenceRepository;
  @Mock private AgentClient agentClient;
  @Mock private CandidatePoolService candidatePoolService;
  @Mock private CandidatePoolPersistenceService candidatePoolPersistenceService;
  @Mock private CompanyRepository companyRepository;
  @Mock private CompanyAliasRepository companyAliasRepository;
  @Mock private CompanySourceRepository companySourceRepository;

  private static final CollectionProperties PROPS =
      new CollectionProperties(7, 300, 100, 100, 500, 2, 5, 3);

  private final CompanyNameNormalizer normalizer = new CompanyNameNormalizer();
  private DailyCollectionService svc;
  private static final LocalDate TEST_DATE = LocalDate.of(2026, 8, 1);

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
    when(collectionJobService.claimForProcessing(any())).thenReturn(true);
    when(userBriefingPreferenceRepository.findAllByCategoryCodeAndActiveTrue(
            BriefingCategoryCode.JOB_POSTING))
        .thenReturn(List.of());
  }

  private CollectionJob pendingJob() {
    return CollectionJob.createPending(
        TEST_DATE, "[\"JOB_POSTING\"]", CollectionTriggerType.MANUAL);
  }

  private AgentCollectionResponse responseWithOutcomes(List<AgentSourceOutcome> outcomes) {
    return new AgentCollectionResponse(
        null,
        TEST_DATE.toString(),
        List.of(),
        List.of(),
        List.of(),
        new AgentCollectionStats(0, 0, 0, 0, 0, 0, 0),
        outcomes,
        List.of());
  }

  // 1. Agent 422 → 재시도 없음 (non-retryable 4xx)
  @Test
  void agentReturns422_nonRetryable_propagatesImmediately() {
    when(collectionJobService.createOrGetForDate(any(), any(), any())).thenReturn(pendingJob());
    when(agentClient.triggerDailyCollection(any(), anyInt(), anyInt()))
        .thenThrow(
            new BusinessException(
                ErrorCode.AGENT_SERVER_ERROR, "Agent returned : 422: Unprocessable Entity"));

    DailyCollectionResult result = svc.triggerDailyCollection(TEST_DATE, List.of("JOB_POSTING"));

    assertThat(result.status()).isEqualTo("FAILED");
    verify(candidatePoolPersistenceService, never()).saveAtomically(any(), any());
  }

  // 2. Agent timeout → retry exhausted → FAILED
  @Test
  void agentTimeout_retriesExhausted_jobMarkedFailed() {
    when(collectionJobService.createOrGetForDate(any(), any(), any())).thenReturn(pendingJob());
    when(agentClient.triggerDailyCollection(any(), anyInt(), anyInt()))
        .thenThrow(new BusinessException(ErrorCode.AGENT_SERVER_ERROR, "Agent server error"));

    DailyCollectionResult result = svc.triggerDailyCollection(TEST_DATE, List.of("JOB_POSTING"));

    assertThat(result.status()).isEqualTo("FAILED");
    verify(collectionJobService).markFailed(any(), anyString());
  }

  // 3. 저장 실패 → CollectionJob FAILED (별도 TX 확인: markFailed 호출됨)
  @Test
  void persistenceFailure_jobMarkedFailed_separateTransaction() {
    when(collectionJobService.createOrGetForDate(any(), any(), any())).thenReturn(pendingJob());
    AgentCollectionResponse resp =
        responseWithOutcomes(List.of(new AgentSourceOutcome("saramin", true, 5, null)));
    when(agentClient.triggerDailyCollection(any(), anyInt(), anyInt())).thenReturn(resp);
    when(candidatePoolPersistenceService.saveAtomically(any(), any()))
        .thenThrow(new RuntimeException("DB error"));

    DailyCollectionResult result = svc.triggerDailyCollection(TEST_DATE, List.of("JOB_POSTING"));

    assertThat(result.status()).isEqualTo("FAILED");
    verify(collectionJobService).markFailed(any(), anyString());
  }

  // 4. PARTIAL_SUCCESS 저장 후 기존 Candidate Pool 유지 (삭제 없음)
  @Test
  void partialSuccess_existingCandidatePoolPreserved() {
    when(collectionJobService.createOrGetForDate(any(), any(), any())).thenReturn(pendingJob());
    AgentCollectionResponse resp =
        responseWithOutcomes(
            List.of(
                new AgentSourceOutcome("saramin", true, 3, null),
                new AgentSourceOutcome("jasoseol", false, 0, "timeout")));
    when(agentClient.triggerDailyCollection(any(), anyInt(), anyInt())).thenReturn(resp);
    when(candidatePoolPersistenceService.saveAtomically(any(), any()))
        .thenReturn(CandidatePoolUpsertResult.of(0, 3, 0));

    DailyCollectionResult result = svc.triggerDailyCollection(TEST_DATE, List.of("JOB_POSTING"));

    assertThat(result.status()).isEqualTo("PARTIAL_SUCCESS");
    assertThat(result.outcome()).isEqualTo("PARTIAL_SUCCESS");
    // saveAtomically 호출 → 삭제 없이 upsert만 수행
    verify(candidatePoolPersistenceService).saveAtomically(any(), any());
  }

  // 5. 전체 실패에서도 기존 Candidate Pool 유지 (저장 호출 없음)
  @Test
  void allSourcesFailed_noSaveAttempted_existingPoolUntouched() {
    when(collectionJobService.createOrGetForDate(any(), any(), any())).thenReturn(pendingJob());
    AgentCollectionResponse resp =
        responseWithOutcomes(
            List.of(
                new AgentSourceOutcome("saramin", false, 0, "http_503"),
                new AgentSourceOutcome("jasoseol", false, 0, "timeout")));
    when(agentClient.triggerDailyCollection(any(), anyInt(), anyInt())).thenReturn(resp);

    DailyCollectionResult result = svc.triggerDailyCollection(TEST_DATE, List.of("JOB_POSTING"));

    assertThat(result.status()).isEqualTo("FAILED");
    verify(candidatePoolPersistenceService, never()).saveAtomically(any(), any());
  }

  // 6. 모든 소스 성공 → COMPLETED
  @Test
  void allSourcesSucceed_outcomeIsCompleted() {
    when(collectionJobService.createOrGetForDate(any(), any(), any())).thenReturn(pendingJob());
    AgentCollectionResponse resp =
        responseWithOutcomes(
            List.of(
                new AgentSourceOutcome("saramin", true, 10, null),
                new AgentSourceOutcome("jasoseol", true, 5, null)));
    when(agentClient.triggerDailyCollection(any(), anyInt(), anyInt())).thenReturn(resp);
    when(candidatePoolPersistenceService.saveAtomically(any(), any()))
        .thenReturn(CandidatePoolUpsertResult.of(0, 15, 0));

    DailyCollectionResult result = svc.triggerDailyCollection(TEST_DATE, List.of("JOB_POSTING"));

    assertThat(result.status()).isEqualTo("COMPLETED");
    assertThat(result.outcome()).isEqualTo("COMPLETED");
    verify(collectionJobService).markCompleted(any(), anyInt(), anyInt(), anyInt());
  }

  // 7. 일부 성공 → PARTIAL_SUCCESS
  @Test
  void someSourcesFail_outcomeIsPartialSuccess() {
    when(collectionJobService.createOrGetForDate(any(), any(), any())).thenReturn(pendingJob());
    AgentCollectionResponse resp =
        responseWithOutcomes(
            List.of(
                new AgentSourceOutcome("saramin", true, 7, null),
                new AgentSourceOutcome("fixture", false, 0, "connection_error")));
    when(agentClient.triggerDailyCollection(any(), anyInt(), anyInt())).thenReturn(resp);
    when(candidatePoolPersistenceService.saveAtomically(any(), any()))
        .thenReturn(CandidatePoolUpsertResult.of(0, 7, 0));

    DailyCollectionResult result = svc.triggerDailyCollection(TEST_DATE, List.of("JOB_POSTING"));

    assertThat(result.status()).isEqualTo("PARTIAL_SUCCESS");
    verify(collectionJobService).markPartialSuccess(any(), anyInt(), anyInt(), anyInt(), any());
    verify(candidatePoolPersistenceService).saveAtomically(any(), any());
  }

  // 8. 모두 실패 → FAILED (저장 호출 없음)
  @Test
  void allSourcesFailed_outcomeIsFailedNoSaveCall() {
    when(collectionJobService.createOrGetForDate(any(), any(), any())).thenReturn(pendingJob());
    AgentCollectionResponse resp =
        responseWithOutcomes(List.of(new AgentSourceOutcome("saramin", false, 0, "http_503")));
    when(agentClient.triggerDailyCollection(any(), anyInt(), anyInt())).thenReturn(resp);

    DailyCollectionResult result = svc.triggerDailyCollection(TEST_DATE, List.of("JOB_POSTING"));

    assertThat(result.status()).isEqualTo("FAILED");
    verify(candidatePoolPersistenceService, never()).saveAtomically(any(), any());
    verify(collectionJobService).markFailed(any(), any());
  }
}
