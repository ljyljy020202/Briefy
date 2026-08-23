package com.briefy.domain.preference.controller;

import com.briefy.domain.preference.dto.BriefingCategoryResponse;
import com.briefy.domain.preference.service.BriefingCategoryService;
import com.briefy.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "브리핑 카테고리", description = "브리핑 카테고리 목록 조회")
@RestController
@RequestMapping("/api/briefing-categories")
public class BriefingCategoryController {

  private final BriefingCategoryService briefingCategoryService;

  public BriefingCategoryController(BriefingCategoryService briefingCategoryService) {
    this.briefingCategoryService = briefingCategoryService;
  }

  @Operation(
      summary = "활성 브리핑 카테고리 목록 조회",
      description = "현재 활성화된 브리핑 카테고리 목록을 반환합니다. 선호도 설정 시 카테고리 선택에 사용합니다.")
  @GetMapping
  public ResponseEntity<ApiResponse<List<BriefingCategoryResponse>>> getActiveCategories() {
    return ResponseEntity.ok(ApiResponse.success(briefingCategoryService.getActiveCategories()));
  }
}
