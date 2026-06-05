package com.briefy.domain.topic.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.briefy.domain.topic.dto.TopicResponse;
import com.briefy.domain.topic.entity.Topic;
import com.briefy.domain.topic.entity.TopicCategory;
import com.briefy.domain.topic.repository.TopicRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TopicServiceTest {

  @Mock private TopicRepository topicRepository;

  @InjectMocks private TopicService topicService;

  @Test
  void getActiveTopics_returnsOnlyActiveTopicsOrderedByDisplayOrder() {
    Topic t1 = mock(Topic.class);
    when(t1.getId()).thenReturn(1L);
    when(t1.getName()).thenReturn("AI/LLM");
    when(t1.getSlug()).thenReturn("ai-llm");
    when(t1.getCategory()).thenReturn(TopicCategory.TECH);
    when(t1.getDescription()).thenReturn("AI news");
    when(t1.getDisplayOrder()).thenReturn(1);

    Topic t2 = mock(Topic.class);
    when(t2.getId()).thenReturn(2L);
    when(t2.getName()).thenReturn("Backend/Spring");
    when(t2.getSlug()).thenReturn("backend-spring");
    when(t2.getCategory()).thenReturn(TopicCategory.TECH);
    when(t2.getDescription()).thenReturn("Spring Boot news");
    when(t2.getDisplayOrder()).thenReturn(2);

    when(topicRepository.findAllByActiveTrueOrderByDisplayOrderAsc()).thenReturn(List.of(t1, t2));

    List<TopicResponse> result = topicService.getActiveTopics();

    assertThat(result).hasSize(2);
    assertThat(result.get(0).name()).isEqualTo("AI/LLM");
    assertThat(result.get(0).category()).isEqualTo("TECH");
    assertThat(result.get(1).name()).isEqualTo("Backend/Spring");
  }

  @Test
  void getActiveTopics_returnsEmptyList_whenNoActiveTopics() {
    when(topicRepository.findAllByActiveTrueOrderByDisplayOrderAsc()).thenReturn(List.of());

    List<TopicResponse> result = topicService.getActiveTopics();

    assertThat(result).isEmpty();
  }
}
