package com.briefy.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.briefy.config.JwtProperties;
import com.briefy.domain.auth.service.OAuthStateService.StateValidationResult;
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

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OAuthStateServiceTest {

  @Mock private StringRedisTemplate redisTemplate;
  @Mock private ValueOperations<String, String> valueOps;

  private OAuthStateService oAuthStateServiceLax;
  private OAuthStateService oAuthStateServiceNone;

  @BeforeEach
  void setUp() {
    JwtProperties laxProps =
        new JwtProperties("test-secret-32-bytes-minimum-here", 3600L, "briefy_token", false, "Lax");
    JwtProperties noneProps =
        new JwtProperties("test-secret-32-bytes-minimum-here", 3600L, "briefy_token", true, "None");
    oAuthStateServiceLax = new OAuthStateService(redisTemplate, laxProps);
    oAuthStateServiceNone = new OAuthStateService(redisTemplate, noneProps);
    when(redisTemplate.opsForValue()).thenReturn(valueOps);
  }

  // ── Cookie name ──────────────────────────────────────────────────────────

  @Test
  void cookieName_google_hasProviderSuffix() {
    assertThat(OAuthStateService.cookieName("google")).isEqualTo("oauth_state_google");
  }

  @Test
  void cookieName_kakao_hasProviderSuffix() {
    assertThat(OAuthStateService.cookieName("kakao")).isEqualTo("oauth_state_kakao");
  }

  @Test
  void googleAndKakaoCookieNames_areDifferent() {
    assertThat(OAuthStateService.cookieName("google"))
        .isNotEqualTo(OAuthStateService.cookieName("kakao"));
  }

  // ── State cookie attributes (local / SameSite=Lax) ───────────────────────

  @Test
  void buildStateCookie_laxConfig_hasCorrectAttributes() {
    ResponseCookie cookie = oAuthStateServiceLax.buildStateCookie("some-state-value", "google");

    assertThat(cookie.getName()).isEqualTo("oauth_state_google");
    assertThat(cookie.getValue()).isEqualTo("some-state-value");
    assertThat(cookie.isHttpOnly()).isTrue();
    assertThat(cookie.isSecure()).isFalse();
    assertThat(cookie.getSameSite()).isEqualTo("Lax");
    assertThat(cookie.getPath()).isEqualTo("/api/oauth2");
    assertThat(cookie.getMaxAge().getSeconds()).isEqualTo(600);
  }

  @Test
  void buildStateCookie_kakao_hasKakaoCookieName() {
    ResponseCookie cookie = oAuthStateServiceLax.buildStateCookie("some-state-value", "kakao");
    assertThat(cookie.getName()).isEqualTo("oauth_state_kakao");
  }

  // ── State cookie attributes (production / SameSite=None) ─────────────────

  @Test
  void buildStateCookie_productionNoneConfig_sameSiteIsNoneAndSecure() {
    ResponseCookie cookie = oAuthStateServiceNone.buildStateCookie("some-state-value", "google");

    assertThat(cookie.getSameSite()).isEqualTo("None");
    assertThat(cookie.isSecure()).isTrue();
    assertThat(cookie.isHttpOnly()).isTrue();
    assertThat(cookie.getPath()).isEqualTo("/api/oauth2");
    assertThat(cookie.getMaxAge().getSeconds()).isEqualTo(600);
  }

  // ── Cleared cookie ────────────────────────────────────────────────────────

  @Test
  void buildClearedStateCookie_hasMaxAgeZero() {
    ResponseCookie cookie = oAuthStateServiceLax.buildClearedStateCookie("google");

    assertThat(cookie.getName()).isEqualTo("oauth_state_google");
    assertThat(cookie.getValue()).isEmpty();
    assertThat(cookie.getMaxAge().getSeconds()).isZero();
  }

  @Test
  void buildClearedStateCookie_sameAttributesAsStateCookie() {
    ResponseCookie state = oAuthStateServiceNone.buildStateCookie("val", "kakao");
    ResponseCookie cleared = oAuthStateServiceNone.buildClearedStateCookie("kakao");

    assertThat(cleared.getSameSite()).isEqualTo(state.getSameSite());
    assertThat(cleared.isSecure()).isEqualTo(state.isSecure());
    assertThat(cleared.getPath()).isEqualTo(state.getPath());
    assertThat(cleared.isHttpOnly()).isEqualTo(state.isHttpOnly());
  }

  // ── State generation ──────────────────────────────────────────────────────

  @Test
  void generateAndStore_storesStateInRedisAndReturnsUuid() {
    String state = oAuthStateServiceLax.generateAndStore();

    assertThat(state).isNotBlank();
    assertThat(state).hasSize(36);
  }

  // ── validateAndConsume ────────────────────────────────────────────────────

  @Test
  void validateAndConsume_matchingStateExistsInRedis_returnsSuccess() {
    String state = "valid-state-uuid";
    when(redisTemplate.delete(OAuthStateService.REDIS_KEY_PREFIX + state)).thenReturn(true);

    assertThat(oAuthStateServiceLax.validateAndConsume(state, state))
        .isEqualTo(StateValidationResult.SUCCESS);
  }

  @Test
  void validateAndConsume_stateNotInRedis_returnsExpiredOrUsed() {
    String state = "expired-state-uuid";
    when(redisTemplate.delete(OAuthStateService.REDIS_KEY_PREFIX + state)).thenReturn(false);

    assertThat(oAuthStateServiceLax.validateAndConsume(state, state))
        .isEqualTo(StateValidationResult.STATE_EXPIRED_OR_USED);
  }

  @Test
  void validateAndConsume_stateMismatch_returnsMismatch() {
    assertThat(oAuthStateServiceLax.validateAndConsume("query-state", "cookie-state"))
        .isEqualTo(StateValidationResult.STATE_MISMATCH);
  }

  @Test
  void validateAndConsume_nullQueryParamState_returnsCallbackStateMissing() {
    assertThat(oAuthStateServiceLax.validateAndConsume(null, "cookie-state"))
        .isEqualTo(StateValidationResult.CALLBACK_STATE_MISSING);
  }

  @Test
  void validateAndConsume_nullCookieState_returnsCookieMissing() {
    assertThat(oAuthStateServiceLax.validateAndConsume("query-state", null))
        .isEqualTo(StateValidationResult.STATE_COOKIE_MISSING);
  }

  @Test
  void validateAndConsume_bothNull_returnsCallbackStateMissing() {
    assertThat(oAuthStateServiceLax.validateAndConsume(null, null))
        .isEqualTo(StateValidationResult.CALLBACK_STATE_MISSING);
  }

  @Test
  void validateAndConsume_sameStateConsumedTwice_secondCallReturnsExpiredOrUsed() {
    String state = "reuse-state-uuid";
    // First call succeeds
    when(redisTemplate.delete(OAuthStateService.REDIS_KEY_PREFIX + state))
        .thenReturn(true)
        .thenReturn(false);

    assertThat(oAuthStateServiceLax.validateAndConsume(state, state))
        .isEqualTo(StateValidationResult.SUCCESS);
    assertThat(oAuthStateServiceLax.validateAndConsume(state, state))
        .isEqualTo(StateValidationResult.STATE_EXPIRED_OR_USED);
  }
}
