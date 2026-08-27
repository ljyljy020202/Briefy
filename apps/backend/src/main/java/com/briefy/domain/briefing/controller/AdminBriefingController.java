package com.briefy.domain.briefing.controller;

import com.briefy.domain.briefing.dto.AdminGenerateBriefingRequest;
import com.briefy.domain.briefing.dto.GenerateResult;
import com.briefy.domain.briefing.service.BriefingService;
import com.briefy.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "관리자 - 브리핑", description = "브리핑 생성 및 배포 관리 API (ROLE_ADMIN 필요)")
@RestController
@RequestMapping("/api/admin/briefings")
public class AdminBriefingController {

  private final BriefingService briefingService;

  public AdminBriefingController(BriefingService briefingService) {
    this.briefingService = briefingService;
  }

  @Operation(summary = "브리핑 수동 생성", description = "특정 사용자의 브리핑을 즉시 생성합니다. 개발 및 테스트용.")
  @PostMapping("/generate")
  public ResponseEntity<ApiResponse<GenerateResult>> generate(
      @RequestBody @Valid AdminGenerateBriefingRequest request) {
    GenerateResult result = briefingService.generateBriefing(request.userId());
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(result));
  }

  @Operation(
      summary = "브리핑 Job 재시도",
      description = "FAILED 상태의 브리핑 작업을 재시도합니다. 최대 retry 횟수 미만이고 Report가 없을 때만 허용됩니다.")
  @PostMapping("/jobs/{jobId}/retry")
  public ResponseEntity<ApiResponse<GenerateResult>> retryJob(@PathVariable Long jobId) {
    GenerateResult result = briefingService.retryBriefingJob(jobId);
    return ResponseEntity.ok(ApiResponse.success(result));
  }
}
