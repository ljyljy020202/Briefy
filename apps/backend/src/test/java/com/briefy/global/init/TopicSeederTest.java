package com.briefy.global.init;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.briefy.domain.topic.entity.Topic;
import com.briefy.domain.topic.repository.TopicRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TopicSeederTest {

  @Mock private TopicRepository topicRepository;

  @InjectMocks private TopicSeeder topicSeeder;

  @Test
  void seed_insertsAllSixTopics_whenDatabaseIsEmpty() {
    when(topicRepository.findBySlug(anyString())).thenReturn(Optional.empty());
    when(topicRepository.save(any(Topic.class))).thenAnswer(inv -> inv.getArgument(0));

    topicSeeder.seed();

    verify(topicRepository, times(6)).save(any(Topic.class));
  }

  @Test
  void seed_skipsExistingSlug_insertsOnlyMissingTopics() {
    when(topicRepository.findBySlug(anyString())).thenReturn(Optional.empty());
    when(topicRepository.findBySlug("target-role")).thenReturn(Optional.of(mock(Topic.class)));
    when(topicRepository.save(any(Topic.class))).thenAnswer(inv -> inv.getArgument(0));

    topicSeeder.seed();

    verify(topicRepository, times(5)).save(any(Topic.class));
  }

  @Test
  void seed_isIdempotent_whenAllTopicsAlreadyExist() {
    when(topicRepository.findBySlug(anyString())).thenReturn(Optional.of(mock(Topic.class)));

    topicSeeder.seed();

    verify(topicRepository, never()).save(any());
  }

  @Test
  void seed_insertsCorrectSlugAndCategory() {
    when(topicRepository.findBySlug(anyString())).thenReturn(Optional.empty());
    ArgumentCaptor<Topic> captor = ArgumentCaptor.forClass(Topic.class);
    when(topicRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

    topicSeeder.seed();

    Topic first = captor.getAllValues().get(0);
    assertThat(first.getSlug()).isEqualTo("target-role");
    assertThat(first.getName()).isEqualTo("Target Role");
    assertThat(first.getDisplayOrder()).isEqualTo(1);
    assertThat(first.isActive()).isTrue();
  }
}
