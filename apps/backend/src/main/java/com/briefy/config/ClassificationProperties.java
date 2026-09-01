package com.briefy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 분류 작업자 설정.
 *
 * <p>기존 06:00 Collection / 08:00 Briefing 스케줄러({@code briefy.scheduler})와 책임을 분리한다. 분류 작업자는
 * fixed-delay 방식으로 독립적으로 실행된다.
 *
 * <p>mode와 classifierVersion은 명시적으로 설정해야 한다. 기본값 {@code off}는 운영 환경에서 의도치 않은 LLM 호출을 방지한다.
 *
 * <p>설정 예시:
 *
 * <pre>
 * briefy:
 *   classification:
 *     mode: shadow          # off | shadow | enforce
 *     classifier-version: 1.0.0
 *     worker:
 *       fixed-delay-ms: 60000
 *       max-items-per-run: 100
 *       budget-seconds: 600
 *       batch-size: 5
 *       max-concurrent-requests: 2
 *       agent-timeout-seconds: 90
 *       lease-seconds: 700
 *       max-attempts: 5
 *       backoff-base-seconds: 60
 * </pre>
 */
@ConfigurationProperties(prefix = "briefy.classification")
public record ClassificationProperties(
    ClassificationMode mode, String classifierVersion, Worker worker) {

  public ClassificationProperties {
    if (mode == null) mode = ClassificationMode.OFF;
    if (classifierVersion == null || classifierVersion.isBlank()) classifierVersion = "1.0.0";
    if (worker == null) worker = new Worker(60_000, 100, 600, 5, 2, 90, 700, 5, 60);
  }

  /**
   * 분류 작업자 실행 제한 설정.
   *
   * @param fixedDelayMs 실행 간 최소 지연(ms). 이전 실행 완료 후 이 시간이 지나야 다음 실행 시작.
   * @param maxItemsPerRun 한 실행에서 처리할 최대 공고 수. DB를 전량 로딩하지 않도록 제한.
   * @param budgetSeconds 한 실행의 전체 시간 예산(초). 초과 시 미처리 항목은 lease 만료 후 다음 실행에서 복구.
   * @param batchSize Agent 요청당 공고 수.
   * @param maxConcurrentRequests Backend에서 Agent로 동시에 보낼 수 있는 HTTP 요청 최대 수.
   * @param agentTimeoutSeconds Agent HTTP 호출 타임아웃(초).
   * @param leaseSeconds 클레임 유효 기간(초). budgetSeconds + agentTimeoutSeconds + 여유분으로 설정.
   * @param maxAttempts 항목당 최대 시도 횟수 (claim 기준). 초과 시 FAILED로 영구 처리.
   * @param backoffBaseSeconds 실패 후 재시도 기본 지연(초). 지수 백오프 계산에 사용.
   */
  public record Worker(
      long fixedDelayMs,
      int maxItemsPerRun,
      int budgetSeconds,
      int batchSize,
      int maxConcurrentRequests,
      int agentTimeoutSeconds,
      int leaseSeconds,
      int maxAttempts,
      int backoffBaseSeconds) {

    public Worker {
      if (fixedDelayMs <= 0) fixedDelayMs = 60_000;
      if (maxItemsPerRun <= 0) maxItemsPerRun = 100;
      if (budgetSeconds <= 0) budgetSeconds = 600;
      if (batchSize <= 0) batchSize = 5;
      if (maxConcurrentRequests <= 0) maxConcurrentRequests = 2;
      if (agentTimeoutSeconds <= 0) agentTimeoutSeconds = 90;
      if (leaseSeconds <= 0) leaseSeconds = 700;
      if (maxAttempts <= 0) maxAttempts = 5;
      if (backoffBaseSeconds <= 0) backoffBaseSeconds = 60;
    }
  }
}
