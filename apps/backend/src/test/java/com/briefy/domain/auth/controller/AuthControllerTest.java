package com.briefy.domain.auth.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.briefy.config.JwtProperties;
import com.briefy.domain.auth.dto.AuthCallbackResult;
import com.briefy.domain.auth.service.AuthService;
import com.briefy.domain.auth.service.OAuthStateService;
import com.briefy.global.exception.GlobalExceptionHandler;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.ResponseCookie;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthControllerTest {

  @Mock private AuthService authService;
  @Mock private StringRedisTemplate redisTemplate;
  @Mock private ValueOperations<String, String> valueOps;

  private OAuthStateService oAuthStateServiceLax;
  private OAuthStateService oAuthStateServiceNone;
  private MockMvc mockMvcLax;
  private MockMvc mockMvcNone;

  private static final String FRONTEND_URL = "https://briefy.store";
  private static final String STATE_INVALID_URL = FRONTEND_URL + "/login?error=oauth_state_invalid";
  private static final String OAUTH_FAILED_URL = FRONTEND_URL + "/?error=oauth_failed";
  private static final String OAUTH_CANCELLED_URL = FRONTEND_URL + "/login?error=oauth_cancelled";

  @BeforeEach
  void setUp() {
    JwtProperties laxProps =
        new JwtProperties("test-secret-32-bytes-minimum-here", 3600L, "briefy_token", false, "Lax");
    JwtProperties noneProps =
        new JwtProperties("test-secret-32-bytes-minimum-here", 3600L, "briefy_token", true, "None");

    oAuthStateServiceLax = new OAuthStateService(redisTemplate, laxProps);
    oAuthStateServiceNone = new OAuthStateService(redisTemplate, noneProps);

    when(redisTemplate.opsForValue()).thenReturn(valueOps);

    // Default stubs for redirect URL helpers
    when(authService.getLoginErrorRedirectUrl("oauth_state_invalid")).thenReturn(STATE_INVALID_URL);
    when(authService.getLoginErrorRedirectUrl("oauth_cancelled")).thenReturn(OAUTH_CANCELLED_URL);
    when(authService.getLoginErrorRedirectUrl("oauth_failed"))
        .thenReturn(FRONTEND_URL + "/login?error=oauth_failed");
    when(authService.getGoogleErrorRedirectUrl()).thenReturn(OAUTH_FAILED_URL);
    when(authService.buildGoogleAuthorizationUrl(anyString()))
        .thenReturn("https://accounts.google.com/o/oauth2/auth?state=test");
    when(authService.buildKakaoAuthorizationUrl(anyString()))
        .thenReturn("https://kauth.kakao.com/oauth/authorize?state=test");

    mockMvcLax =
        MockMvcBuilders.standaloneSetup(new AuthController(authService, oAuthStateServiceLax))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    mockMvcNone =
        MockMvcBuilders.standaloneSetup(new AuthController(authService, oAuthStateServiceNone))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  // ── Authorize: state cookie attributes ───────────────────────────────────

  @Test
  void googleAuthorize_setsCookieWithLaxAttributes() throws Exception {
    MvcResult result =
        mockMvcLax
            .perform(get("/api/oauth2/authorize/google"))
            .andExpect(status().isFound())
            .andReturn();

    String setCookie = result.getResponse().getHeader("Set-Cookie");
    assertThat(setCookie).contains("oauth_state_google=");
    assertThat(setCookie).containsIgnoringCase("HttpOnly");
    assertThat(setCookie).containsIgnoringCase("SameSite=Lax");
    assertThat(setCookie).containsIgnoringCase("Path=/api/oauth2");
  }

  @Test
  void kakaoAuthorize_setsCookieWithKakaoCookieName() throws Exception {
    MvcResult result =
        mockMvcLax
            .perform(get("/api/oauth2/authorize/kakao"))
            .andExpect(status().isFound())
            .andReturn();

    String setCookie = result.getResponse().getHeader("Set-Cookie");
    assertThat(setCookie).contains("oauth_state_kakao=");
    assertThat(setCookie).doesNotContain("oauth_state_google");
  }

  @Test
  void googleAndKakaoAuthorize_useDifferentCookieNames() throws Exception {
    String googleCookie =
        mockMvcLax
            .perform(get("/api/oauth2/authorize/google"))
            .andReturn()
            .getResponse()
            .getHeader("Set-Cookie");
    String kakaoCookie =
        mockMvcLax
            .perform(get("/api/oauth2/authorize/kakao"))
            .andReturn()
            .getResponse()
            .getHeader("Set-Cookie");

    assertThat(googleCookie).contains("oauth_state_google=");
    assertThat(kakaoCookie).contains("oauth_state_kakao=");
    assertThat(googleCookie).doesNotContain("oauth_state_kakao");
    assertThat(kakaoCookie).doesNotContain("oauth_state_google");
  }

  @Test
  void googleAuthorize_productionConfig_setsCookieWithNoneAndSecure() throws Exception {
    MvcResult result =
        mockMvcNone
            .perform(get("/api/oauth2/authorize/google"))
            .andExpect(status().isFound())
            .andReturn();

    String setCookie = result.getResponse().getHeader("Set-Cookie");
    assertThat(setCookie).containsIgnoringCase("SameSite=None");
    assertThat(setCookie).containsIgnoringCase("Secure");
  }

  // ── Callback: state cookie missing — STATE_COOKIE_MISSING ────────────────

  @Test
  void googleCallback_missingStateCookie_redirectsToLoginError() throws Exception {
    // No oauth_state_google cookie → simulates Safari ITP blocking the cookie
    mockMvcLax
        .perform(
            get("/api/oauth2/callback/google")
                .param("code", "auth-code-123")
                .param("state", "some-state-uuid"))
        .andExpect(status().isFound())
        .andExpect(
            header()
                .string("Location", org.hamcrest.Matchers.containsString("oauth_state_invalid")));

    verify(authService, never()).handleGoogleCallback(any());
  }

  @Test
  void kakaoCallback_missingStateCookie_redirectsToLoginError() throws Exception {
    mockMvcLax
        .perform(
            get("/api/oauth2/callback/kakao")
                .param("code", "kakao-code-123")
                .param("state", "some-state-uuid"))
        .andExpect(status().isFound())
        .andExpect(
            header()
                .string("Location", org.hamcrest.Matchers.containsString("oauth_state_invalid")));

    verify(authService, never()).handleKakaoCallback(any());
  }

  // ── Callback: state mismatch — STATE_MISMATCH ─────────────────────────────

  @Test
  void googleCallback_stateMismatch_redirectsToLoginError() throws Exception {
    mockMvcLax
        .perform(
            get("/api/oauth2/callback/google")
                .param("code", "auth-code-123")
                .param("state", "query-param-state")
                .cookie(new Cookie("oauth_state_google", "different-cookie-state")))
        .andExpect(status().isFound())
        .andExpect(
            header()
                .string("Location", org.hamcrest.Matchers.containsString("oauth_state_invalid")));

    verify(authService, never()).handleGoogleCallback(any());
  }

  // ── Callback: state expired / already used — STATE_EXPIRED_OR_USED ────────

  @Test
  void googleCallback_stateExpiredInRedis_redirectsToLoginError() throws Exception {
    String state = "expired-uuid";
    when(redisTemplate.delete(OAuthStateService.REDIS_KEY_PREFIX + state)).thenReturn(false);

    mockMvcLax
        .perform(
            get("/api/oauth2/callback/google")
                .param("code", "auth-code-123")
                .param("state", state)
                .cookie(new Cookie("oauth_state_google", state)))
        .andExpect(status().isFound())
        .andExpect(
            header()
                .string("Location", org.hamcrest.Matchers.containsString("oauth_state_invalid")));

    verify(authService, never()).handleGoogleCallback(any());
  }

  // ── Callback: success ─────────────────────────────────────────────────────

  @Test
  void googleCallback_validState_callsHandlerAndRedirectsToDashboard() throws Exception {
    String state = "valid-state-uuid";
    when(redisTemplate.delete(OAuthStateService.REDIS_KEY_PREFIX + state)).thenReturn(true);

    ResponseCookie jwtCookie = ResponseCookie.from("briefy_token", "jwt-value").path("/").build();
    when(authService.handleGoogleCallback("auth-code-123"))
        .thenReturn(new AuthCallbackResult(jwtCookie, FRONTEND_URL + "/dashboard"));

    mockMvcLax
        .perform(
            get("/api/oauth2/callback/google")
                .param("code", "auth-code-123")
                .param("state", state)
                .cookie(new Cookie("oauth_state_google", state)))
        .andExpect(status().isFound())
        .andExpect(header().string("Location", FRONTEND_URL + "/dashboard"));

    verify(authService).handleGoogleCallback("auth-code-123");
  }

  @Test
  void kakaoCallback_validState_callsHandlerAndRedirects() throws Exception {
    String state = "valid-kakao-state";
    when(redisTemplate.delete(OAuthStateService.REDIS_KEY_PREFIX + state)).thenReturn(true);

    ResponseCookie jwtCookie = ResponseCookie.from("briefy_token", "jwt-value").path("/").build();
    when(authService.handleKakaoCallback("kakao-code"))
        .thenReturn(new AuthCallbackResult(jwtCookie, FRONTEND_URL + "/onboarding"));

    mockMvcLax
        .perform(
            get("/api/oauth2/callback/kakao")
                .param("code", "kakao-code")
                .param("state", state)
                .cookie(new Cookie("oauth_state_kakao", state)))
        .andExpect(status().isFound())
        .andExpect(header().string("Location", FRONTEND_URL + "/onboarding"));

    verify(authService).handleKakaoCallback("kakao-code");
  }

  // ── Callback: Google cookie does not satisfy Kakao callback ──────────────

  @Test
  void kakaoCallback_googleCookiePresentButKakaoCookieMissing_redirectsToError() throws Exception {
    // Starting Google OAuth then attempting Kakao callback should not succeed
    // because the provider-specific cookie name is different.
    mockMvcLax
        .perform(
            get("/api/oauth2/callback/kakao")
                .param("code", "kakao-code")
                .param("state", "some-state")
                .cookie(new Cookie("oauth_state_google", "some-state")))
        .andExpect(status().isFound())
        .andExpect(
            header()
                .string("Location", org.hamcrest.Matchers.containsString("oauth_state_invalid")));

    verify(authService, never()).handleKakaoCallback(any());
  }

  // ── Callback: error / cancel ──────────────────────────────────────────────

  @Test
  void googleCallback_errorParam_redirectsToErrorPage() throws Exception {
    mockMvcLax
        .perform(get("/api/oauth2/callback/google").param("error", "access_denied"))
        .andExpect(status().isFound())
        .andExpect(
            header().string("Location", org.hamcrest.Matchers.containsString("oauth_failed")));

    verify(authService, never()).handleGoogleCallback(any());
  }

  @Test
  void kakaoCallback_errorParam_redirectsToCancelledPage() throws Exception {
    mockMvcLax
        .perform(get("/api/oauth2/callback/kakao").param("error", "access_denied"))
        .andExpect(status().isFound())
        .andExpect(
            header().string("Location", org.hamcrest.Matchers.containsString("oauth_cancelled")));

    verify(authService, never()).handleKakaoCallback(any());
  }

  // ── Callback: state cookie is cleared on failure ──────────────────────────

  @Test
  void googleCallback_stateValidationFailure_clearsStateCookie() throws Exception {
    MvcResult result =
        mockMvcLax
            .perform(
                get("/api/oauth2/callback/google")
                    .param("code", "code")
                    .param("state", "state-val"))
            .andReturn();

    String setCookie = result.getResponse().getHeader("Set-Cookie");
    assertThat(setCookie).contains("oauth_state_google=;");
    assertThat(setCookie).containsIgnoringCase("Max-Age=0");
  }
}
