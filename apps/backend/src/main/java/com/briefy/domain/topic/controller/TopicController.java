package com.briefy.domain.topic.controller;

import com.briefy.domain.topic.dto.TopicResponse;
import com.briefy.domain.topic.service.TopicService;
import com.briefy.global.response.ApiResponse;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/topics")
public class TopicController {

  private final TopicService topicService;

  public TopicController(TopicService topicService) {
    this.topicService = topicService;
  }

  @GetMapping
  public ResponseEntity<ApiResponse<List<TopicResponse>>> getAllTopics() {
    return ResponseEntity.ok(ApiResponse.success(topicService.getActiveTopics()));
  }
}
