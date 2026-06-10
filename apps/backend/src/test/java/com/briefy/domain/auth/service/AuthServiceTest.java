package com.briefy.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.briefy.config.AppProperties;
import com.briefy.config.JwtProperties;
import com.briefy.domain.auth.client.GoogleOAuthClient;
import com.briefy.domain.auth.dto.AuthCallbackResult;
import com.briefy.domain.auth.dto.GoogleTokenResponse;
import com.briefy.domain.auth.dto.GoogleUserInfoResponse;
import com.briefy.domain.user.entity.AuthProvider;
import com.briefy.domain.user.entity.User;
import com.briefy.domain.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseCookie;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

  @Mock private GoogleOAuthClient googleOAuthClient;
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
            googleOAuthClient, userService, jwtTokenProvider, jwtProperties, appProperties);
  }

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
    existingUser.completeOnboarding(null);

    when(googleOAuthClient.exchangeCode("auth-code")).thenReturn(tokenResponse);
    when(googleOAuthClient.fetchUserInfo("access-token")).thenReturn(userInfo);
    when(userService.findOrCreate(any(), any(), any(), any(), any())).thenReturn(existingUser);
    when(jwtTokenProvider.createToken(any(), any(), any())).thenReturn("jwt-token");

    AuthCallbackResult result = authService.handleGoogleCallback("auth-code");

    assertThat(result.redirectUrl()).isEqualTo("http://localhost:3000/dashboard");
  }

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
  void getErrorRedirectUrl_returnsLoginPageWithErrorParam() {
    assertThat(authService.getErrorRedirectUrl())
        .isEqualTo("http://localhost:3000/login?error=oauth_failed");
  }
}
