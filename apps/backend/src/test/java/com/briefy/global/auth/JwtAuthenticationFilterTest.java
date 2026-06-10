package com.briefy.global.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.briefy.config.JwtProperties;
import com.briefy.domain.auth.service.JwtTokenProvider;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

  @Mock private JwtTokenProvider jwtTokenProvider;

  private JwtAuthenticationFilter filter;

  @BeforeEach
  void setUp() {
    JwtProperties props =
        new JwtProperties(
            "test-secret-with-at-least-32-bytes!!", 3600L, "briefy_access_token", false, "Lax");
    filter = new JwtAuthenticationFilter(jwtTokenProvider, props);
    SecurityContextHolder.clearContext();
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void doFilter_setsAuthentication_whenValidCookiePresent() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setCookies(new Cookie("briefy_access_token", "valid.jwt.token"));
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain filterChain = mock(FilterChain.class);

    JwtPrincipal principal = new JwtPrincipal(1L, "user@example.com", "USER");
    when(jwtTokenProvider.isValid("valid.jwt.token")).thenReturn(true);
    when(jwtTokenProvider.parseToken("valid.jwt.token")).thenReturn(principal);

    filter.doFilter(request, response, filterChain);

    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    assertThat(auth).isNotNull();
    assertThat(auth.isAuthenticated()).isTrue();
    assertThat(auth.getPrincipal()).isEqualTo(principal);
    assertThat(auth.getAuthorities()).extracting("authority").containsExactly("ROLE_USER");
    verify(filterChain).doFilter(request, response);
  }

  @Test
  void doFilter_setsAdminAuthority_forAdminRole() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setCookies(new Cookie("briefy_access_token", "admin.jwt.token"));
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain filterChain = mock(FilterChain.class);

    JwtPrincipal principal = new JwtPrincipal(2L, "admin@example.com", "ADMIN");
    when(jwtTokenProvider.isValid("admin.jwt.token")).thenReturn(true);
    when(jwtTokenProvider.parseToken("admin.jwt.token")).thenReturn(principal);

    filter.doFilter(request, response, filterChain);

    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    assertThat(auth.getAuthorities()).extracting("authority").containsExactly("ROLE_ADMIN");
  }

  @Test
  void doFilter_doesNotSetAuthentication_whenNoCookies() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain filterChain = mock(FilterChain.class);

    filter.doFilter(request, response, filterChain);

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    verify(filterChain).doFilter(request, response);
    verify(jwtTokenProvider, never()).isValid(any());
  }

  @Test
  void doFilter_doesNotSetAuthentication_whenCookieHasInvalidToken() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setCookies(new Cookie("briefy_access_token", "invalid.token"));
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain filterChain = mock(FilterChain.class);

    when(jwtTokenProvider.isValid("invalid.token")).thenReturn(false);

    filter.doFilter(request, response, filterChain);

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    verify(filterChain).doFilter(request, response);
    verify(jwtTokenProvider, never()).parseToken(any());
  }

  @Test
  void doFilter_doesNotSetAuthentication_whenCookieNameDoesNotMatch() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setCookies(new Cookie("other_cookie", "some.token"));
    MockHttpServletResponse response = new MockHttpServletResponse();
    FilterChain filterChain = mock(FilterChain.class);

    filter.doFilter(request, response, filterChain);

    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    verify(jwtTokenProvider, never()).isValid(any());
  }
}
