package com.briefy.domain.auth.service;

import com.briefy.config.JwtProperties;
import java.time.Duration;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

@Service
public class OAuthStateService {

  public static final String REDIS_KEY_PREFIX = "oauth:state:";
  private static final String COOKIE_NAME_PREFIX = "oauth_state_";
  private static final Duration STATE_TTL = Duration.ofMinutes(10);
  private static final int STATE_COOKIE_MAX_AGE_SECONDS = 600;

  /** Detailed result of state validation, used for structured diagnostic logging. */
  public enum StateValidationResult {
    SUCCESS,
    /** OAuth provider did not return a state parameter. */
    CALLBACK_STATE_MISSING,
    /** Browser did not send the state cookie (e.g. blocked by Safari ITP). */
    STATE_COOKIE_MISSING,
    /** State parameter and state cookie values do not match. */
    STATE_MISMATCH,
    /** State exists but was not found in Redis — expired or already consumed. */
    STATE_EXPIRED_OR_USED
  }

  private final StringRedisTemplate redisTemplate;
  private final JwtProperties jwtProperties;

  public OAuthStateService(StringRedisTemplate redisTemplate, JwtProperties jwtProperties) {
    this.redisTemplate = redisTemplate;
    this.jwtProperties = jwtProperties;
  }

  /** Provider-specific cookie name prevents Google/Kakao states from overwriting each other. */
  public static String cookieName(String provider) {
    return COOKIE_NAME_PREFIX + provider;
  }

  public String generateAndStore() {
    String state = UUID.randomUUID().toString();
    redisTemplate.opsForValue().set(REDIS_KEY_PREFIX + state, "1", STATE_TTL);
    return state;
  }

  /**
   * Builds the state correlation cookie.
   *
   * <p>SameSite follows {@code jwt.cookie-same-site} so the state cookie and JWT cookie share the
   * same cross-site policy. In production (SameSite=None; Secure) this prevents Safari ITP from
   * blocking the cookie when the OAuth callback arrives from a cross-site redirect chain.
   */
  public ResponseCookie buildStateCookie(String state, String provider) {
    return ResponseCookie.from(cookieName(provider), state)
        .httpOnly(true)
        .secure(jwtProperties.cookieSecure())
        .sameSite(jwtProperties.cookieSameSite())
        .path("/api/oauth2")
        .maxAge(STATE_COOKIE_MAX_AGE_SECONDS)
        .build();
  }

  public ResponseCookie buildClearedStateCookie(String provider) {
    return ResponseCookie.from(cookieName(provider), "")
        .httpOnly(true)
        .secure(jwtProperties.cookieSecure())
        .sameSite(jwtProperties.cookieSameSite())
        .path("/api/oauth2")
        .maxAge(0)
        .build();
  }

  /**
   * Validates the state and consumes the Redis entry on success (one-time use).
   *
   * @return {@link StateValidationResult#SUCCESS} if valid; a specific failure reason otherwise
   */
  public StateValidationResult validateAndConsume(String queryParamState, String cookieState) {
    if (queryParamState == null) {
      return StateValidationResult.CALLBACK_STATE_MISSING;
    }
    if (cookieState == null) {
      return StateValidationResult.STATE_COOKIE_MISSING;
    }
    if (!queryParamState.equals(cookieState)) {
      return StateValidationResult.STATE_MISMATCH;
    }
    Boolean deleted = redisTemplate.delete(REDIS_KEY_PREFIX + queryParamState);
    return Boolean.TRUE.equals(deleted)
        ? StateValidationResult.SUCCESS
        : StateValidationResult.STATE_EXPIRED_OR_USED;
  }
}
