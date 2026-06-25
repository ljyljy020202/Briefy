package com.briefy.domain.topic.controller;

import com.briefy.domain.topic.dto.TopicResponse;
import com.briefy.domain.topic.service.TopicService;
import com.briefy.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "토픽", description = "전체 토픽 목록 조회 (인증 불필요)")
@RestController
@RequestMapping("/api/topics")
public class TopicController {

  private final TopicService topicService;

  public TopicController(TopicService topicService) {
    this.topicService = topicService;
  }

  @Operation(summary = "전체 토픽 목록", description = "서비스에서 제공하는 모든 활성 토픽을 반환합니다.")
  @GetMapping
  public ResponseEntity<ApiResponse<List<TopicResponse>>> getAllTopics() {
    return ResponseEntity.ok(ApiResponse.success(topicService.getActiveTopics()));
  }
}
