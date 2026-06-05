package com.briefy.domain.topic.service;

import com.briefy.domain.topic.dto.TopicResponse;
import com.briefy.domain.topic.repository.TopicRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class TopicService {

  private final TopicRepository topicRepository;

  public TopicService(TopicRepository topicRepository) {
    this.topicRepository = topicRepository;
  }

  public List<TopicResponse> getActiveTopics() {
    return topicRepository.findAllByActiveTrueOrderByDisplayOrderAsc().stream()
        .map(TopicResponse::from)
        .toList();
  }
}
