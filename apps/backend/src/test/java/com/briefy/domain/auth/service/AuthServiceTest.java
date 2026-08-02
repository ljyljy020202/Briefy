package com.briefy.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.briefy.config.AppProperties;
import com.briefy.config.JwtProperties;
import com.briefy.domain.auth.client.GoogleOAuthClient;
import com.briefy.domain.auth.client.KakaoOAuthClient;
import com.briefy.domain.auth.dto.AuthCallbackResult;
import com.briefy.domain.auth.dto.GoogleTokenResponse;
import com.briefy.domain.auth.dto.GoogleUserInfoResponse;
import com.briefy.domain.auth.dto.KakaoTokenResponse;
import com.briefy.domain.auth.dto.KakaoUserInfoResponse;
import com.briefy.domain.auth.dto.KakaoUserInfoResponse.KakaoAccount;
import com.briefy.domain.auth.dto.KakaoUserInfoResponse.KakaoProfile;
import com.briefy.domain.auth.exception.OAuthProviderConflictException;
import com.briefy.domain.user.entity.AuthProvider;
import com.briefy.domain.user.entity.User;
import com.briefy.domain.user.service.UserService;
import com.briefy.global.exception.BusinessException;
import com.briefy.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseCookie;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

  @Mock private GoogleOAuthClient googleOAuthClient;
  @Mock private KakaoOAuthClient kakaoOAuthClient;
  @Mock private UserService userService;
  @Mock private JwtTokenProvider jwtTokenProvider;

  private JwtProperties jwtProperties;
  private AppProperties appProperties;
  private AuthService authService;

  @BeforeEach
  void setUp() {
    jwtProperties =
        new JwtProperties(
            "test-secret-with-at-least-32-bytes!!", 3600L, "briefy_access_token", false, "Lax");
    appProperties = new AppProperties("http://localhost:3000");
    authService =
        new AuthService(
            googleOAuthClient,
            kakaoOAuthClient,
            userService,
            jwtTokenProvider,
            jwtProperties,
            appProperties);
  }

  // ── Google ────────────────────────────────────────────────────────────────

  @Test
  void handleGoogleCallback_newUser_redirectsToOnboarding() {
    GoogleTokenResponse tokenResponse =
        new GoogleTokenResponse("access-token", "Bearer", 3600, "openid email profile", null);
    GoogleUserInfoResponse userInfo =
        new GoogleUserInfoResponse(
            "google-sub-123", "user@gmail.com", "Test User", "https://example.com/photo.jpg");
    User newUser =
        User.create(
            "user@gmail.com",
            "Test User",
            "https://example.com/photo.jpg",
            AuthProvider.GOOGLE,
            "google-sub-123");

    when(googleOAuthClient.exchangeCode("auth-code")).thenReturn(tokenResponse);
    when(googleOAuthClient.fetchUserInfo("access-token")).thenReturn(userInfo);
    when(userService.findOrCreate(
            eq("user@gmail.com"),
            eq(AuthProvider.GOOGLE),
            eq("google-sub-123"),
            eq("Test User"),
            eq("https://example.com/photo.jpg")))
        .thenReturn(newUser);
    when(jwtTokenProvider.createToken(any(), eq("user@gmail.com"), eq("USER")))
        .thenReturn("jwt-token");

    AuthCallbackResult result = authService.handleGoogleCallback("auth-code");

    assertThat(result.redirectUrl()).isEqualTo("http://localhost:3000/onboarding");
    assertThat(result.cookie().getName()).isEqualTo("briefy_access_token");
    assertThat(result.cookie().getValue()).isEqualTo("jwt-token");
    verify(googleOAuthClient).exchangeCode("auth-code");
    verify(googleOAuthClient).fetchUserInfo("access-token");
  }

  @Test
  void handleGoogleCallback_returningUser_redirectsToDashboard() {
    GoogleTokenResponse tokenResponse =
        new GoogleTokenResponse("access-token", "Bearer", 3600, "openid email profile", null);
    GoogleUserInfoResponse userInfo =
        new GoogleUserInfoResponse(
            "google-sub-123", "user@gmail.com", "Test User", "https://example.com/photo.jpg");
    User existingUser =
        User.create(
            "user@gmail.com",
            "Test User",
            "https://example.com/photo.jpg",
            AuthProvider.GOOGLE,
            "google-sub-123");
    existingUser.completeOnboarding(null, null);

    when(googleOAuthClient.exchangeCode("auth-code")).thenReturn(tokenResponse);
    when(googleOAuthClient.fetchUserInfo("access-token")).thenReturn(userInfo);
    when(userService.findOrCreate(any(), any(), any(), any(), any())).thenReturn(existingUser);
    when(jwtTokenProvider.createToken(any(), any(), any())).thenReturn("jwt-token");

    AuthCallbackResult result = authService.handleGoogleCallback("auth-code");

    assertThat(result.redirectUrl()).isEqualTo("http://localhost:3000/dashboard");
  }

  @Test
  void buildGoogleAuthorizationUrl_includesStateParam() {
    when(googleOAuthClient.buildAuthorizationUrl("test-state"))
        .thenReturn("https://google.com/auth?state=test-state");

    String url = authService.buildGoogleAuthorizationUrl("test-state");

    assertThat(url).contains("state=test-state");
    verify(googleOAuthClient).buildAuthorizationUrl("test-state");
  }

  // ── Kakao ─────────────────────────────────────────────────────────────────

  @Test
  void handleKakaoCallback_newUser_redirectsToOnboarding() {
    KakaoTokenResponse tokenResponse =
        new KakaoTokenResponse("kakao-access-token", "Bearer", 3600, null, null, null);
    KakaoProfile profile = new KakaoProfile("카카오유저", "https://img.kakao.com/photo.jpg", false);
    KakaoAccount account = new KakaoAccount("kakao@kakao.com", false, true, true, true, profile);
    KakaoUserInfoResponse userInfo = new KakaoUserInfoResponse(111222333L, account);
    User newUser =
        User.create(
            "kakao@kakao.com",
            "카카오유저",
            "https://img.kakao.com/photo.jpg",
            AuthProvider.KAKAO,
            "111222333");

    when(kakaoOAuthClient.exchangeCode("kakao-code")).thenReturn(tokenResponse);
    when(kakaoOAuthClient.fetchUserInfo("kakao-access-token")).thenReturn(userInfo);
    when(userService.findOrCreateKakao(
            eq("kakao@kakao.com"),
            eq("111222333"),
            eq("카카오유저"),
            eq("https://img.kakao.com/photo.jpg")))
        .thenReturn(newUser);
    when(jwtTokenProvider.createToken(any(), eq("kakao@kakao.com"), eq("USER")))
        .thenReturn("jwt-token");

    AuthCallbackResult result = authService.handleKakaoCallback("kakao-code");

    assertThat(result.redirectUrl()).isEqualTo("http://localhost:3000/onboarding");
    assertThat(result.cookie().getName()).isEqualTo("briefy_access_token");
    assertThat(result.cookie().getValue()).isEqualTo("jwt-token");
    verify(kakaoOAuthClient).exchangeCode("kakao-code");
    verify(kakaoOAuthClient).fetchUserInfo("kakao-access-token");
  }

  @Test
  void handleKakaoCallback_returningUser_redirectsToDashboard() {
    KakaoTokenResponse tokenResponse =
        new KakaoTokenResponse("kakao-access-token", "Bearer", 3600, null, null, null);
    KakaoProfile profile = new KakaoProfile("카카오유저", null, false);
    KakaoAccount account = new KakaoAccount("kakao@kakao.com", false, true, true, true, profile);
    KakaoUserInfoResponse userInfo = new KakaoUserInfoResponse(111222333L, account);
    User existingUser =
        User.create("kakao@kakao.com", "카카오유저", null, AuthProvider.KAKAO, "111222333");
    existingUser.completeOnboarding(null, null);

    when(kakaoOAuthClient.exchangeCode("kakao-code")).thenReturn(tokenResponse);
    when(kakaoOAuthClient.fetchUserInfo("kakao-access-token")).thenReturn(userInfo);
    when(userService.findOrCreateKakao(any(), any(), any(), any())).thenReturn(existingUser);
    when(jwtTokenProvider.createToken(any(), any(), any())).thenReturn("jwt-token");

    AuthCallbackResult result = authService.handleKakaoCallback("kakao-code");

    assertThat(result.redirectUrl()).isEqualTo("http://localhost:3000/dashboard");
  }

  @Test
  void handleKakaoCallback_noProfile_fallsBackToNullNickname() {
    KakaoTokenResponse tokenResponse =
        new KakaoTokenResponse("kakao-access-token", "Bearer", 3600, null, null, null);
    KakaoAccount account = new KakaoAccount("kakao@kakao.com", false, true, true, true, null);
    KakaoUserInfoResponse userInfo = new KakaoUserInfoResponse(111222333L, account);
    User newUser = User.create("kakao@kakao.com", null, null, AuthProvider.KAKAO, "111222333");

    when(kakaoOAuthClient.exchangeCode("kakao-code")).thenReturn(tokenResponse);
    when(kakaoOAuthClient.fetchUserInfo("kakao-access-token")).thenReturn(userInfo);
    when(userService.findOrCreateKakao(eq("kakao@kakao.com"), eq("111222333"), eq(null), eq(null)))
        .thenReturn(newUser);
    when(jwtTokenProvider.createToken(any(), any(), any())).thenReturn("jwt-token");

    AuthCallbackResult result = authService.handleKakaoCallback("kakao-code");

    assertThat(result.redirectUrl()).isEqualTo("http://localhost:3000/onboarding");
  }

  @Test
  void handleKakaoCallback_emailMissing_throwsKakaoEmailRequired() {
    KakaoTokenResponse tokenResponse =
        new KakaoTokenResponse("kakao-access-token", "Bearer", 3600, null, null, null);
    KakaoAccount account = new KakaoAccount(null, false, true, true, false, null);
    KakaoUserInfoResponse userInfo = new KakaoUserInfoResponse(111222333L, account);

    when(kakaoOAuthClient.exchangeCode("kakao-code")).thenReturn(tokenResponse);
    when(kakaoOAuthClient.fetchUserInfo("kakao-access-token")).thenReturn(userInfo);

    assertThatThrownBy(() -> authService.handleKakaoCallback("kakao-code"))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            e ->
                assertThat(((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.KAKAO_EMAIL_REQUIRED));
  }

  @Test
  void handleKakaoCallback_emailNeedsAgreement_throwsKakaoEmailRequired() {
    KakaoTokenResponse tokenResponse =
        new KakaoTokenResponse("kakao-access-token", "Bearer", 3600, null, null, null);
    KakaoAccount account = new KakaoAccount("kakao@kakao.com", true, true, true, true, null);
    KakaoUserInfoResponse userInfo = new KakaoUserInfoResponse(111222333L, account);

    when(kakaoOAuthClient.exchangeCode("kakao-code")).thenReturn(tokenResponse);
    when(kakaoOAuthClient.fetchUserInfo("kakao-access-token")).thenReturn(userInfo);

    assertThatThrownBy(() -> authService.handleKakaoCallback("kakao-code"))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            e ->
                assertThat(((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.KAKAO_EMAIL_REQUIRED));
  }

  @Test
  void handleKakaoCallback_emailNotValid_throwsKakaoEmailInvalid() {
    KakaoTokenResponse tokenResponse =
        new KakaoTokenResponse("kakao-access-token", "Bearer", 3600, null, null, null);
    KakaoAccount account = new KakaoAccount("kakao@kakao.com", false, false, true, true, null);
    KakaoUserInfoResponse userInfo = new KakaoUserInfoResponse(111222333L, account);

    when(kakaoOAuthClient.exchangeCode("kakao-code")).thenReturn(tokenResponse);
    when(kakaoOAuthClient.fetchUserInfo("kakao-access-token")).thenReturn(userInfo);

    assertThatThrownBy(() -> authService.handleKakaoCallback("kakao-code"))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            e ->
                assertThat(((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.KAKAO_EMAIL_INVALID));
  }

  @Test
  void handleKakaoCallback_emailNotVerified_throwsKakaoEmailInvalid() {
    KakaoTokenResponse tokenResponse =
        new KakaoTokenResponse("kakao-access-token", "Bearer", 3600, null, null, null);
    KakaoAccount account = new KakaoAccount("kakao@kakao.com", false, true, false, true, null);
    KakaoUserInfoResponse userInfo = new KakaoUserInfoResponse(111222333L, account);

    when(kakaoOAuthClient.exchangeCode("kakao-code")).thenReturn(tokenResponse);
    when(kakaoOAuthClient.fetchUserInfo("kakao-access-token")).thenReturn(userInfo);

    assertThatThrownBy(() -> authService.handleKakaoCallback("kakao-code"))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            e ->
                assertThat(((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.KAKAO_EMAIL_INVALID));
  }

  @Test
  void handleKakaoCallback_providerConflict_throwsOAuthProviderConflictException() {
    KakaoTokenResponse tokenResponse =
        new KakaoTokenResponse("kakao-access-token", "Bearer", 3600, null, null, null);
    KakaoAccount account = new KakaoAccount("conflict@email.com", false, true, true, true, null);
    KakaoUserInfoResponse userInfo = new KakaoUserInfoResponse(111222333L, account);

    when(kakaoOAuthClient.exchangeCode("kakao-code")).thenReturn(tokenResponse);
    when(kakaoOAuthClient.fetchUserInfo("kakao-access-token")).thenReturn(userInfo);
    when(userService.findOrCreateKakao(any(), any(), any(), any()))
        .thenThrow(new OAuthProviderConflictException("google"));

    assertThatThrownBy(() -> authService.handleKakaoCallback("kakao-code"))
        .isInstanceOf(OAuthProviderConflictException.class)
        .satisfies(
            e ->
                assertThat(((OAuthProviderConflictException) e).getExistingProvider())
                    .isEqualTo("google"));
  }

  // ── JWT Cookie ────────────────────────────────────────────────────────────

  @Test
  void buildExpiredJwtCookie_hasMaxAgeZero() {
    ResponseCookie cookie = authService.buildExpiredJwtCookie();

    assertThat(cookie.getName()).isEqualTo("briefy_access_token");
    assertThat(cookie.getMaxAge().getSeconds()).isZero();
    assertThat(cookie.isHttpOnly()).isTrue();
  }

  @Test
  void buildExpiredJwtCookie_hasCorrectSecureAndSameSiteSettings() {
    ResponseCookie cookie = authService.buildExpiredJwtCookie();

    assertThat(cookie.isSecure()).isFalse();
    assertThat(cookie.getSameSite()).isEqualTo("Lax");
    assertThat(cookie.getPath()).isEqualTo("/");
  }

  @Test
  void getGoogleErrorRedirectUrl_returnsRootWithErrorParam() {
    assertThat(authService.getGoogleErrorRedirectUrl())
        .isEqualTo("http://localhost:3000/?error=oauth_failed");
  }

  @Test
  void getLoginErrorRedirectUrl_returnsLoginPageWithErrorParam() {
    assertThat(authService.getLoginErrorRedirectUrl("kakao_email_required"))
        .isEqualTo("http://localhost:3000/login?error=kakao_email_required");
  }
}
