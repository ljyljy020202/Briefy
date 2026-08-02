package com.briefy.domain.auth.client;

import com.briefy.config.KakaoOAuthProperties;
import com.briefy.domain.auth.dto.KakaoTokenResponse;
import com.briefy.domain.auth.dto.KakaoUserInfoResponse;
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
public class KakaoOAuthClient {

  private static final Logger log = LoggerFactory.getLogger(KakaoOAuthClient.class);

  static final String DEFAULT_AUTH_URL = "https://kauth.kakao.com/oauth/authorize";
  static final String DEFAULT_TOKEN_URL = "https://kauth.kakao.com/oauth/token";
  static final String DEFAULT_USERINFO_URL = "https://kapi.kakao.com/v2/user/me";

  private final KakaoOAuthProperties properties;
  private final WebClient webClient;

  public KakaoOAuthClient(KakaoOAuthProperties properties, WebClient.Builder webClientBuilder) {
    this.properties = properties;
    this.webClient = webClientBuilder.build();
  }

  public String buildAuthorizationUrl(String state) {
    return UriComponentsBuilder.fromUriString(DEFAULT_AUTH_URL)
        .queryParam("client_id", properties.clientId())
        .queryParam("redirect_uri", properties.redirectUri())
        .queryParam("response_type", "code")
        .queryParam("state", state)
        .queryParam("scope", "account_email profile_nickname profile_image")
        .build()
        .toUriString();
  }

  public KakaoTokenResponse exchangeCode(String code) {
    return exchangeCodeInternal(code, DEFAULT_TOKEN_URL);
  }

  public KakaoUserInfoResponse fetchUserInfo(String accessToken) {
    return fetchUserInfoInternal(accessToken, DEFAULT_USERINFO_URL);
  }

  KakaoTokenResponse exchangeCodeInternal(String code, String tokenUrl) {
    try {
      MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
      formData.add("grant_type", "authorization_code");
      formData.add("client_id", properties.clientId());
      formData.add("redirect_uri", properties.redirectUri());
      formData.add("code", code);
      formData.add("client_secret", properties.clientSecret());

      return webClient
          .post()
          .uri(tokenUrl)
          .contentType(MediaType.APPLICATION_FORM_URLENCODED)
          .body(BodyInserters.fromFormData(formData))
          .retrieve()
          .bodyToMono(KakaoTokenResponse.class)
          .block();
    } catch (WebClientResponseException e) {
      log.warn("Kakao token exchange failed: status={}", e.getStatusCode());
      throw new BusinessException(
          ErrorCode.INTERNAL_SERVER_ERROR, "Kakao OAuth token exchange failed");
    }
  }

  KakaoUserInfoResponse fetchUserInfoInternal(String accessToken, String userinfoUrl) {
    try {
      return webClient
          .get()
          .uri(userinfoUrl)
          .header("Authorization", "Bearer " + accessToken)
          .retrieve()
          .bodyToMono(KakaoUserInfoResponse.class)
          .block();
    } catch (WebClientResponseException e) {
      log.warn("Kakao userinfo fetch failed: status={}", e.getStatusCode());
      throw new BusinessException(
          ErrorCode.INTERNAL_SERVER_ERROR, "Failed to fetch Kakao user info");
    }
  }
}
