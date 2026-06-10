package com.briefy.domain.auth.controller;

import com.briefy.domain.auth.dto.AuthCallbackResult;
import com.briefy.domain.auth.service.AuthService;
import com.briefy.global.response.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {

  private static final Logger log = LoggerFactory.getLogger(AuthController.class);

  private final AuthService authService;

  public AuthController(AuthService authService) {
    this.authService = authService;
  }

  @GetMapping("/api/oauth2/authorize/google")
  public ResponseEntity<Void> authorize() {
    return ResponseEntity.status(HttpStatus.FOUND)
        .header(HttpHeaders.LOCATION, authService.buildGoogleAuthorizationUrl())
        .build();
  }

  @GetMapping("/api/oauth2/callback/google")
  public ResponseEntity<Void> callback(
      @RequestParam(required = false) String code, @RequestParam(required = false) String error) {
    if (error != null || code == null) {
      log.warn("OAuth callback received error or missing code: error={}", error);
      return ResponseEntity.status(HttpStatus.FOUND)
          .header(HttpHeaders.LOCATION, authService.getErrorRedirectUrl())
          .build();
    }
    try {
      AuthCallbackResult result = authService.handleGoogleCallback(code);
      return ResponseEntity.status(HttpStatus.FOUND)
          .header(HttpHeaders.SET_COOKIE, result.cookie().toString())
          .header(HttpHeaders.LOCATION, result.redirectUrl())
          .build();
    } catch (Exception e) {
      log.warn("OAuth callback failed", e);
      return ResponseEntity.status(HttpStatus.FOUND)
          .header(HttpHeaders.LOCATION, authService.getErrorRedirectUrl())
          .build();
    }
  }

  @PostMapping("/api/auth/logout")
  public ResponseEntity<ApiResponse<Void>> logout() {
    return ResponseEntity.ok()
        .header(HttpHeaders.SET_COOKIE, authService.buildExpiredJwtCookie().toString())
        .body(ApiResponse.success());
  }
}
