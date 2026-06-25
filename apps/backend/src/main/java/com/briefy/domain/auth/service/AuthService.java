package com.briefy.domain.auth.service;

import com.briefy.config.AppProperties;
import com.briefy.config.JwtProperties;
import com.briefy.domain.auth.client.GoogleOAuthClient;
import com.briefy.domain.auth.dto.AuthCallbackResult;
import com.briefy.domain.auth.dto.GoogleTokenResponse;
import com.briefy.domain.auth.dto.GoogleUserInfoResponse;
import com.briefy.domain.user.entity.AuthProvider;
import com.briefy.domain.user.entity.User;
import com.briefy.domain.user.service.UserService;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

  private final GoogleOAuthClient googleOAuthClient;
  private final UserService userService;
  private final JwtTokenProvider jwtTokenProvider;
  private final JwtProperties jwtProperties;
  private final AppProperties appProperties;

  public AuthService(
      GoogleOAuthClient googleOAuthClient,
      UserService userService,
      JwtTokenProvider jwtTokenProvider,
      JwtProperties jwtProperties,
      AppProperties appProperties) {
    this.googleOAuthClient = googleOAuthClient;
    this.userService = userService;
    this.jwtTokenProvider = jwtTokenProvider;
    this.jwtProperties = jwtProperties;
    this.appProperties = appProperties;
  }

  public String buildGoogleAuthorizationUrl() {
    return googleOAuthClient.buildAuthorizationUrl();
  }

  public AuthCallbackResult handleGoogleCallback(String code) {
    GoogleTokenResponse tokenResponse = googleOAuthClient.exchangeCode(code);
    GoogleUserInfoResponse userInfo = googleOAuthClient.fetchUserInfo(tokenResponse.accessToken());

    User user =
        userService.findOrCreate(
            userInfo.email(),
            AuthProvider.GOOGLE,
            userInfo.sub(),
            userInfo.name(),
            userInfo.picture());

    String jwt = jwtTokenProvider.createToken(user.getId(), user.getEmail(), user.getRole().name());
    ResponseCookie cookie = buildJwtCookie(jwt, jwtProperties.expirationSeconds());

    String redirectUrl =
        user.isOnboardingCompleted()
            ? appProperties.frontendBaseUrl() + "/dashboard"
            : appProperties.frontendBaseUrl() + "/onboarding";

    return new AuthCallbackResult(cookie, redirectUrl);
  }

  public ResponseCookie buildExpiredJwtCookie() {
    return buildJwtCookie("", 0);
  }

  public String getErrorRedirectUrl() {
    return appProperties.frontendBaseUrl() + "/?error=oauth_failed";
  }

  private ResponseCookie buildJwtCookie(String value, long maxAgeSeconds) {
    return ResponseCookie.from(jwtProperties.cookieName(), value)
        .httpOnly(true)
        .secure(jwtProperties.cookieSecure())
        .sameSite(jwtProperties.cookieSameSite())
        .path("/")
        .maxAge(maxAgeSeconds)
        .build();
  }
}
