package com.briefy.domain.auth.controller;

import com.briefy.domain.auth.dto.AuthCallbackResult;
import com.briefy.domain.auth.exception.OAuthProviderConflictException;
import com.briefy.domain.auth.service.AuthService;
import com.briefy.domain.auth.service.OAuthStateService;
import com.briefy.global.exception.BusinessException;
import com.briefy.global.exception.ErrorCode;
import com.briefy.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "인증", description = "Google / Kakao OAuth 로그인 및 로그아웃")
@RestController
public class AuthController {

  private static final Logger log = LoggerFactory.getLogger(AuthController.class);

  private final AuthService authService;
  private final OAuthStateService oAuthStateService;

  public AuthController(AuthService authService, OAuthStateService oAuthStateService) {
    this.authService = authService;
    this.oAuthStateService = oAuthStateService;
  }

  // ── Google ────────────────────────────────────────────────────────────────

  @Operation(summary = "Google OAuth 인증 시작", description = "Google 로그인 페이지로 리다이렉트합니다.")
  @GetMapping("/api/oauth2/authorize/google")
  public ResponseEntity<Void> googleAuthorize() {
    String state = oAuthStateService.generateAndStore();
    String authUrl = authService.buildGoogleAuthorizationUrl(state);
    return ResponseEntity.status(HttpStatus.FOUND)
        .header(HttpHeaders.SET_COOKIE, oAuthStateService.buildStateCookie(state).toString())
        .header(HttpHeaders.LOCATION, authUrl)
        .build();
  }

  @Operation(summary = "Google OAuth 콜백", description = "Google 인증 완료 후 code를 교환하고 JWT 쿠키를 발급합니다.")
  @GetMapping("/api/oauth2/callback/google")
  public ResponseEntity<Void> googleCallback(
      @RequestParam(required = false) String code,
      @RequestParam(required = false) String error,
      @RequestParam(required = false) String state,
      @CookieValue(name = OAuthStateService.COOKIE_NAME, required = false) String cookieState) {

    String clearStateCookie = oAuthStateService.buildClearedStateCookie().toString();

    if (error != null || code == null) {
      log.warn("Google OAuth callback error or missing code: error={}", error);
      return redirectWithClearedState(clearStateCookie, authService.getGoogleErrorRedirectUrl());
    }

    if (!oAuthStateService.validateAndConsume(state, cookieState)) {
      log.warn("Google OAuth state validation failed");
      return redirectWithClearedState(
          clearStateCookie, authService.getLoginErrorRedirectUrl("oauth_state_invalid"));
    }

    try {
      AuthCallbackResult result = authService.handleGoogleCallback(code);
      return redirectWithJwtAndClearedState(clearStateCookie, result);
    } catch (Exception e) {
      log.warn("Google OAuth callback failed", e);
      return redirectWithClearedState(clearStateCookie, authService.getGoogleErrorRedirectUrl());
    }
  }

  // ── Kakao ─────────────────────────────────────────────────────────────────

  @Operation(summary = "Kakao OAuth 인증 시작", description = "Kakao 로그인 페이지로 리다이렉트합니다.")
  @GetMapping("/api/oauth2/authorize/kakao")
  public ResponseEntity<Void> kakaoAuthorize() {
    String state = oAuthStateService.generateAndStore();
    String authUrl = authService.buildKakaoAuthorizationUrl(state);
    return ResponseEntity.status(HttpStatus.FOUND)
        .header(HttpHeaders.SET_COOKIE, oAuthStateService.buildStateCookie(state).toString())
        .header(HttpHeaders.LOCATION, authUrl)
        .build();
  }

  @Operation(summary = "Kakao OAuth 콜백", description = "Kakao 인증 완료 후 code를 교환하고 JWT 쿠키를 발급합니다.")
  @GetMapping("/api/oauth2/callback/kakao")
  public ResponseEntity<Void> kakaoCallback(
      @RequestParam(required = false) String code,
      @RequestParam(required = false) String error,
      @RequestParam(required = false) String state,
      @CookieValue(name = OAuthStateService.COOKIE_NAME, required = false) String cookieState) {

    String clearStateCookie = oAuthStateService.buildClearedStateCookie().toString();

    if (error != null) {
      log.warn("Kakao OAuth cancelled or error: error={}", error);
      return redirectWithClearedState(
          clearStateCookie, authService.getLoginErrorRedirectUrl("oauth_cancelled"));
    }

    if (code == null) {
      log.warn("Kakao OAuth callback missing code");
      return redirectWithClearedState(
          clearStateCookie, authService.getLoginErrorRedirectUrl("oauth_failed"));
    }

    if (!oAuthStateService.validateAndConsume(state, cookieState)) {
      log.warn("Kakao OAuth state validation failed");
      return redirectWithClearedState(
          clearStateCookie, authService.getLoginErrorRedirectUrl("oauth_state_invalid"));
    }

    try {
      AuthCallbackResult result = authService.handleKakaoCallback(code);
      return redirectWithJwtAndClearedState(clearStateCookie, result);
    } catch (OAuthProviderConflictException e) {
      log.warn("Kakao OAuth provider conflict: existing provider={}", e.getExistingProvider());
      return redirectWithClearedState(
          clearStateCookie,
          authService.getLoginErrorRedirectUrl(
              "oauth_provider_conflict&provider=" + e.getExistingProvider()));
    } catch (BusinessException e) {
      String errorParam = mapBusinessErrorToParam(e.getErrorCode());
      return redirectWithClearedState(
          clearStateCookie, authService.getLoginErrorRedirectUrl(errorParam));
    } catch (Exception e) {
      log.warn("Kakao OAuth callback failed unexpectedly", e);
      return redirectWithClearedState(
          clearStateCookie, authService.getLoginErrorRedirectUrl("oauth_failed"));
    }
  }

  // ── Logout ────────────────────────────────────────────────────────────────

  @Operation(summary = "로그아웃", description = "JWT 쿠키를 만료시켜 로그아웃합니다.")
  @PostMapping("/api/auth/logout")
  public ResponseEntity<ApiResponse<Void>> logout() {
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, authService.buildExpiredJwtCookie().toString())
        .body(ApiResponse.success());
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  private ResponseEntity<Void> redirectWithClearedState(String clearStateCookie, String location) {
    return ResponseEntity.status(HttpStatus.FOUND)
        .header(HttpHeaders.SET_COOKIE, clearStateCookie)
        .header(HttpHeaders.LOCATION, location)
        .build();
  }

  private ResponseEntity<Void> redirectWithJwtAndClearedState(
      String clearStateCookie, AuthCallbackResult result) {
    HttpHeaders headers = new HttpHeaders();
    headers.add(HttpHeaders.SET_COOKIE, clearStateCookie);
    headers.add(HttpHeaders.SET_COOKIE, result.cookie().toString());
    headers.add(HttpHeaders.LOCATION, result.redirectUrl());
    return ResponseEntity.status(HttpStatus.FOUND).headers(headers).build();
  }

  private String mapBusinessErrorToParam(ErrorCode errorCode) {
    if (errorCode == ErrorCode.KAKAO_EMAIL_REQUIRED) {
      return "kakao_email_required";
    }
    if (errorCode == ErrorCode.KAKAO_EMAIL_INVALID) {
      return "kakao_email_invalid";
    }
    return "oauth_failed";
  }
}
