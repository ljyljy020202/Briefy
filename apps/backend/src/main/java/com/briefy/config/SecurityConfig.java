package com.briefy.config;

import com.briefy.domain.auth.service.JwtTokenProvider;
import com.briefy.global.auth.JwtAuthenticationFilter;
import com.briefy.global.exception.ErrorCode;
import com.briefy.global.response.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

  private final ObjectMapper objectMapper;
  private final AppProperties appProperties;

  public SecurityConfig(ObjectMapper objectMapper, AppProperties appProperties) {
    this.objectMapper = objectMapper;
    this.appProperties = appProperties;
  }

  @Bean
  public SecurityFilterChain securityFilterChain(
      HttpSecurity http, JwtTokenProvider jwtTokenProvider, JwtProperties jwtProperties)
      throws Exception {
    JwtAuthenticationFilter jwtFilter =
        new JwtAuthenticationFilter(jwtTokenProvider, jwtProperties);

    http.cors(cors -> cors.configurationSource(corsConfigurationSource()))
        .csrf(csrf -> csrf.disable())
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .formLogin(form -> form.disable())
        .httpBasic(basic -> basic.disable())
        .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers("/api/oauth2/**")
                    .permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/auth/logout")
                    .permitAll()
                    .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**")
                    .permitAll()
                    .requestMatchers("/api/admin/**")
                    .hasRole("ADMIN")
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            ex ->
                ex.authenticationEntryPoint(
                        (request, response, e) ->
                            writeError(
                                response,
                                HttpServletResponse.SC_UNAUTHORIZED,
                                ErrorCode.UNAUTHORIZED))
                    .accessDeniedHandler(
                        (request, response, e) ->
                            writeError(
                                response, HttpServletResponse.SC_FORBIDDEN, ErrorCode.FORBIDDEN)));
    return http.build();
  }

  @Bean
  public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOrigins(List.of(appProperties.frontendBaseUrl()));
    config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    config.setAllowedHeaders(List.of("*"));
    config.setAllowCredentials(true);
    config.setMaxAge(3600L);

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/api/**", config);
    return source;
  }

  private void writeError(HttpServletResponse response, int status, ErrorCode errorCode)
      throws java.io.IOException {
    response.setStatus(status);
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding("UTF-8");
    response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.failure(errorCode)));
  }
}
