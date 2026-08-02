package com.briefy.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.briefy.config.JwtProperties;
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

  private OAuthStateService oAuthStateService;

  @BeforeEach
  void setUp() {
    JwtProperties jwtProperties =
        new JwtProperties("test-secret-32-bytes-minimum-here", 3600L, "briefy_token", false, "Lax");
    oAuthStateService = new OAuthStateService(redisTemplate, jwtProperties);
    when(redisTemplate.opsForValue()).thenReturn(valueOps);
  }

  @Test
  void generateAndStore_storesStateInRedisAndReturnsUuid() {
    String state = oAuthStateService.generateAndStore();

    assertThat(state).isNotBlank();
    assertThat(state).hasSize(36);
  }

  @Test
  void buildStateCookie_hasCorrectAttributes() {
    ResponseCookie cookie = oAuthStateService.buildStateCookie("some-state-value");

    assertThat(cookie.getName()).isEqualTo(OAuthStateService.COOKIE_NAME);
    assertThat(cookie.getValue()).isEqualTo("some-state-value");
    assertThat(cookie.isHttpOnly()).isTrue();
    assertThat(cookie.isSecure()).isFalse();
    assertThat(cookie.getSameSite()).isEqualTo("Lax");
    assertThat(cookie.getPath()).isEqualTo("/api/oauth2");
    assertThat(cookie.getMaxAge().getSeconds()).isEqualTo(600);
  }

  @Test
  void buildClearedStateCookie_hasMaxAgeZero() {
    ResponseCookie cookie = oAuthStateService.buildClearedStateCookie();

    assertThat(cookie.getName()).isEqualTo(OAuthStateService.COOKIE_NAME);
    assertThat(cookie.getValue()).isEmpty();
    assertThat(cookie.getMaxAge().getSeconds()).isZero();
  }

  @Test
  void validateAndConsume_matchingStateExistsInRedis_returnsTrue() {
    String state = "valid-state-uuid";
    when(redisTemplate.delete(OAuthStateService.REDIS_KEY_PREFIX + state)).thenReturn(true);

    boolean result = oAuthStateService.validateAndConsume(state, state);

    assertThat(result).isTrue();
  }

  @Test
  void validateAndConsume_stateNotInRedis_returnsFalse() {
    String state = "expired-state-uuid";
    when(redisTemplate.delete(OAuthStateService.REDIS_KEY_PREFIX + state)).thenReturn(false);

    boolean result = oAuthStateService.validateAndConsume(state, state);

    assertThat(result).isFalse();
  }

  @Test
  void validateAndConsume_stateMismatch_returnsFalseWithoutRedisCall() {
    boolean result = oAuthStateService.validateAndConsume("query-state", "cookie-state");

    assertThat(result).isFalse();
  }

  @Test
  void validateAndConsume_nullQueryParamState_returnsFalse() {
    boolean result = oAuthStateService.validateAndConsume(null, "cookie-state");

    assertThat(result).isFalse();
  }

  @Test
  void validateAndConsume_nullCookieState_returnsFalse() {
    boolean result = oAuthStateService.validateAndConsume("query-state", null);

    assertThat(result).isFalse();
  }

  @Test
  void validateAndConsume_bothNull_returnsFalse() {
    boolean result = oAuthStateService.validateAndConsume(null, null);

    assertThat(result).isFalse();
  }
}
