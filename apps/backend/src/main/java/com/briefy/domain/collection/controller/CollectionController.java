package com.briefy.domain.collection.controller;

import com.briefy.domain.collection.dto.DailyCollectionResult;
import com.briefy.domain.collection.dto.TriggerDailyCollectionRequest;
import com.briefy.domain.collection.service.DailyCollectionService;
import com.briefy.global.response.ApiResponse;
import java.time.LocalDate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/collections")
public class CollectionController {

  private final DailyCollectionService dailyCollectionService;

  public CollectionController(DailyCollectionService dailyCollectionService) {
    this.dailyCollectionService = dailyCollectionService;
  }

  @PostMapping("/daily")
  public ResponseEntity<ApiResponse<DailyCollectionResult>> triggerDailyCollection(
      @RequestBody TriggerDailyCollectionRequest request) {
    LocalDate collectDate = request.collectDate() != null ? request.collectDate() : LocalDate.now();
    DailyCollectionResult result =
        dailyCollectionService.triggerDailyCollection(collectDate, request.categories());
    return ResponseEntity.ok(ApiResponse.success(result));
  }
}
