package com.briefy.domain.topic.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record BulkCreateUserTopicRequest(
    @NotNull @Size(min = 1, max = 20) List<@Valid TopicEntry> topics) {

  public record TopicEntry(
      @NotNull Long topicId,
      @NotNull @Size(min = 1, max = 10) List<@NotBlank @Size(max = 100) String> keywords) {}
}
