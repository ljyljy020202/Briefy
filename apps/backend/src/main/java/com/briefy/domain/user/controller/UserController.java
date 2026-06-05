package com.briefy.domain.user.controller;

import com.briefy.domain.user.dto.UpdateOnboardingRequest;
import com.briefy.domain.user.dto.UpdateOnboardingResponse;
import com.briefy.domain.user.dto.UserMeResponse;
import com.briefy.domain.user.service.UserService;
import com.briefy.global.auth.AuthenticatedUser;
import com.briefy.global.auth.CurrentUserProvider;
import com.briefy.global.response.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class UserController {

  private final UserService userService;
  private final CurrentUserProvider currentUserProvider;

  public UserController(UserService userService, CurrentUserProvider currentUserProvider) {
    this.userService = userService;
    this.currentUserProvider = currentUserProvider;
  }

  @GetMapping("/me")
  public ResponseEntity<ApiResponse<UserMeResponse>> getMe() {
    AuthenticatedUser auth = currentUserProvider.getCurrentUser();
    return ResponseEntity.ok(ApiResponse.success(userService.getMe(auth.userId())));
  }

  @PatchMapping("/me/onboarding")
  public ResponseEntity<ApiResponse<UpdateOnboardingResponse>> completeOnboarding(
      @RequestBody @Valid UpdateOnboardingRequest request) {
    AuthenticatedUser auth = currentUserProvider.getCurrentUser();
    return ResponseEntity.ok(
        ApiResponse.success(userService.completeOnboarding(auth.userId(), request)));
  }
}
