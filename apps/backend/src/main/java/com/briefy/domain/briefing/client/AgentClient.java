package com.briefy.domain.briefing.client;

import com.briefy.config.AgentProperties;
import com.briefy.domain.briefing.client.dto.AgentBriefingRequest;
import com.briefy.domain.briefing.client.dto.AgentBriefingResponse;
import com.briefy.global.exception.BusinessException;
import com.briefy.global.exception.ErrorCode;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
public class AgentClient {

  private static final Logger log = LoggerFactory.getLogger(AgentClient.class);

  private final WebClient webClient;

  public AgentClient(WebClient.Builder builder, AgentProperties agentProperties) {
    this.webClient = builder.baseUrl(agentProperties.url()).build();
  }

  public AgentBriefingResponse generate(AgentBriefingRequest request) {
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
}
