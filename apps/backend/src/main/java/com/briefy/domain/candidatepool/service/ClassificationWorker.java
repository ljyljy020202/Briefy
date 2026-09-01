package com.briefy.domain.candidatepool.service;

import com.briefy.config.ClassificationMode;
import com.briefy.config.ClassificationProperties;
import com.briefy.domain.candidatepool.dto.ClassificationWorkItem;
import com.briefy.infra.agent.AgentClient;
import com.briefy.infra.agent.dto.AgentClassificationResult;
import com.briefy.infra.agent.dto.AgentClassifyInputQuality;
import com.briefy.infra.agent.dto.AgentClassifyPostingInput;
import com.briefy.infra.agent.dto.AgentClassifyRequest;
import com.briefy.infra.agent.dto.AgentClassifyResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 분류 작업자. @Scheduled 스케줄러가 fixedDelay로 실행하며, 한 번의 실행에서:
 *
 * <ol>
 *   <li>만료된 리스 복구
 *   <li>PENDING/재시도 가능 항목을 claim (최대 maxItemsPerRun 개)
 *   <li>batchSize 단위로 Agent POST /collections/classify 호출 (최대 maxConcurrentRequests 동시)
 *   <li>결과를 DB에 적용 (각 항목 독립 트랜잭션)
 * </ol>
 *
 * <p>각 단계가 실패해도 다른 항목에 영향을 주지 않는다 (부분 실패 허용).
 */
@Component
public class ClassificationWorker {

  private static final Logger log = LoggerFactory.getLogger(ClassificationWorker.class);

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {};

  private final ClassificationProperties properties;
  private final ClassificationWorkerPersistenceService persistenceService;
  private final AgentClient agentClient;

  public ClassificationWorker(
      ClassificationProperties properties,
      ClassificationWorkerPersistenceService persistenceService,
      AgentClient agentClient) {
    this.properties = properties;
    this.persistenceService = persistenceService;
    this.agentClient = agentClient;
  }

  @Scheduled(fixedDelayString = "${briefy.classification.worker.fixed-delay-ms:60000}")
  public void run() {
    if (properties.mode() == ClassificationMode.OFF) {
      return;
    }

    log.info("Classification worker started, mode={}", properties.mode());
    long startMs = System.currentTimeMillis();
    long budgetMs = (long) properties.worker().budgetSeconds() * 1_000;

    try {
      int recovered = persistenceService.recoverExpiredLeases();
      if (recovered > 0) {
        log.info("Recovered {} expired leases before claiming", recovered);
      }

      List<ClassificationWorkItem> workItems =
          persistenceService.claimBatch(properties.worker().maxItemsPerRun());

      if (workItems.isEmpty()) {
        log.info("No classification work items found");
        return;
      }

      log.info("Claimed {} work items for classification", workItems.size());

      List<List<ClassificationWorkItem>> batches =
          partition(workItems, properties.worker().batchSize());
      Semaphore concurrencySemaphore = new Semaphore(properties.worker().maxConcurrentRequests());

      for (List<ClassificationWorkItem> batch : batches) {
        if (budgetExceeded(startMs, budgetMs)) {
          log.warn("Classification worker budget exceeded, stopping early");
          releaseUnprocessedItems(batch);
          break;
        }

        processBatch(batch, concurrencySemaphore);
      }
    } catch (Exception e) {
      log.error("Classification worker encountered unexpected error: {}", e.getMessage(), e);
    }

    long elapsed = System.currentTimeMillis() - startMs;
    log.info("Classification worker finished in {}ms", elapsed);
  }

  private void processBatch(List<ClassificationWorkItem> batch, Semaphore semaphore) {
    String requestId = UUID.randomUUID().toString();
    List<AgentClassifyPostingInput> inputs = batch.stream().map(this::toInput).toList();
    AgentClassifyRequest request =
        new AgentClassifyRequest(requestId, properties.classifierVersion(), inputs);

    AgentClassifyResponse response;
    try {
      semaphore.acquire();
      try {
        response = agentClient.classify(request, properties.worker().agentTimeoutSeconds());
      } finally {
        semaphore.release();
      }
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      log.warn("Worker thread interrupted during Agent call, requestId={}", requestId);
      markBatchFailed(batch, "INTERRUPTED");
      return;
    } catch (Exception e) {
      log.warn("Agent classify call failed, requestId={}: {}", requestId, e.getMessage());
      markBatchFailed(batch, "AGENT_ERROR");
      return;
    }

    applyBatchResults(batch, response);
  }

  private void applyBatchResults(
      List<ClassificationWorkItem> batch, AgentClassifyResponse response) {
    if (response == null || response.results() == null) {
      log.warn("Agent returned null response/results");
      markBatchFailed(batch, "NULL_RESPONSE");
      return;
    }

    Map<Long, AgentClassificationResult> resultMap =
        response.results().stream()
            .collect(
                Collectors.toMap(
                    AgentClassificationResult::jobPostingId, Function.identity(), (a, b) -> a));

    Set<Long> expectedIds =
        batch.stream().map(ClassificationWorkItem::postingId).collect(Collectors.toSet());

    LocalDateTime now = LocalDateTime.now();

    for (ClassificationWorkItem item : batch) {
      AgentClassificationResult result = resultMap.get(item.postingId());
      if (result == null) {
        log.warn(
            "Agent response missing result for postingId={} analysisId={}",
            item.postingId(),
            item.analysisId());
        persistenceService.markFailed(item, "MISSING_IN_RESPONSE");
        continue;
      }

      try {
        boolean applied = persistenceService.applyResult(item, result, now);
        if (!applied) {
          log.warn(
              "Result not applied for analysisId={} (stale claim or conflict)", item.analysisId());
        }
      } catch (Exception e) {
        log.error(
            "Failed to apply result for analysisId={}: {}", item.analysisId(), e.getMessage(), e);
        try {
          persistenceService.markFailed(item, "APPLY_ERROR");
        } catch (Exception ex) {
          log.error(
              "Failed to mark failed for analysisId={}: {}", item.analysisId(), ex.getMessage());
        }
      }
    }

    // 응답에 있지만 요청에 없는 extra IDs 경고 (계약 위반이지만 무시)
    Set<Long> responseIds = resultMap.keySet();
    Set<Long> extraIds =
        responseIds.stream().filter(id -> !expectedIds.contains(id)).collect(Collectors.toSet());
    if (!extraIds.isEmpty()) {
      log.warn("Agent returned unexpected extra postingIds: {}", extraIds);
    }
  }

  private void markBatchFailed(List<ClassificationWorkItem> batch, String errorCode) {
    for (ClassificationWorkItem item : batch) {
      try {
        persistenceService.markFailed(item, errorCode);
      } catch (Exception e) {
        log.error("Failed to mark failed for analysisId={}: {}", item.analysisId(), e.getMessage());
      }
    }
  }

  /** 예산 초과로 처리하지 못한 항목은 leaseUntil이 지나면 자동 복구되므로 별도 처리 불필요. 단, 로그는 남긴다. */
  private void releaseUnprocessedItems(List<ClassificationWorkItem> unprocessed) {
    log.warn("{} work items were claimed but not processed due to budget", unprocessed.size());
  }

  private boolean budgetExceeded(long startMs, long budgetMs) {
    return System.currentTimeMillis() - startMs >= budgetMs;
  }

  private AgentClassifyPostingInput toInput(ClassificationWorkItem item) {
    List<String> parsedRoles = parseRoles(item.rolesJson());
    boolean hasDesc = item.description() != null && !item.description().isBlank();
    int descLen = hasDesc ? item.description().length() : 0;

    AgentClassifyInputQuality quality =
        new AgentClassifyInputQuality(
            hasDesc,
            !parsedRoles.isEmpty(),
            item.experienceLevel() != null && !item.experienceLevel().isBlank(),
            Boolean.TRUE.equals(item.descriptionTruncated()),
            descLen);

    return new AgentClassifyPostingInput(
        item.postingId(),
        item.analysisInputHash(),
        item.title(),
        item.company(),
        item.description(),
        parsedRoles,
        item.experienceLevel(),
        item.employmentType(),
        item.sourceRefs(),
        quality);
  }

  private List<String> parseRoles(String rolesJson) {
    if (rolesJson == null || rolesJson.isBlank()) return Collections.emptyList();
    try {
      return MAPPER.readValue(rolesJson, STRING_LIST_TYPE);
    } catch (Exception e) {
      return Collections.emptyList();
    }
  }

  private <T> List<List<T>> partition(List<T> list, int size) {
    if (size <= 0) size = 1;
    List<List<T>> result = new ArrayList<>();
    for (int i = 0; i < list.size(); i += size) {
      result.add(list.subList(i, Math.min(i + size, list.size())));
    }
    return result;
  }
}
