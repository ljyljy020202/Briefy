package com.briefy.domain.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.briefy.config.JwtProperties;
import com.briefy.global.auth.JwtPrincipal;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JwtTokenProviderTest {

  private static final String TEST_SECRET = "test-secret-with-at-least-32-bytes!!";

  private JwtTokenProvider jwtTokenProvider;

  @BeforeEach
  void setUp() {
    JwtProperties props =
        new JwtProperties(TEST_SECRET, 3600L, "briefy_access_token", false, "Lax");
    jwtTokenProvider = new JwtTokenProvider(props);
  }

  @Test
  void createToken_and_parseToken_roundTrip() {
    String token = jwtTokenProvider.createToken(1L, "user@example.com", "USER");

    JwtPrincipal principal = jwtTokenProvider.parseToken(token);

    assertThat(principal.userId()).isEqualTo(1L);
    assertThat(principal.email()).isEqualTo("user@example.com");
    assertThat(principal.role()).isEqualTo("USER");
  }

  @Test
  void createToken_withAdminRole_preservesRole() {
    String token = jwtTokenProvider.createToken(42L, "admin@example.com", "ADMIN");

    JwtPrincipal principal = jwtTokenProvider.parseToken(token);

    assertThat(principal.userId()).isEqualTo(42L);
    assertThat(principal.role()).isEqualTo("ADMIN");
  }

  @Test
  void isValid_returnsTrue_forValidToken() {
    String token = jwtTokenProvider.createToken(1L, "user@example.com", "USER");

    assertThat(jwtTokenProvider.isValid(token)).isTrue();
  }

  @Test
  void isValid_returnsFalse_forInvalidToken() {
    assertThat(jwtTokenProvider.isValid("invalid.token.here")).isFalse();
  }

  @Test
  void isValid_returnsFalse_forTamperedToken() {
    String token = jwtTokenProvider.createToken(1L, "user@example.com", "USER");
    String tampered = token.substring(0, token.length() - 5) + "XXXXX";

    assertThat(jwtTokenProvider.isValid(tampered)).isFalse();
  }

  @Test
  void isValid_returnsFalse_forExpiredToken() {
    JwtProperties expiredProps =
        new JwtProperties(TEST_SECRET, -1L, "briefy_access_token", false, "Lax");
    JwtTokenProvider expiredProvider = new JwtTokenProvider(expiredProps);
    String token = expiredProvider.createToken(1L, "user@example.com", "USER");

    assertThat(jwtTokenProvider.isValid(token)).isFalse();
  }

  @Test
  void isValid_returnsFalse_forTokenSignedWithDifferentSecret() {
    JwtProperties otherProps =
        new JwtProperties(
            "other-secret-with-at-least-32-bytes!!", 3600L, "briefy_access_token", false, "Lax");
    JwtTokenProvider otherProvider = new JwtTokenProvider(otherProps);
    String token = otherProvider.createToken(1L, "user@example.com", "USER");

    assertThat(jwtTokenProvider.isValid(token)).isFalse();
  }

  @Test
  void parseToken_throwsJwtException_forInvalidToken() {
    assertThatThrownBy(() -> jwtTokenProvider.parseToken("not.a.valid.jwt"))
        .isInstanceOf(JwtException.class);
  }
}
