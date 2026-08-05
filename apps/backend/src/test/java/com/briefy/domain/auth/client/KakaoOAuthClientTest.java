package com.briefy.domain.auth.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.briefy.config.KakaoOAuthProperties;
import com.briefy.domain.auth.dto.KakaoTokenResponse;
import com.briefy.domain.auth.dto.KakaoUserInfoResponse;
import com.briefy.global.exception.BusinessException;
import java.io.IOException;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

class KakaoOAuthClientTest {

  private MockWebServer mockWebServer;
  private KakaoOAuthClient kakaoOAuthClient;

  @BeforeEach
  void setUp() throws IOException {
    mockWebServer = new MockWebServer();
    mockWebServer.start();

    String baseUrl = mockWebServer.url("/").toString().replaceAll("/$", "");
    KakaoOAuthProperties properties =
        new KakaoOAuthProperties("test-client-id", "test-client-secret", baseUrl + "/callback");

    kakaoOAuthClient = new KakaoOAuthClientForTest(properties, WebClient.builder(), baseUrl);
  }

  @AfterEach
  void tearDown() throws IOException {
    mockWebServer.shutdown();
  }

  @Test
  void buildAuthorizationUrl_containsRequiredParams() {
    String url = kakaoOAuthClient.buildAuthorizationUrl("test-state-uuid");

    assertThat(url).contains("client_id=test-client-id");
    assertThat(url).contains("response_type=code");
    assertThat(url).contains("state=test-state-uuid");
    assertThat(url).contains("scope=");
  }

  @Test
  void exchangeCode_sendsCorrectFormDataAndReturnsToken() throws InterruptedException {
    String tokenJson =
        """
        {
          "access_token": "test-access-token",
          "token_type": "Bearer",
          "expires_in": 3600,
          "scope": "account_email"
        }
        """;
    mockWebServer.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .setBody(tokenJson));

    KakaoTokenResponse response = kakaoOAuthClient.exchangeCode("auth-code-123");

    assertThat(response.accessToken()).isEqualTo("test-access-token");
    assertThat(response.tokenType()).isEqualTo("Bearer");
    assertThat(response.expiresIn()).isEqualTo(3600);

    RecordedRequest request = mockWebServer.takeRequest();
    String body = request.getBody().readUtf8();
    assertThat(body).contains("grant_type=authorization_code");
    assertThat(body).contains("client_id=test-client-id");
    assertThat(body).contains("client_secret=test-client-secret");
    assertThat(body).contains("code=auth-code-123");
    assertThat(request.getHeader(HttpHeaders.CONTENT_TYPE))
        .contains(MediaType.APPLICATION_FORM_URLENCODED_VALUE);
  }

  @Test
  void exchangeCode_tokenEndpointError_throwsBusinessException() {
    mockWebServer.enqueue(new MockResponse().setResponseCode(400).setBody("invalid_grant"));

    assertThatThrownBy(() -> kakaoOAuthClient.exchangeCode("bad-code"))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void fetchUserInfo_sendsAuthHeaderAndReturnsUserInfo() throws InterruptedException {
    String userInfoJson =
        """
        {
          "id": 123456789,
          "kakao_account": {
            "email": "user@kakao.com",
            "email_needs_agreement": false,
            "is_email_valid": true,
            "is_email_verified": true,
            "has_email": true,
            "profile": {
              "nickname": "테스트유저",
              "profile_image_url": "https://img.kakao.com/photo.jpg",
              "is_default_image": false
            }
          }
        }
        """;
    mockWebServer.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .setBody(userInfoJson));

    KakaoUserInfoResponse response = kakaoOAuthClient.fetchUserInfo("test-access-token");

    assertThat(response.id()).isEqualTo(123456789L);
    assertThat(response.kakaoAccount().email()).isEqualTo("user@kakao.com");
    assertThat(response.kakaoAccount().isEmailValid()).isTrue();
    assertThat(response.kakaoAccount().isEmailVerified()).isTrue();
    assertThat(response.kakaoAccount().profile().nickname()).isEqualTo("테스트유저");
    assertThat(response.kakaoAccount().profile().profileImageUrl())
        .isEqualTo("https://img.kakao.com/photo.jpg");

    RecordedRequest request = mockWebServer.takeRequest();
    assertThat(request.getHeader(HttpHeaders.AUTHORIZATION)).isEqualTo("Bearer test-access-token");
  }

  @Test
  void fetchUserInfo_apiError_throwsBusinessException() {
    mockWebServer.enqueue(new MockResponse().setResponseCode(401).setBody("unauthorized"));

    assertThatThrownBy(() -> kakaoOAuthClient.fetchUserInfo("invalid-token"))
        .isInstanceOf(BusinessException.class);
  }

  /** Subclass that overrides internal endpoint URLs to point to the MockWebServer. */
  private static class KakaoOAuthClientForTest extends KakaoOAuthClient {

    private final String baseUrl;

    KakaoOAuthClientForTest(
        KakaoOAuthProperties properties, WebClient.Builder builder, String baseUrl) {
      super(properties, builder);
      this.baseUrl = baseUrl;
    }

    @Override
    public KakaoTokenResponse exchangeCode(String code) {
      return exchangeCodeInternal(code, baseUrl + "/oauth/token");
    }

    @Override
    public KakaoUserInfoResponse fetchUserInfo(String accessToken) {
      return fetchUserInfoInternal(accessToken, baseUrl + "/v2/user/me");
    }
  }
}
