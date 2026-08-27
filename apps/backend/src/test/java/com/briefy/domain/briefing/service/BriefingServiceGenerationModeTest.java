package com.briefy.domain.briefing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.briefy.config.BriefingProperties;
import com.briefy.domain.briefing.dto.GenerateResult;
import com.briefy.domain.briefing.entity.BriefingJob;
import com.briefy.domain.briefing.entity.BriefingJobStatus;
import com.briefy.domain.briefing.entity.BriefingReport;
import com.briefy.domain.briefing.entity.BriefingTriggerType;
import com.briefy.domain.briefing.repository.BriefingArticleRepository;
import com.briefy.domain.briefing.repository.BriefingJobRepository;
import com.briefy.domain.briefing.repository.BriefingReportRepository;
import com.briefy.domain.candidatepool.service.CandidatePoolService;
import com.briefy.domain.preference.repository.UserBriefingPreferenceRepository;
import com.briefy.domain.user.repository.UserRepository;
import com.briefy.global.exception.BusinessException;
import com.briefy.global.exception.ErrorCode;
import com.briefy.infra.agent.AgentClient;
import com.briefy.infra.agent.dto.AgentBriefingResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Tests for generation mode handling, Agent response contract validation, retry logic, and
 * retryBriefingJob flow.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BriefingServiceGenerationModeTest {

  @Mock private BriefingJobRepository briefingJobRepository;
  @Mock private BriefingReportRepository briefingReportRepository;
  @Mock private BriefingArticleRepository briefingArticleRepository;
  @Mock private UserBriefingPreferenceRepository userBriefingPreferenceRepository;
  @Mock private AgentClient agentClient;
  @Mock private CandidatePoolService candidatePoolService;
  @Mock private UserRepository userRepository;
  @Mock private BriefingJobPersistenceService briefingJobPersistenceService;
  @Mock private BriefingProperties briefingProperties;

  private BriefingService briefingService;

  private static final Long USER_ID = 1L;
  private static final Long JOB_ID = 10L;

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
            briefingProperties);

    when(briefingArticleRepository.findRecentExposuresByUserId(any(), any())).thenReturn(List.of());
    when(userBriefingPreferenceRepository.findAllByUserIdAndActiveTrue(USER_ID))
        .thenReturn(List.of());
    when(candidatePoolService.findEligibleJobPostingsForBriefing(any())).thenReturn(List.of());
    when(briefingJobPersistenceService.findTodayReport(any(), any())).thenReturn(Optional.empty());
    when(briefingJobPersistenceService.createOrGet(any(BriefingJob.class)))
        .thenAnswer(inv -> inv.getArgument(0));
    when(briefingJobPersistenceService.claimForProcessing(any())).thenReturn(true);
    when(briefingJobRepository.findById(any()))
        .thenReturn(Optional.of(BriefingJob.createManual(USER_ID, LocalDate.now())));
    when(briefingJobPersistenceService.saveReportAndComplete(
            any(), any(BriefingReport.class), any(), any()))
        .thenAnswer(inv -> inv.getArgument(1));

    when(briefingProperties.agentRetryMaxAttempts()).thenReturn(0);
    when(briefingProperties.agentRetryBackoffSeconds()).thenReturn(0);
    when(briefingProperties.jobMaxRetryCount()).thenReturn(3);
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private AgentBriefingResponse agentResponse(String mode, Boolean usedFallback, String reason) {
    return new AgentBriefingResponse(
        "오늘의 채용 브리핑",
        "요약",
        "## 내용\n콘텐츠",
        List.of(),
        new AgentBriefingResponse.TokenUsage(100, 50),
        mode,
        usedFallback,
        reason,
        0);
  }

  private BriefingJob failedJob(int retryCount) {
    BriefingJob job = mock(BriefingJob.class);
    when(job.getId()).thenReturn(JOB_ID);
    when(job.getUserId()).thenReturn(USER_ID);
    when(job.getStatus()).thenReturn(BriefingJobStatus.FAILED);
    when(job.getBriefingDate()).thenReturn(LocalDate.now());
    when(job.getTriggerType()).thenReturn(BriefingTriggerType.MANUAL);
    when(job.getRetryCount()).thenReturn(retryCount);
    return job;
  }

  // ---------------------------------------------------------------------------
  // A: FALLBACK 응답 → Job 성공 처리 (not FAILED)
  // ---------------------------------------------------------------------------

  @Test
  void fallbackResponseSavedAsSuccess() {
    AgentBriefingResponse fallbackResp = agentResponse("FALLBACK", true, "enrichment_failed");
    when(agentClient.generate(any(), any(Integer.class), any(Integer.class)))
        .thenReturn(fallbackResp);

    BriefingReport savedReport = mock(BriefingReport.class);
    when(savedReport.getId()).thenReturn(100L);
    when(briefingJobPersistenceService.saveReportAndComplete(
            any(), any(BriefingReport.class), any(), any()))
        .thenReturn(savedReport);

    GenerateResult result = briefingService.generateBriefing(USER_ID);

    assertThat(result.status()).isEqualTo("COMPLETED");
    assertThat(result.briefingReportId()).isEqualTo(100L);

    // Verify generationMode and fallbackReason are passed to persistence
    ArgumentCaptor<String> modeCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> reasonCaptor = ArgumentCaptor.forClass(String.class);
    verify(briefingJobPersistenceService)
        .saveReportAndComplete(
            any(), any(BriefingReport.class), modeCaptor.capture(), reasonCaptor.capture());
    assertThat(modeCaptor.getValue()).isEqualTo("FALLBACK");
    assertThat(reasonCaptor.getValue()).isEqualTo("enrichment_failed");
  }

  // ---------------------------------------------------------------------------
  // B: EMPTY 응답 → Job 성공 처리
  // ---------------------------------------------------------------------------

  @Test
  void emptyResponseSavedAsSuccess() {
    AgentBriefingResponse emptyResp = agentResponse("EMPTY", true, null);
    when(agentClient.generate(any(), any(Integer.class), any(Integer.class))).thenReturn(emptyResp);

    BriefingReport savedReport = mock(BriefingReport.class);
    when(savedReport.getId()).thenReturn(200L);
    when(briefingJobPersistenceService.saveReportAndComplete(
            any(), any(BriefingReport.class), any(), any()))
        .thenReturn(savedReport);

    GenerateResult result = briefingService.generateBriefing(USER_ID);

    assertThat(result.status()).isEqualTo("COMPLETED");
    assertThat(result.briefingReportId()).isEqualTo(200L);
  }

  // ---------------------------------------------------------------------------
  // C: Agent 응답 계약 검증 — blank title → AGENT_CONTRACT_VIOLATION
  // ---------------------------------------------------------------------------

  @Test
  void agentBlankTitle_throwsContractViolation() {
    AgentBriefingResponse badResp =
        new AgentBriefingResponse(
            "", // blank title
            "요약",
            "## 내용\n콘텐츠",
            List.of(),
            new AgentBriefingResponse.TokenUsage(0, 0),
            "LLM",
            false,
            null,
            0);
    when(agentClient.generate(any(), any(Integer.class), any(Integer.class))).thenReturn(badResp);

    assertThatThrownBy(() -> briefingService.generateBriefing(USER_ID))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            e ->
                assertThat(((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.AGENT_CONTRACT_VIOLATION));

    verify(briefingJobPersistenceService).recordFailure(any(), any());
  }

  // ---------------------------------------------------------------------------
  // D: Agent 응답 계약 검증 — blank content → AGENT_CONTRACT_VIOLATION
  // ---------------------------------------------------------------------------

  @Test
  void agentBlankContent_throwsContractViolation() {
    AgentBriefingResponse badResp =
        new AgentBriefingResponse(
            "오늘의 채용 브리핑",
            "요약",
            "", // blank content
            List.of(),
            new AgentBriefingResponse.TokenUsage(0, 0),
            "LLM",
            false,
            null,
            0);
    when(agentClient.generate(any(), any(Integer.class), any(Integer.class))).thenReturn(badResp);

    assertThatThrownBy(() -> briefingService.generateBriefing(USER_ID))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            e ->
                assertThat(((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.AGENT_CONTRACT_VIOLATION));

    verify(briefingJobPersistenceService).recordFailure(any(), any());
  }

  // ---------------------------------------------------------------------------
  // E: EMPTY 모드 → title/content 검증 없음 (빈 content 허용)
  // ---------------------------------------------------------------------------

  @Test
  void emptyMode_skipsContractValidation() {
    AgentBriefingResponse emptyResp =
        new AgentBriefingResponse(
            "오늘의 채용 브리핑",
            "요약",
            "# 오늘의 채용 브리핑\n\n## 오늘의 핵심 요약\n...",
            List.of(),
            new AgentBriefingResponse.TokenUsage(0, 0),
            "EMPTY",
            true,
            null,
            0);
    when(agentClient.generate(any(), any(Integer.class), any(Integer.class))).thenReturn(emptyResp);

    BriefingReport savedReport = mock(BriefingReport.class);
    when(savedReport.getId()).thenReturn(300L);
    when(briefingJobPersistenceService.saveReportAndComplete(
            any(), any(BriefingReport.class), any(), any()))
        .thenReturn(savedReport);

    // Should not throw
    GenerateResult result = briefingService.generateBriefing(USER_ID);
    assertThat(result.status()).isEqualTo("COMPLETED");
  }

  // ---------------------------------------------------------------------------
  // F: Agent timeout 재시도 설정 사용 확인
  // ---------------------------------------------------------------------------

  @Test
  void agentClientCalledWithRetrySettings() {
    when(briefingProperties.agentRetryMaxAttempts()).thenReturn(2);
    when(briefingProperties.agentRetryBackoffSeconds()).thenReturn(5);

    AgentBriefingResponse resp = agentResponse("LLM", false, null);
    when(agentClient.generate(any(), any(Integer.class), any(Integer.class))).thenReturn(resp);

    BriefingReport saved = mock(BriefingReport.class);
    when(saved.getId()).thenReturn(1L);
    when(briefingJobPersistenceService.saveReportAndComplete(
            any(), any(BriefingReport.class), any(), any()))
        .thenReturn(saved);

    briefingService.generateBriefing(USER_ID);

    ArgumentCaptor<Integer> maxRetriesCaptor = ArgumentCaptor.forClass(Integer.class);
    ArgumentCaptor<Integer> backoffCaptor = ArgumentCaptor.forClass(Integer.class);
    verify(agentClient).generate(any(), maxRetriesCaptor.capture(), backoffCaptor.capture());
    assertThat(maxRetriesCaptor.getValue()).isEqualTo(2);
    assertThat(backoffCaptor.getValue()).isEqualTo(5);
  }

  // ---------------------------------------------------------------------------
  // G: Agent 422 → 재시도 없이 FAILED (isNonRetryable 경로 검증)
  // ---------------------------------------------------------------------------

  @Test
  void agent422Error_marksJobFailed() {
    when(agentClient.generate(any(), any(Integer.class), any(Integer.class)))
        .thenThrow(
            new BusinessException(
                ErrorCode.AGENT_SERVER_ERROR, "Agent returned 422: Unprocessable Entity"));

    assertThatThrownBy(() -> briefingService.generateBriefing(USER_ID))
        .isInstanceOf(BusinessException.class);

    verify(briefingJobPersistenceService).recordFailure(any(), any());
    verify(briefingJobPersistenceService, never())
        .saveReportAndComplete(any(), any(BriefingReport.class), any(), any());
  }

  // ---------------------------------------------------------------------------
  // H: generationMode 저장 확인 — LLM 모드
  // ---------------------------------------------------------------------------

  @Test
  void llmMode_savedWithLlmGenerationMode() {
    AgentBriefingResponse llmResp = agentResponse("LLM", false, null);
    when(agentClient.generate(any(), any(Integer.class), any(Integer.class))).thenReturn(llmResp);

    BriefingReport saved = mock(BriefingReport.class);
    when(saved.getId()).thenReturn(1L);
    when(briefingJobPersistenceService.saveReportAndComplete(
            any(), any(BriefingReport.class), any(), any()))
        .thenReturn(saved);

    briefingService.generateBriefing(USER_ID);

    ArgumentCaptor<String> modeCaptor = ArgumentCaptor.forClass(String.class);
    verify(briefingJobPersistenceService)
        .saveReportAndComplete(any(), any(BriefingReport.class), modeCaptor.capture(), any());
    assertThat(modeCaptor.getValue()).isEqualTo("LLM");
  }

  // ---------------------------------------------------------------------------
  // I: generationMode 저장 확인 — null mode (older Agent) → defaults to LLM
  // ---------------------------------------------------------------------------

  @Test
  void nullGenerationMode_defaultsToLlm() {
    AgentBriefingResponse nullModeResp = agentResponse(null, null, null);
    when(agentClient.generate(any(), any(Integer.class), any(Integer.class)))
        .thenReturn(nullModeResp);

    BriefingReport saved = mock(BriefingReport.class);
    when(saved.getId()).thenReturn(1L);
    when(briefingJobPersistenceService.saveReportAndComplete(
            any(), any(BriefingReport.class), any(), any()))
        .thenReturn(saved);

    briefingService.generateBriefing(USER_ID);

    ArgumentCaptor<String> modeCaptor = ArgumentCaptor.forClass(String.class);
    verify(briefingJobPersistenceService)
        .saveReportAndComplete(any(), any(BriefingReport.class), modeCaptor.capture(), any());
    assertThat(modeCaptor.getValue()).isEqualTo("LLM"); // generationModeOrDefault()
  }

  // ---------------------------------------------------------------------------
  // J: retryBriefingJob — FAILED retry 동일 jobId 사용
  // ---------------------------------------------------------------------------

  @Test
  void retryReusesSameJobId() {
    BriefingJob job = failedJob(1);
    when(briefingJobPersistenceService.findJobById(JOB_ID)).thenReturn(job);
    when(briefingReportRepository.existsByBriefingJobId(JOB_ID)).thenReturn(false);
    when(briefingJobPersistenceService.claimForRetry(JOB_ID, 3)).thenReturn(true);

    AgentBriefingResponse resp = agentResponse("LLM", false, null);
    when(agentClient.generate(any(), any(Integer.class), any(Integer.class))).thenReturn(resp);

    BriefingReport savedReport = mock(BriefingReport.class);
    when(savedReport.getId()).thenReturn(99L);
    when(briefingJobPersistenceService.saveReportAndComplete(
            any(), any(BriefingReport.class), any(), any()))
        .thenReturn(savedReport);

    when(briefingJobRepository.findById(JOB_ID))
        .thenReturn(Optional.of(BriefingJob.createManual(USER_ID, LocalDate.now())));

    GenerateResult result = briefingService.retryBriefingJob(JOB_ID);

    assertThat(result.status()).isEqualTo("COMPLETED");
    assertThat(result.jobId()).isEqualTo(JOB_ID);
  }

  // ---------------------------------------------------------------------------
  // K: retryBriefingJob — 최대 retry 횟수 초과 → BRIEFING_JOB_MAX_RETRY_EXCEEDED
  // ---------------------------------------------------------------------------

  @Test
  void retryMaxExceeded_throwsMaxRetryExceeded() {
    BriefingJob job = failedJob(3); // retryCount == jobMaxRetryCount
    when(briefingJobPersistenceService.findJobById(JOB_ID)).thenReturn(job);
    when(briefingReportRepository.existsByBriefingJobId(JOB_ID)).thenReturn(false);
    // claimForRetry fails → retryCount >= max
    when(briefingJobPersistenceService.claimForRetry(JOB_ID, 3)).thenReturn(false);

    BriefingJob freshJob = failedJob(3); // still maxed out after claim failure
    when(briefingJobPersistenceService.findJobById(JOB_ID)).thenReturn(job).thenReturn(freshJob);

    assertThatThrownBy(() -> briefingService.retryBriefingJob(JOB_ID))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            e ->
                assertThat(((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.BRIEFING_JOB_MAX_RETRY_EXCEEDED));
  }

  // ---------------------------------------------------------------------------
  // L: retryBriefingJob — 이미 Report가 있는 FAILED Job retry 차단
  // ---------------------------------------------------------------------------

  @Test
  void retryBlockedWhenReportExists() {
    BriefingJob job = failedJob(1);
    when(briefingJobPersistenceService.findJobById(JOB_ID)).thenReturn(job);
    when(briefingReportRepository.existsByBriefingJobId(JOB_ID)).thenReturn(true); // has report

    assertThatThrownBy(() -> briefingService.retryBriefingJob(JOB_ID))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            e ->
                assertThat(((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.BRIEFING_JOB_HAS_EXISTING_REPORT));

    verify(briefingJobPersistenceService, never()).claimForRetry(any(), any(Integer.class));
  }

  // ---------------------------------------------------------------------------
  // M: retryBriefingJob — COMPLETED 상태 retry 거부
  // ---------------------------------------------------------------------------

  @Test
  void retryRejectedForCompletedJob() {
    BriefingJob completedJob = mock(BriefingJob.class);
    when(completedJob.getStatus()).thenReturn(BriefingJobStatus.COMPLETED);
    when(briefingJobPersistenceService.findJobById(JOB_ID)).thenReturn(completedJob);

    assertThatThrownBy(() -> briefingService.retryBriefingJob(JOB_ID))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            e ->
                assertThat(((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.BRIEFING_JOB_RETRY_NOT_ALLOWED));

    verify(briefingReportRepository, never()).existsByBriefingJobId(any());
  }

  // ---------------------------------------------------------------------------
  // N: retryBriefingJob — claimForRetry 실패하지만 retryCount < max → ALREADY_PROCESSING
  // ---------------------------------------------------------------------------

  @Test
  void concurrentRetry_lostRace_throwsAlreadyProcessing() {
    BriefingJob job = failedJob(1); // retryCount < max (3)
    when(briefingJobPersistenceService.findJobById(JOB_ID)).thenReturn(job);
    when(briefingReportRepository.existsByBriefingJobId(JOB_ID)).thenReturn(false);
    when(briefingJobPersistenceService.claimForRetry(JOB_ID, 3)).thenReturn(false);

    BriefingJob freshJob = failedJob(1); // retryCount still < max
    when(briefingJobPersistenceService.findJobById(JOB_ID)).thenReturn(job).thenReturn(freshJob);

    assertThatThrownBy(() -> briefingService.retryBriefingJob(JOB_ID))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            e ->
                assertThat(((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.BRIEFING_JOB_ALREADY_PROCESSING));
  }
}
