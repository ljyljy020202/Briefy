package com.briefy.domain.auth.client;

import com.briefy.config.GoogleOAuthProperties;
import com.briefy.domain.auth.dto.GoogleTokenResponse;
import com.briefy.domain.auth.dto.GoogleUserInfoResponse;
import com.briefy.global.exception.BusinessException;
import com.briefy.global.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class GoogleOAuthClient {

  private static final Logger log = LoggerFactory.getLogger(GoogleOAuthClient.class);
  private static final String AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth";
  private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
  private static final String USERINFO_URL = "https://www.googleapis.com/oauth2/v3/userinfo";

  private final GoogleOAuthProperties properties;
  private final WebClient webClient;

  public GoogleOAuthClient(GoogleOAuthProperties properties, WebClient.Builder webClientBuilder) {
    this.properties = properties;
    this.webClient = webClientBuilder.build();
  }

  public String buildAuthorizationUrl(String state) {
    return UriComponentsBuilder.fromUriString(AUTH_URL)
        .queryParam("client_id", properties.clientId())
        .queryParam("redirect_uri", properties.redirectUri())
        .queryParam("response_type", "code")
        .queryParam("scope", "openid email profile")
        .queryParam("access_type", "online")
        .queryParam("state", state)
        .build()
        .toUriString();
  }

  public GoogleTokenResponse exchangeCode(String code) {
    try {
      MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
      formData.add("code", code);
      formData.add("client_id", properties.clientId());
      formData.add("client_secret", properties.clientSecret());
      formData.add("redirect_uri", properties.redirectUri());
      formData.add("grant_type", "authorization_code");

      return webClient
          .post()
          .uri(TOKEN_URL)
          .contentType(MediaType.APPLICATION_FORM_URLENCODED)
          .body(BodyInserters.fromFormData(formData))
          .retrieve()
          .bodyToMono(GoogleTokenResponse.class)
          .block();
    } catch (WebClientResponseException e) {
      log.warn(
          "Google token exchange failed: status={} body={}",
          e.getStatusCode(),
          e.getResponseBodyAsString());
      throw new BusinessException(
          ErrorCode.INTERNAL_SERVER_ERROR, "Google OAuth token exchange failed");
    }
  }

  public GoogleUserInfoResponse fetchUserInfo(String accessToken) {
    try {
      return webClient
          .get()
          .uri(USERINFO_URL)
          .header("Authorization", "Bearer " + accessToken)
          .retrieve()
          .bodyToMono(GoogleUserInfoResponse.class)
          .block();
    } catch (WebClientResponseException e) {
      log.warn("Google userinfo fetch failed: status={}", e.getStatusCode());
      throw new BusinessException(
          ErrorCode.INTERNAL_SERVER_ERROR, "Failed to fetch Google user info");
    }
  }
}
