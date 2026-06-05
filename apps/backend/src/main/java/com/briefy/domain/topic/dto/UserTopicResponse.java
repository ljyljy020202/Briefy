package com.briefy.domain.topic.dto;

import com.briefy.domain.topic.entity.UserTopic;
import com.fasterxml.jackson.annotation.JsonProperty;

public record UserTopicResponse(
    Long id,
    Long topicId,
    String topicName,
    String keyword,
    int priority,
    @JsonProperty("isActive") boolean active) {

  public static UserTopicResponse from(UserTopic ut) {
    return new UserTopicResponse(
        ut.getId(),
        ut.getTopic().getId(),
        ut.getTopic().getName(),
        ut.getKeyword(),
        ut.getPriority(),
        ut.isActive());
  }
}
