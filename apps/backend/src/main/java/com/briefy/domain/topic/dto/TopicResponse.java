package com.briefy.domain.topic.dto;

import com.briefy.domain.topic.entity.Topic;

public record TopicResponse(
    Long id, String name, String slug, String category, String description, Integer displayOrder) {

  public static TopicResponse from(Topic topic) {
    return new TopicResponse(
        topic.getId(),
        topic.getName(),
        topic.getSlug(),
        topic.getCategory().name(),
        topic.getDescription(),
        topic.getDisplayOrder());
  }
}
