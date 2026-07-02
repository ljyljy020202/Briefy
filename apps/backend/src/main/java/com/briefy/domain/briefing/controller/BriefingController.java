package com.briefy.domain.briefing.controller;

import com.briefy.domain.briefing.dto.BriefingDetailResponse;
import com.briefy.domain.briefing.dto.BriefingListItem;
import com.briefy.domain.briefing.service.BriefingService;
import com.briefy.global.auth.AuthenticatedUser;
import com.briefy.global.auth.CurrentUserProvider;
import com.briefy.global.response.ApiResponse;
import com.briefy.global.response.PageResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "브리핑", description = "브리핑 생성 및 조회")
@RestController
@RequestMapping("/api/briefings")
public class BriefingController {

  private final BriefingService briefingService;
  private final CurrentUserProvider currentUserProvider;

  public BriefingController(
      BriefingService briefingService, CurrentUserProvider currentUserProvider) {
    this.briefingService = briefingService;
    this.currentUserProvider = currentUserProvider;
  }

  @Operation(summary = "브리핑 목록", description = "내 브리핑 목록을 페이지네이션으로 조회합니다.")
  @GetMapping
  public ResponseEntity<ApiResponse<PageResult<BriefingListItem>>> list(
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size) {
    AuthenticatedUser auth = currentUserProvider.getCurrentUser();
    return ResponseEntity.ok(
        ApiResponse.success(briefingService.listBriefings(auth.userId(), page, size)));
  }

  @Operation(summary = "브리핑 상세", description = "특정 브리핑 리포트의 전체 내용을 반환합니다.")
  @GetMapping("/{id}")
  public ResponseEntity<ApiResponse<BriefingDetailResponse>> getById(@PathVariable Long id) {
    AuthenticatedUser auth = currentUserProvider.getCurrentUser();
    return ResponseEntity.ok(
        ApiResponse.success(briefingService.getBriefingDetail(auth.userId(), id)));
  }
}
