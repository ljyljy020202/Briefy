package com.briefy.domain.candidatepool.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.briefy.config.ClassificationMode;
import com.briefy.config.ClassificationProperties;
import com.briefy.domain.candidatepool.dto.ClassificationWorkItem;
import com.briefy.infra.agent.AgentClient;
import com.briefy.infra.agent.dto.AgentClassificationResult;
import com.briefy.infra.agent.dto.AgentClassifyRequest;
import com.briefy.infra.agent.dto.AgentClassifyResponse;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ClassificationWorkerTest {

  @Mock private ClassificationWorkerPersistenceService persistenceService;
  @Mock private AgentClient agentClient;

  private ClassificationProperties properties;
  private ClassificationWorker worker;

  private static final ClassificationProperties.Worker DEFAULT_WORKER =
      new ClassificationProperties.Worker(60_000, 100, 600, 5, 2, 90, 700, 5, 60);

  @BeforeEach
  void setUp() {
    properties = new ClassificationProperties(ClassificationMode.SHADOW, "1.0.0", DEFAULT_WORKER);
    worker = new ClassificationWorker(properties, persistenceService, agentClient);
  }

  // ── OFF 모드 ────────────────────────────────────────────────────────────────

  @Test
  void run_doesNothing_whenModeIsOff() {
    properties = new ClassificationProperties(ClassificationMode.OFF, "1.0.0", DEFAULT_WORKER);
    worker = new ClassificationWorker(properties, persistenceService, agentClient);

    worker.run();

    verify(persistenceService, never()).claimBatch(anyInt());
    verify(agentClient, never()).classify(any(), anyInt());
  }

  // ── 작업 항목 없음 ───────────────────────────────────────────────────────────

  @Test
  void run_skipsAgentCall_whenNoWorkItems() {
    when(persistenceService.recoverExpiredLeases()).thenReturn(0);
    when(persistenceService.claimBatch(anyInt())).thenReturn(Collections.emptyList());

    worker.run();

    verify(agentClient, never()).classify(any(), anyInt());
  }

  // ── 정상 처리 ────────────────────────────────────────────────────────────────

  @Test
  void run_callsAgent_andAppliesResult() {
    ClassificationWorkItem item = workItem(1L, 101L, "token-1", "hash-1");
    when(persistenceService.recoverExpiredLeases()).thenReturn(0);
    when(persistenceService.claimBatch(anyInt())).thenReturn(List.of(item));

    AgentClassificationResult result = classificationResult(101L, "hash-1", "SUCCEEDED");
    AgentClassifyResponse response =
        new AgentClassifyResponse("req-1", "1.0.0", List.of(result), null, List.of());
    when(agentClient.classify(any(), anyInt())).thenReturn(response);
    when(persistenceService.applyResult(eq(item), eq(result), any())).thenReturn(true);

    worker.run();

    verify(agentClient, times(1)).classify(any(), anyInt());
    verify(persistenceService, times(1)).applyResult(eq(item), eq(result), any());
    verify(persistenceService, never()).markFailed(any(), anyString());
  }

  // ── Agent 호출 실패 → 배치 전체 실패 표시 ─────────────────────────────────────

  @Test
  void run_marksBatchFailed_whenAgentCallThrows() {
    ClassificationWorkItem item = workItem(2L, 102L, "token-2", "hash-2");
    when(persistenceService.recoverExpiredLeases()).thenReturn(0);
    when(persistenceService.claimBatch(anyInt())).thenReturn(List.of(item));
    when(agentClient.classify(any(), anyInt())).thenThrow(new RuntimeException("timeout"));

    worker.run();

    verify(persistenceService, times(1)).markFailed(eq(item), eq("AGENT_ERROR"));
    verify(persistenceService, never()).applyResult(any(), any(), any());
  }

  // ── Agent 응답에서 ID 누락 ────────────────────────────────────────────────────

  @Test
  void run_marksFailedForMissingId_inResponse() {
    ClassificationWorkItem item = workItem(3L, 103L, "token-3", "hash-3");
    when(persistenceService.recoverExpiredLeases()).thenReturn(0);
    when(persistenceService.claimBatch(anyInt())).thenReturn(List.of(item));

    // 응답에 다른 ID만 포함
    AgentClassificationResult wrongResult = classificationResult(999L, "hash-x", "SUCCEEDED");
    AgentClassifyResponse response =
        new AgentClassifyResponse("req-1", "1.0.0", List.of(wrongResult), null, List.of());
    when(agentClient.classify(any(), anyInt())).thenReturn(response);

    worker.run();

    verify(persistenceService, times(1)).markFailed(eq(item), eq("MISSING_IN_RESPONSE"));
    verify(persistenceService, never()).applyResult(any(), any(), any());
  }

  // ── 배치 분할 (batchSize=2) ──────────────────────────────────────────────────

  @Test
  void run_splitsBatch_andCallsAgentOncePerBatch() {
    ClassificationProperties smallBatch =
        new ClassificationProperties(
            ClassificationMode.SHADOW,
            "1.0.0",
            new ClassificationProperties.Worker(60_000, 100, 600, 2, 2, 90, 700, 5, 60));
    worker = new ClassificationWorker(smallBatch, persistenceService, agentClient);

    ClassificationWorkItem item1 = workItem(1L, 101L, "t1", "h1");
    ClassificationWorkItem item2 = workItem(2L, 102L, "t2", "h2");
    ClassificationWorkItem item3 = workItem(3L, 103L, "t3", "h3");

    when(persistenceService.recoverExpiredLeases()).thenReturn(0);
    when(persistenceService.claimBatch(anyInt())).thenReturn(List.of(item1, item2, item3));

    // 각 배치마다 빈 결과 반환
    when(agentClient.classify(any(), anyInt()))
        .thenReturn(new AgentClassifyResponse("r1", "1.0.0", List.of(), null, List.of()));

    worker.run();

    // 3개 항목, batchSize=2 → 배치 2개 (2+1)
    verify(agentClient, times(2)).classify(any(), anyInt());
  }

  // ── ENFORCE 모드에서도 동일하게 동작 ─────────────────────────────────────────

  @Test
  void run_works_inEnforceMode() {
    properties = new ClassificationProperties(ClassificationMode.ENFORCE, "1.0.0", DEFAULT_WORKER);
    worker = new ClassificationWorker(properties, persistenceService, agentClient);

    ClassificationWorkItem item = workItem(4L, 104L, "token-4", "hash-4");
    when(persistenceService.recoverExpiredLeases()).thenReturn(0);
    when(persistenceService.claimBatch(anyInt())).thenReturn(List.of(item));

    AgentClassificationResult result = classificationResult(104L, "hash-4", "SUCCEEDED");
    AgentClassifyResponse response =
        new AgentClassifyResponse("req-4", "1.0.0", List.of(result), null, List.of());
    when(agentClient.classify(any(), anyInt())).thenReturn(response);
    when(persistenceService.applyResult(eq(item), eq(result), any())).thenReturn(true);

    worker.run();

    verify(agentClient, times(1)).classify(any(), anyInt());
  }

  // ── 만료된 리스 복구 ──────────────────────────────────────────────────────────

  @Test
  void run_recoversExpiredLeases_beforeClaiming() {
    when(persistenceService.recoverExpiredLeases()).thenReturn(3);
    when(persistenceService.claimBatch(anyInt())).thenReturn(Collections.emptyList());

    worker.run();

    verify(persistenceService, times(1)).recoverExpiredLeases();
    verify(persistenceService, times(1)).claimBatch(anyInt());
  }

  // ── requestId 배치별 고유 ─────────────────────────────────────────────────────

  @Test
  void run_usesUniqueRequestIdPerBatch() {
    ClassificationProperties smallBatch =
        new ClassificationProperties(
            ClassificationMode.SHADOW,
            "1.0.0",
            new ClassificationProperties.Worker(60_000, 100, 600, 1, 2, 90, 700, 5, 60));
    worker = new ClassificationWorker(smallBatch, persistenceService, agentClient);

    when(persistenceService.recoverExpiredLeases()).thenReturn(0);
    when(persistenceService.claimBatch(anyInt()))
        .thenReturn(List.of(workItem(1L, 101L, "t1", "h1"), workItem(2L, 102L, "t2", "h2")));

    ArgumentCaptor<AgentClassifyRequest> captor =
        ArgumentCaptor.forClass(AgentClassifyRequest.class);
    when(agentClient.classify(captor.capture(), anyInt()))
        .thenReturn(new AgentClassifyResponse("r", "1.0.0", List.of(), null, List.of()));

    worker.run();

    List<AgentClassifyRequest> requests = captor.getAllValues();
    assertThat(requests).hasSize(2);
    assertThat(requests.get(0).requestId()).isNotEqualTo(requests.get(1).requestId());
  }

  // ── null 응답 → 배치 전체 실패 ────────────────────────────────────────────────

  @Test
  void run_marksBatchFailed_whenResponseIsNull() {
    ClassificationWorkItem item = workItem(5L, 105L, "token-5", "hash-5");
    when(persistenceService.recoverExpiredLeases()).thenReturn(0);
    when(persistenceService.claimBatch(anyInt())).thenReturn(List.of(item));
    when(agentClient.classify(any(), anyInt())).thenReturn(null);

    worker.run();

    verify(persistenceService, times(1)).markFailed(eq(item), eq("NULL_RESPONSE"));
  }

  // ── applyResult 예외 → markFailed 호출 ─────────────────────────────────────

  @Test
  void run_marksFailed_whenApplyResultThrows() {
    ClassificationWorkItem item = workItem(6L, 106L, "token-6", "hash-6");
    when(persistenceService.recoverExpiredLeases()).thenReturn(0);
    when(persistenceService.claimBatch(anyInt())).thenReturn(List.of(item));

    AgentClassificationResult result = classificationResult(106L, "hash-6", "SUCCEEDED");
    AgentClassifyResponse response =
        new AgentClassifyResponse("req-6", "1.0.0", List.of(result), null, List.of());
    when(agentClient.classify(any(), anyInt())).thenReturn(response);
    when(persistenceService.applyResult(any(), any(), any()))
        .thenThrow(new RuntimeException("db error"));

    worker.run();

    verify(persistenceService, times(1)).markFailed(eq(item), eq("APPLY_ERROR"));
  }

  // ── 요청 건수 상한 ────────────────────────────────────────────────────────────

  @Test
  void run_claimsBatch_withMaxItemsPerRunLimit() {
    when(persistenceService.recoverExpiredLeases()).thenReturn(0);
    when(persistenceService.claimBatch(100)).thenReturn(Collections.emptyList());

    worker.run();

    verify(persistenceService, times(1)).claimBatch(100);
  }

  // ── helpers ──────────────────────────────────────────────────────────────────

  private ClassificationWorkItem workItem(
      Long analysisId, Long postingId, String claimToken, String hash) {
    return new ClassificationWorkItem(
        analysisId,
        postingId,
        claimToken,
        hash,
        "1.0.0",
        "백엔드 개발자",
        "네이버",
        "Java Spring 개발",
        "[\"백엔드\"]",
        "신입/경력",
        "정규직",
        false,
        List.of());
  }

  private AgentClassificationResult classificationResult(
      Long postingId, String hash, String status) {
    return new AgentClassificationResult(
        postingId,
        hash,
        "IT",
        "SINGLE_ROLE",
        List.of("BACKEND"),
        "REGULAR",
        List.of(),
        null,
        null,
        null,
        "REQUIRED",
        null,
        "LLM",
        status,
        "evidence text",
        List.of(),
        0.9,
        0.8,
        false);
  }
}
