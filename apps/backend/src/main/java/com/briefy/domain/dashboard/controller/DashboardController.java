package com.briefy.domain.dashboard.controller;

import com.briefy.domain.dashboard.dto.DashboardResponse;
import com.briefy.domain.dashboard.service.DashboardService;
import com.briefy.global.auth.AuthenticatedUser;
import com.briefy.global.auth.CurrentUserProvider;
import com.briefy.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "대시보드", description = "대시보드 요약 데이터")
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

  private final DashboardService dashboardService;
  private final CurrentUserProvider currentUserProvider;

  public DashboardController(
      DashboardService dashboardService, CurrentUserProvider currentUserProvider) {
    this.dashboardService = dashboardService;
    this.currentUserProvider = currentUserProvider;
  }

  @Operation(summary = "대시보드 조회", description = "현재 사용자의 대시보드 요약 데이터를 반환합니다.")
  @GetMapping
  public ResponseEntity<ApiResponse<DashboardResponse>> getDashboard() {
    AuthenticatedUser auth = currentUserProvider.getCurrentUser();
    return ResponseEntity.ok(ApiResponse.success(dashboardService.getDashboard(auth.userId())));
  }
}
