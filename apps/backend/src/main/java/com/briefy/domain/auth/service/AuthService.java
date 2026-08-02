package com.briefy.domain.auth.service;

import com.briefy.config.AppProperties;
import com.briefy.config.JwtProperties;
import com.briefy.domain.auth.client.GoogleOAuthClient;
import com.briefy.domain.auth.client.KakaoOAuthClient;
import com.briefy.domain.auth.dto.AuthCallbackResult;
import com.briefy.domain.auth.dto.GoogleTokenResponse;
import com.briefy.domain.auth.dto.GoogleUserInfoResponse;
import com.briefy.domain.auth.dto.KakaoTokenResponse;
import com.briefy.domain.auth.dto.KakaoUserInfoResponse;
import com.briefy.domain.user.entity.AuthProvider;
import com.briefy.domain.user.entity.User;
import com.briefy.domain.user.service.UserService;
import com.briefy.global.exception.BusinessException;
import com.briefy.global.exception.ErrorCode;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

  private final GoogleOAuthClient googleOAuthClient;
  private final KakaoOAuthClient kakaoOAuthClient;
  private final UserService userService;
  private final JwtTokenProvider jwtTokenProvider;
  private final JwtProperties jwtProperties;
  private final AppProperties appProperties;

  public AuthService(
      GoogleOAuthClient googleOAuthClient,
      KakaoOAuthClient kakaoOAuthClient,
      UserService userService,
      JwtTokenProvider jwtTokenProvider,
      JwtProperties jwtProperties,
      AppProperties appProperties) {
    this.googleOAuthClient = googleOAuthClient;
    this.kakaoOAuthClient = kakaoOAuthClient;
    this.userService = userService;
    this.jwtTokenProvider = jwtTokenProvider;
    this.jwtProperties = jwtProperties;
    this.appProperties = appProperties;
  }

  public String buildGoogleAuthorizationUrl(String state) {
    return googleOAuthClient.buildAuthorizationUrl(state);
  }

  public String buildKakaoAuthorizationUrl(String state) {
    return kakaoOAuthClient.buildAuthorizationUrl(state);
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

    return buildCallbackResult(user);
  }

  public AuthCallbackResult handleKakaoCallback(String code) {
    KakaoTokenResponse tokenResponse = kakaoOAuthClient.exchangeCode(code);
    KakaoUserInfoResponse userInfo = kakaoOAuthClient.fetchUserInfo(tokenResponse.accessToken());

    validateKakaoEmail(userInfo);

    String email = userInfo.kakaoAccount().email();
    String kakaoId = String.valueOf(userInfo.id());
    String nickname = extractKakaoNickname(userInfo);
    String profileImageUrl = extractKakaoProfileImage(userInfo);

    User user = userService.findOrCreateKakao(email, kakaoId, nickname, profileImageUrl);
    return buildCallbackResult(user);
  }

  public ResponseCookie buildExpiredJwtCookie() {
    return buildJwtCookie("", 0);
  }

  public String getGoogleErrorRedirectUrl() {
    return appProperties.frontendBaseUrl() + "/?error=oauth_failed";
  }

  public String getLoginErrorRedirectUrl(String errorCode) {
    return appProperties.frontendBaseUrl() + "/login?error=" + errorCode;
  }

  private AuthCallbackResult buildCallbackResult(User user) {
    String jwt = jwtTokenProvider.createToken(user.getId(), user.getEmail(), user.getRole().name());
    ResponseCookie cookie = buildJwtCookie(jwt, jwtProperties.expirationSeconds());
    String redirectUrl =
        user.isOnboardingCompleted()
            ? appProperties.frontendBaseUrl() + "/dashboard"
            : appProperties.frontendBaseUrl() + "/onboarding";
    return new AuthCallbackResult(cookie, redirectUrl);
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

  private void validateKakaoEmail(KakaoUserInfoResponse userInfo) {
    KakaoUserInfoResponse.KakaoAccount account = userInfo.kakaoAccount();
    if (account == null
        || account.email() == null
        || account.email().isBlank()
        || Boolean.TRUE.equals(account.emailNeedsAgreement())) {
      throw new BusinessException(ErrorCode.KAKAO_EMAIL_REQUIRED);
    }
    if (!Boolean.TRUE.equals(account.isEmailValid())
        || !Boolean.TRUE.equals(account.isEmailVerified())) {
      throw new BusinessException(ErrorCode.KAKAO_EMAIL_INVALID);
    }
  }

  private String extractKakaoNickname(KakaoUserInfoResponse userInfo) {
    if (userInfo.kakaoAccount() == null || userInfo.kakaoAccount().profile() == null) {
      return null;
    }
    return userInfo.kakaoAccount().profile().nickname();
  }

  private String extractKakaoProfileImage(KakaoUserInfoResponse userInfo) {
    if (userInfo.kakaoAccount() == null || userInfo.kakaoAccount().profile() == null) {
      return null;
    }
    String url = userInfo.kakaoAccount().profile().profileImageUrl();
    if (url == null || url.isBlank()) {
      return null;
    }
    return url;
  }
}
