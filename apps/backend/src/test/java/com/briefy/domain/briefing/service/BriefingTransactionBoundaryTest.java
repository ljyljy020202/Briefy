package com.briefy.domain.briefing.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.briefy.config.BriefingProperties;
import com.briefy.config.ClassificationMode;
import com.briefy.config.ClassificationProperties;
import com.briefy.domain.briefing.entity.BriefingJob;
import com.briefy.domain.briefing.entity.BriefingReport;
import com.briefy.domain.briefing.repository.BriefingArticleRepository;
import com.briefy.domain.briefing.repository.BriefingJobRepository;
import com.briefy.domain.briefing.repository.BriefingReportRepository;
import com.briefy.domain.candidatepool.repository.JobPostingAnalysisRepository;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Verifies transaction boundary correctness of BriefingService.doGenerateBriefing:
 *
 * <ul>
 *   <li>Report save failure causes recordFailure() to be called (REQUIRES_NEW — survives the
 *       failure)
 *   <li>Agent call failure causes recordFailure() to be called
 *   <li>Already-completed briefing short-circuits: no Agent call, no claim
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BriefingTransactionBoundaryTest {

  @Mock private BriefingJobRepository briefingJobRepository;
  @Mock private BriefingReportRepository briefingReportRepository;
  @Mock private BriefingArticleRepository briefingArticleRepository;
  @Mock private UserBriefingPreferenceRepository userBriefingPreferenceRepository;
  @Mock private AgentClient agentClient;
  @Mock private CandidatePoolService candidatePoolService;
  @Mock private UserRepository userRepository;
  @Mock private BriefingJobPersistenceService briefingJobPersistenceService;
  @Mock private JobPostingAnalysisRepository jobPostingAnalysisRepository;

  private BriefingService briefingService;

  private static final Long USER_ID = 1L;
  private static final BriefingProperties BRIEFING_PROPS = new BriefingProperties(0, 0, 3);
  private static final ClassificationProperties CLASSIFICATION_PROPS =
      new ClassificationProperties(
          ClassificationMode.OFF,
          "1.0.0",
          new ClassificationProperties.Worker(60000, 100, 600, 5, 2, 90, 700, 5, 60));

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
            BRIEFING_PROPS,
            jobPostingAnalysisRepository,
            CLASSIFICATION_PROPS);

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
  }

  @Test
  void agentCallFails_recordFailureIsInvoked() {
    when(agentClient.generate(any(), any(Integer.class), any(Integer.class)))
        .thenThrow(new RuntimeException("Connection refused"));

    assertThatThrownBy(() -> briefingService.generateBriefing(USER_ID))
        .isInstanceOf(BusinessException.class);

    verify(briefingJobPersistenceService).recordFailure(any(), any());
    verify(briefingJobPersistenceService, never())
        .saveReportAndComplete(any(), any(BriefingReport.class), any(), any());
  }

  @Test
  void saveReportFails_recordFailureIsInvoked() {
    AgentBriefingResponse response =
        new AgentBriefingResponse(
            "title",
            "summary",
            "content",
            List.of(),
            new AgentBriefingResponse.TokenUsage(0, 0),
            null,
            null,
            null,
            null);
    when(agentClient.generate(any(), any(Integer.class), any(Integer.class))).thenReturn(response);
    when(briefingJobPersistenceService.saveReportAndComplete(
            any(), any(BriefingReport.class), any(), any()))
        .thenThrow(new RuntimeException("DB write failed"));

    assertThatThrownBy(() -> briefingService.generateBriefing(USER_ID))
        .isInstanceOf(BusinessException.class);

    verify(briefingJobPersistenceService).recordFailure(any(), any());
  }

  @Test
  void existingReportFound_agentIsNeverCalled() {
    BriefingReport existingReport = mock(BriefingReport.class);
    when(existingReport.getId()).thenReturn(99L);
    when(existingReport.getBriefingJobId()).thenReturn(5L);
    when(briefingJobPersistenceService.findTodayReport(eq(USER_ID), any()))
        .thenReturn(Optional.of(existingReport));

    briefingService.generateBriefing(USER_ID);

    verify(agentClient, never()).generate(any());
    verify(briefingJobPersistenceService, never()).claimForProcessing(any());
    verify(briefingJobPersistenceService, never()).createOrGet(any());
  }

  @Test
  void processingJobStatus_throwsAlreadyProcessing() {
    BriefingJob processingJob = BriefingJob.createManual(USER_ID, LocalDate.now());
    processingJob.startProcessing();
    when(briefingJobPersistenceService.createOrGet(any())).thenReturn(processingJob);

    assertThatThrownBy(() -> briefingService.generateBriefing(USER_ID))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            e ->
                org.assertj.core.api.Assertions.assertThat(((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.BRIEFING_JOB_ALREADY_PROCESSING));

    verify(agentClient, never()).generate(any());
  }

  @Test
  void failedJobStatus_throwsFailedNoRetry() {
    BriefingJob failedJob = BriefingJob.createManual(USER_ID, LocalDate.now());
    failedJob.fail("previous error");
    when(briefingJobPersistenceService.createOrGet(any())).thenReturn(failedJob);

    assertThatThrownBy(() -> briefingService.generateBriefing(USER_ID))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            e ->
                org.assertj.core.api.Assertions.assertThat(((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.BRIEFING_JOB_FAILED_NO_RETRY));

    verify(agentClient, never()).generate(any());
  }

  @Test
  void claimFails_throwsAlreadyProcessing() {
    when(briefingJobPersistenceService.claimForProcessing(any())).thenReturn(false);

    assertThatThrownBy(() -> briefingService.generateBriefing(USER_ID))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            e ->
                org.assertj.core.api.Assertions.assertThat(((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.BRIEFING_JOB_ALREADY_PROCESSING));

    verify(agentClient, never()).generate(any());
  }
}
