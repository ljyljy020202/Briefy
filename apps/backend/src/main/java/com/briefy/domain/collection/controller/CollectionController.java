package com.briefy.domain.collection.controller;

import com.briefy.domain.collection.dto.DailyCollectionResult;
import com.briefy.domain.collection.dto.TriggerDailyCollectionRequest;
import com.briefy.domain.collection.service.DailyCollectionService;
import com.briefy.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "관리자 - 컬렉션", description = "일간 콘텐츠 수집 제어 API (ROLE_ADMIN 필요)")
@RestController
@RequestMapping("/api/admin/collections")
public class CollectionController {

  private final DailyCollectionService dailyCollectionService;

  public CollectionController(DailyCollectionService dailyCollectionService) {
    this.dailyCollectionService = dailyCollectionService;
  }

  @Operation(
      summary = "일일 공고 수집 트리거",
      description = "지정한 날짜의 채용 공고를 수집하고 후보 풀에 저장합니다. 스케줄러 미실행 시 수동으로 호출합니다.")
  @PostMapping("/daily")
  public ResponseEntity<ApiResponse<DailyCollectionResult>> triggerDailyCollection(
      @RequestBody TriggerDailyCollectionRequest request) {
    LocalDate collectDate = request.collectDate() != null ? request.collectDate() : LocalDate.now();
    DailyCollectionResult result =
        dailyCollectionService.triggerDailyCollection(collectDate, request.categories());
    return ResponseEntity.ok(ApiResponse.success(result));
  }
}
