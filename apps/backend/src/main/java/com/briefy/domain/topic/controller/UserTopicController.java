package com.briefy.domain.topic.controller;

import com.briefy.domain.topic.dto.BulkCreateUserTopicRequest;
import com.briefy.domain.topic.dto.BulkCreateUserTopicResponse;
import com.briefy.domain.topic.dto.CreateUserTopicRequest;
import com.briefy.domain.topic.dto.UserTopicResponse;
import com.briefy.domain.topic.service.UserTopicService;
import com.briefy.global.auth.AuthenticatedUser;
import com.briefy.global.auth.CurrentUserProvider;
import com.briefy.global.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "구독 토픽", description = "내 토픽 구독 관리")
@RestController
@RequestMapping("/api/me/topics")
public class UserTopicController {

  private final UserTopicService userTopicService;
  private final CurrentUserProvider currentUserProvider;

  public UserTopicController(
      UserTopicService userTopicService, CurrentUserProvider currentUserProvider) {
    this.userTopicService = userTopicService;
    this.currentUserProvider = currentUserProvider;
  }

  @Operation(summary = "내 구독 토픽 목록", description = "현재 사용자가 구독 중인 토픽과 키워드 목록을 반환합니다.")
  @GetMapping
  public ResponseEntity<ApiResponse<List<UserTopicResponse>>> getMyTopics() {
    AuthenticatedUser auth = currentUserProvider.getCurrentUser();
    return ResponseEntity.ok(ApiResponse.success(userTopicService.getMyTopics(auth.userId())));
  }

  @Operation(summary = "토픽 단건 구독", description = "특정 토픽을 구독합니다.")
  @PostMapping
  public ResponseEntity<ApiResponse<UserTopicResponse>> addTopic(
      @RequestBody @Valid CreateUserTopicRequest request) {
    AuthenticatedUser auth = currentUserProvider.getCurrentUser();
    UserTopicResponse response = userTopicService.addTopic(auth.userId(), request);
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
  }

  @Operation(summary = "토픽 일괄 구독", description = "여러 토픽과 키워드를 한 번에 구독합니다. 기존 구독은 모두 교체됩니다.")
  @PostMapping("/bulk")
  public ResponseEntity<ApiResponse<BulkCreateUserTopicResponse>> bulkAddTopics(
      @RequestBody @Valid BulkCreateUserTopicRequest request) {
    AuthenticatedUser auth = currentUserProvider.getCurrentUser();
    BulkCreateUserTopicResponse response = userTopicService.bulkAddTopics(auth.userId(), request);
    return ResponseEntity.ok(ApiResponse.success(response));
  }

  @Operation(summary = "토픽 구독 해제", description = "특정 구독 토픽을 삭제합니다.")
  @DeleteMapping("/{userTopicId}")
  public ResponseEntity<ApiResponse<Void>> deleteTopic(@PathVariable Long userTopicId) {
    AuthenticatedUser auth = currentUserProvider.getCurrentUser();
    userTopicService.deleteTopic(auth.userId(), userTopicId);
    return ResponseEntity.ok(ApiResponse.success());
  }
}
