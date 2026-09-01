package com.briefy.infra.agent;

import com.briefy.config.AgentProperties;
import com.briefy.global.exception.BusinessException;
import com.briefy.global.exception.ErrorCode;
import com.briefy.infra.agent.dto.AgentBriefingRequest;
import com.briefy.infra.agent.dto.AgentBriefingResponse;
import com.briefy.infra.agent.dto.AgentClassifyRequest;
import com.briefy.infra.agent.dto.AgentClassifyResponse;
import com.briefy.infra.agent.dto.AgentCollectionRequest;
import com.briefy.infra.agent.dto.AgentCollectionResponse;
import com.briefy.infra.agent.dto.AgentSourcePreflightRequest;
import com.briefy.infra.agent.dto.AgentSourcePreflightResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

@Component
public class AgentClient {

  private static final Logger log = LoggerFactory.getLogger(AgentClient.class);

  private final WebClient webClient;

  // Agent responses can be large (many postings × descriptions).
  // Default WebClient buffer is 256KB; raise to 16MB to avoid DataBufferLimitException.
  private static final int MAX_IN_MEMORY_SIZE = 16 * 1024 * 1024;

  public AgentClient(WebClient.Builder builder, AgentProperties agentProperties) {
    ExchangeStrategies strategies =
        ExchangeStrategies.builder()
            .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_SIZE))
            .build();
    this.webClient = builder.baseUrl(agentProperties.url()).exchangeStrategies(strategies).build();
  }

  public AgentBriefingResponse generate(AgentBriefingRequest request) {
    return generate(request, 0, 0);
  }

  public AgentBriefingResponse generate(
      AgentBriefingRequest request, int maxRetries, int backoffSeconds) {
    int attempt = 0;
    while (true) {
      try {
        return doGenerate(request);
      } catch (BusinessException e) {
        if (isNonRetryable(e)) throw e;
        if (attempt >= maxRetries) throw e;
        attempt++;
        log.warn(
            "Agent briefing call failed (attempt {}), retrying in {}s: {}",
            attempt,
            backoffSeconds,
            e.getMessage());
        try {
          Thread.sleep(backoffSeconds * 1000L);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          throw e;
        }
      } catch (Exception e) {
        if (attempt >= maxRetries) {
          log.error(
              "Agent briefing call failed after {} attempts: {}", attempt + 1, e.getMessage());
          throw new BusinessException(ErrorCode.AGENT_SERVER_ERROR, "Agent server error");
        }
        attempt++;
        log.warn(
            "Agent briefing call failed (attempt {}), retrying in {}s: {}",
            attempt,
            backoffSeconds,
            e.getMessage());
        try {
          Thread.sleep(backoffSeconds * 1000L);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          throw new BusinessException(ErrorCode.AGENT_SERVER_ERROR, "Agent server error");
        }
      }
    }
  }

  private AgentBriefingResponse doGenerate(AgentBriefingRequest request) {
    log.info("Calling agent for briefing generation, userId={}", request.userId());
    try {
      AgentBriefingResponse response =
          webClient
              .post()
              .uri("/briefings/generate")
              .contentType(MediaType.APPLICATION_JSON)
              .bodyValue(request)
              .retrieve()
              .onStatus(
                  HttpStatusCode::isError,
                  resp ->
                      resp.bodyToMono(String.class)
                          .defaultIfEmpty("")
                          .map(
                              body ->
                                  new BusinessException(
                                      ErrorCode.AGENT_SERVER_ERROR,
                                      "Agent returned " + resp.statusCode() + ": " + body)))
              .bodyToMono(AgentBriefingResponse.class)
              .timeout(Duration.ofSeconds(60))
              .block();
      log.info("Agent briefing generation succeeded, userId={}", request.userId());
      return response;
    } catch (BusinessException e) {
      throw e;
    } catch (Exception e) {
      log.error("Agent call failed, userId={}: {}", request.userId(), e.getMessage());
      throw new BusinessException(ErrorCode.AGENT_SERVER_ERROR, "Agent server error");
    }
  }

  public AgentSourcePreflightResponse preflight(AgentSourcePreflightRequest request) {
    log.info(
        "Calling agent for source preflight, sourceId={} adapterType={}",
        request.sourceId(),
        request.adapterType());
    try {
      AgentSourcePreflightResponse response =
          webClient
              .post()
              .uri("/official-sources/preflight")
              .contentType(MediaType.APPLICATION_JSON)
              .bodyValue(request)
              .retrieve()
              .onStatus(
                  HttpStatusCode::isError,
                  resp ->
                      resp.bodyToMono(String.class)
                          .defaultIfEmpty("")
                          .map(
                              body ->
                                  new BusinessException(
                                      ErrorCode.AGENT_SERVER_ERROR,
                                      "Agent preflight returned "
                                          + resp.statusCode()
                                          + ": "
                                          + body)))
              .bodyToMono(AgentSourcePreflightResponse.class)
              .timeout(Duration.ofSeconds(30))
              .block();
      log.info("Agent preflight succeeded, sourceId={}", request.sourceId());
      return response;
    } catch (BusinessException e) {
      throw e;
    } catch (Exception e) {
      log.error("Agent preflight call failed, sourceId={}: {}", request.sourceId(), e.getMessage());
      throw new BusinessException(ErrorCode.AGENT_SERVER_ERROR, "Agent server error");
    }
  }

  public AgentCollectionResponse triggerDailyCollection(
      AgentCollectionRequest request, int maxRetries, int backoffSeconds) {
    int attempt = 0;
    while (true) {
      try {
        return doTriggerDailyCollection(request);
      } catch (BusinessException e) {
        if (isNonRetryable(e)) throw e;
        if (attempt >= maxRetries) throw e;
        attempt++;
        log.warn(
            "Agent collection call failed (attempt {}), retrying in {}s: {}",
            attempt,
            backoffSeconds,
            e.getMessage());
        try {
          Thread.sleep(backoffSeconds * 1000L);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          throw e;
        }
      } catch (Exception e) {
        if (attempt >= maxRetries) {
          log.error(
              "Agent collection call failed after {} attempts: {}", attempt + 1, e.getMessage());
          throw new BusinessException(ErrorCode.AGENT_SERVER_ERROR, "Agent server error");
        }
        attempt++;
        log.warn(
            "Agent collection call failed (attempt {}), retrying in {}s: {}",
            attempt,
            backoffSeconds,
            e.getMessage());
        try {
          Thread.sleep(backoffSeconds * 1000L);
        } catch (InterruptedException ie) {
          Thread.currentThread().interrupt();
          throw new BusinessException(ErrorCode.AGENT_SERVER_ERROR, "Agent server error");
        }
      }
    }
  }

  private AgentCollectionResponse doTriggerDailyCollection(AgentCollectionRequest request) {
    log.info("Calling agent for daily collection, date={}", request.collectDate());
    try {
      AgentCollectionResponse response =
          webClient
              .post()
              .uri("/collections/daily")
              .contentType(MediaType.APPLICATION_JSON)
              .bodyValue(request)
              .retrieve()
              .onStatus(
                  HttpStatusCode::isError,
                  resp ->
                      resp.bodyToMono(String.class)
                          .defaultIfEmpty("")
                          .map(
                              body ->
                                  new BusinessException(
                                      ErrorCode.AGENT_SERVER_ERROR,
                                      "Agent returned " + resp.statusCode() + ": " + body)))
              .bodyToMono(AgentCollectionResponse.class)
              .timeout(Duration.ofSeconds(210))
              .block();
      log.info("Agent daily collection succeeded, date={}", request.collectDate());
      return response;
    } catch (BusinessException e) {
      throw e;
    } catch (Exception e) {
      log.error("Agent collection call failed: {}", e.getMessage());
      throw new BusinessException(ErrorCode.AGENT_SERVER_ERROR, "Agent server error");
    }
  }

  /**
   * Agent POST /collections/classify 를 호출한다.
   *
   * <p>HTTP 오류·타임아웃은 {@link BusinessException}으로 변환한다. 재시도 로직은 호출자({@code ClassificationWorker})가
   * 담당하므로 여기서는 1회 시도만 한다.
   *
   * @param request 분류 요청
   * @param timeoutSeconds Agent 응답 대기 최대 시간
   */
  public AgentClassifyResponse classify(AgentClassifyRequest request, int timeoutSeconds) {
    log.info(
        "Calling agent for classification, requestId={} postings={}",
        request.requestId(),
        request.postings() == null ? 0 : request.postings().size());
    try {
      AgentClassifyResponse response =
          webClient
              .post()
              .uri("/collections/classify")
              .contentType(MediaType.APPLICATION_JSON)
              .bodyValue(request)
              .retrieve()
              .onStatus(
                  HttpStatusCode::isError,
                  resp ->
                      resp.bodyToMono(String.class)
                          .defaultIfEmpty("")
                          .map(
                              body ->
                                  new BusinessException(
                                      ErrorCode.AGENT_SERVER_ERROR,
                                      "Agent classify returned "
                                          + resp.statusCode()
                                          + ": "
                                          + body)))
              .bodyToMono(AgentClassifyResponse.class)
              .timeout(Duration.ofSeconds(timeoutSeconds))
              .block();
      log.info("Agent classification succeeded, requestId={}", request.requestId());
      return response;
    } catch (BusinessException e) {
      throw e;
    } catch (Exception e) {
      log.error(
          "Agent classify call failed, requestId={}: {}", request.requestId(), e.getMessage());
      throw new BusinessException(ErrorCode.AGENT_SERVER_ERROR, "Agent classify error");
    }
  }

  private boolean isNonRetryable(BusinessException e) {
    String msg = e.getMessage();
    if (msg == null) return false;
    return msg.contains(": 400")
        || msg.contains(": 401")
        || msg.contains(": 403")
        || msg.contains(": 404")
        || msg.contains(": 422");
  }
}
