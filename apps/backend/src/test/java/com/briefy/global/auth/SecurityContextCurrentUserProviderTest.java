package com.briefy.global.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.briefy.global.exception.BusinessException;
import com.briefy.global.exception.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class SecurityContextCurrentUserProviderTest {

  private SecurityContextCurrentUserProvider provider;

  @BeforeEach
  void setUp() {
    provider = new SecurityContextCurrentUserProvider();
    SecurityContextHolder.clearContext();
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void getCurrentUser_returnsAuthenticatedUser_whenJwtPrincipalIsPresent() {
    JwtPrincipal principal = new JwtPrincipal(42L, "user@example.com", "USER");
    UsernamePasswordAuthenticationToken auth =
        new UsernamePasswordAuthenticationToken(
            principal, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    SecurityContextHolder.getContext().setAuthentication(auth);

    AuthenticatedUser result = provider.getCurrentUser();

    assertThat(result.userId()).isEqualTo(42L);
  }

  @Test
  void getCurrentUser_throwsUnauthorized_whenNotAuthenticated() {
    assertThatThrownBy(() -> provider.getCurrentUser())
        .isInstanceOf(BusinessException.class)
        .satisfies(
            ex ->
                assertThat(((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.UNAUTHORIZED));
  }

  @Test
  void getCurrentUser_throwsUnauthorized_whenPrincipalIsAnonymousUser() {
    UsernamePasswordAuthenticationToken auth =
        new UsernamePasswordAuthenticationToken("anonymousUser", null, List.of());
    SecurityContextHolder.getContext().setAuthentication(auth);

    assertThatThrownBy(() -> provider.getCurrentUser())
        .isInstanceOf(BusinessException.class)
        .satisfies(
            ex ->
                assertThat(((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.UNAUTHORIZED));
  }

  @Test
  void getCurrentUser_throwsUnauthorized_whenPrincipalIsNotJwtPrincipal() {
    UsernamePasswordAuthenticationToken auth =
        new UsernamePasswordAuthenticationToken(
            "some-string-principal", null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    SecurityContextHolder.getContext().setAuthentication(auth);

    assertThatThrownBy(() -> provider.getCurrentUser())
        .isInstanceOf(BusinessException.class)
        .satisfies(
            ex ->
                assertThat(((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.UNAUTHORIZED));
  }
}
