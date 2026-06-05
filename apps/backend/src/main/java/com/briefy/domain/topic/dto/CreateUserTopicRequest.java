package com.briefy.domain.topic.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateUserTopicRequest(
    @NotNull Long topicId, @NotBlank @Size(max = 100) String keyword, Integer priority) {}
