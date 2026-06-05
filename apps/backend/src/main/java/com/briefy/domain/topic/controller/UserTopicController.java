package com.briefy.domain.topic.controller;

import com.briefy.domain.topic.dto.BulkCreateUserTopicRequest;
import com.briefy.domain.topic.dto.BulkCreateUserTopicResponse;
import com.briefy.domain.topic.dto.CreateUserTopicRequest;
import com.briefy.domain.topic.dto.UserTopicResponse;
import com.briefy.domain.topic.service.UserTopicService;
import com.briefy.global.auth.AuthenticatedUser;
import com.briefy.global.auth.CurrentUserProvider;
import com.briefy.global.response.ApiResponse;
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

  @GetMapping
  public ResponseEntity<ApiResponse<List<UserTopicResponse>>> getMyTopics() {
    AuthenticatedUser auth = currentUserProvider.getCurrentUser();
    return ResponseEntity.ok(ApiResponse.success(userTopicService.getMyTopics(auth.userId())));
  }

  @PostMapping
  public ResponseEntity<ApiResponse<UserTopicResponse>> addTopic(
      @RequestBody @Valid CreateUserTopicRequest request) {
    AuthenticatedUser auth = currentUserProvider.getCurrentUser();
    UserTopicResponse response = userTopicService.addTopic(auth.userId(), request);
    return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
  }

  @PostMapping("/bulk")
  public ResponseEntity<ApiResponse<BulkCreateUserTopicResponse>> bulkAddTopics(
      @RequestBody @Valid BulkCreateUserTopicRequest request) {
    AuthenticatedUser auth = currentUserProvider.getCurrentUser();
    BulkCreateUserTopicResponse response = userTopicService.bulkAddTopics(auth.userId(), request);
    return ResponseEntity.ok(ApiResponse.success(response));
  }

  @DeleteMapping("/{userTopicId}")
  public ResponseEntity<ApiResponse<Void>> deleteTopic(@PathVariable Long userTopicId) {
    AuthenticatedUser auth = currentUserProvider.getCurrentUser();
    userTopicService.deleteTopic(auth.userId(), userTopicId);
    return ResponseEntity.ok(ApiResponse.success());
  }
}
