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
  public static final String COOKIE_NAME = "oauth_state";
  private static final Duration STATE_TTL = Duration.ofMinutes(10);
  private static final int STATE_COOKIE_MAX_AGE_SECONDS = 600;

  private final StringRedisTemplate redisTemplate;
  private final JwtProperties jwtProperties;

  public OAuthStateService(StringRedisTemplate redisTemplate, JwtProperties jwtProperties) {
    this.redisTemplate = redisTemplate;
    this.jwtProperties = jwtProperties;
  }

  public String generateAndStore() {
    String state = UUID.randomUUID().toString();
    redisTemplate.opsForValue().set(REDIS_KEY_PREFIX + state, "1", STATE_TTL);
    return state;
  }

  public ResponseCookie buildStateCookie(String state) {
    return ResponseCookie.from(COOKIE_NAME, state)
        .httpOnly(true)
        .secure(jwtProperties.cookieSecure())
        .sameSite("Lax")
        .path("/api/oauth2")
        .maxAge(STATE_COOKIE_MAX_AGE_SECONDS)
        .build();
  }

  public ResponseCookie buildClearedStateCookie() {
    return ResponseCookie.from(COOKIE_NAME, "")
        .httpOnly(true)
        .secure(jwtProperties.cookieSecure())
        .sameSite("Lax")
        .path("/api/oauth2")
        .maxAge(0)
        .build();
  }

  /**
   * Validates that queryParamState matches cookieState and that the state exists in Redis (one-time
   * use). Deletes the Redis entry on successful validation.
   */
  public boolean validateAndConsume(String queryParamState, String cookieState) {
    if (queryParamState == null || cookieState == null || !queryParamState.equals(cookieState)) {
      return false;
    }
    Boolean deleted = redisTemplate.delete(REDIS_KEY_PREFIX + queryParamState);
    return Boolean.TRUE.equals(deleted);
  }
}
