package com.briefy.domain.candidatepool.controller;

import com.briefy.domain.candidatepool.dto.AnalysisStatsResponse;
import com.briefy.domain.candidatepool.dto.BackfillRequest;
import com.briefy.domain.candidatepool.dto.BackfillResponse;
import com.briefy.domain.candidatepool.service.AnalysisBackfillService;
import com.briefy.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "관리자 - 분류 분석", description = "공고 분류 backfill 및 통계 API (ROLE_ADMIN 필요)")
@RestController
@RequestMapping("/api/admin/job-posting-analyses")
public class AdminAnalysisController {

  private static final Logger log = LoggerFactory.getLogger(AdminAnalysisController.class);

  private final AnalysisBackfillService backfillService;

  public AdminAnalysisController(AnalysisBackfillService backfillService) {
    this.backfillService = backfillService;
  }

  @Operation(
      summary = "분류 backfill 등록",
      description =
          "대상 공고를 분류 PENDING으로 등록한다. dryRun=true면 상태 변경 없이 건수만 반환한다." + " 처리는 분류 작업자가 비동기로 실행한다.")
  @PostMapping("/backfill")
  public ResponseEntity<ApiResponse<BackfillResponse>> backfill(
      @RequestBody BackfillRequest request) {
    log.info(
        "Admin backfill requested dryRun={} limit={} forceReclassify={}",
        request.dryRun(),
        request.limit(),
        request.forceReclassify());
    BackfillResponse response = backfillService.backfill(request);
    // 202 Accepted: 등록만 완료, 실제 분류 처리는 비동기
    HttpStatus status = request.dryRun() ? HttpStatus.OK : HttpStatus.ACCEPTED;
    return ResponseEntity.status(status).body(ApiResponse.success(response));
  }

  @Operation(summary = "분류 분석 상태 통계 조회", description = "job_posting_analyses 테이블의 상태별 건수를 반환한다.")
  @GetMapping("/stats")
  public ResponseEntity<ApiResponse<AnalysisStatsResponse>> stats() {
    AnalysisStatsResponse response = backfillService.stats();
    return ResponseEntity.ok(ApiResponse.success(response));
  }
}
