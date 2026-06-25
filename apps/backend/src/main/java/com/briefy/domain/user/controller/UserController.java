package com.briefy.domain.user.controller;

import com.briefy.domain.user.dto.UpdateOnboardingRequest;
import com.briefy.domain.user.dto.UpdateOnboardingResponse;
import com.briefy.domain.user.dto.UserMeResponse;
import com.briefy.domain.user.service.UserService;
import com.briefy.global.auth.AuthenticatedUser;
import com.briefy.global.auth.CurrentUserProvider;
import com.briefy.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "사용자", description = "내 정보 조회 및 온보딩 설정")
@RestController
@RequestMapping("/api/users")
public class UserController {

  private final UserService userService;
  private final CurrentUserProvider currentUserProvider;

  public UserController(UserService userService, CurrentUserProvider currentUserProvider) {
    this.userService = userService;
    this.currentUserProvider = currentUserProvider;
  }

  @Operation(summary = "내 정보 조회", description = "현재 로그인한 사용자의 정보를 반환합니다.")
  @GetMapping("/me")
  public ResponseEntity<ApiResponse<UserMeResponse>> getMe() {
    AuthenticatedUser auth = currentUserProvider.getCurrentUser();
    return ResponseEntity.ok(ApiResponse.success(userService.getMe(auth.userId())));
  }

  @Operation(summary = "온보딩 완료", description = "온보딩을 완료 처리하고 닉네임을 설정합니다.")
  @PatchMapping("/me/onboarding")
  public ResponseEntity<ApiResponse<UpdateOnboardingResponse>> completeOnboarding(
      @RequestBody @Valid UpdateOnboardingRequest request) {
    AuthenticatedUser auth = currentUserProvider.getCurrentUser();
    return ResponseEntity.ok(
        ApiResponse.success(userService.completeOnboarding(auth.userId(), request)));
  }
}
