package com.briefy.domain.topic.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.briefy.domain.topic.dto.BulkCreateUserTopicRequest;
import com.briefy.domain.topic.dto.BulkCreateUserTopicResponse;
import com.briefy.domain.topic.dto.CreateUserTopicRequest;
import com.briefy.domain.topic.dto.UserTopicResponse;
import com.briefy.domain.topic.entity.Topic;
import com.briefy.domain.topic.entity.TopicCategory;
import com.briefy.domain.topic.entity.UserTopic;
import com.briefy.domain.topic.repository.TopicRepository;
import com.briefy.domain.topic.repository.UserTopicRepository;
import com.briefy.global.exception.BusinessException;
import com.briefy.global.exception.ErrorCode;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserTopicServiceTest {

  @Mock private UserTopicRepository userTopicRepository;
  @Mock private TopicRepository topicRepository;

  @InjectMocks private UserTopicService userTopicService;

  private Topic mockTopic;

  @BeforeEach
  void setUp() {
    mockTopic = mock(Topic.class);
    when(mockTopic.getId()).thenReturn(1L);
    when(mockTopic.getName()).thenReturn("Target Role");
    when(mockTopic.getCategory()).thenReturn(TopicCategory.JOB_PREFERENCE);
    when(mockTopic.isActive()).thenReturn(true);
  }

  // ── getMyTopics ────────────────────────────────────────────────────────────

  @Test
  void getMyTopics_returnsActiveSubscriptionsOnly() {
    UserTopic ut = UserTopic.create(1L, mockTopic, "OpenAI", 1);
    when(userTopicRepository.findAllByUserIdAndActiveTrue(1L)).thenReturn(List.of(ut));

    List<UserTopicResponse> result = userTopicService.getMyTopics(1L);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).keyword()).isEqualTo("OpenAI");
    assertThat(result.get(0).topicId()).isEqualTo(1L);
  }

  // ── addTopic ───────────────────────────────────────────────────────────────

  @Test
  void addTopic_success_createsNewSubscription() {
    when(topicRepository.findByIdAndActiveTrue(1L)).thenReturn(Optional.of(mockTopic));
    when(userTopicRepository.findByUserIdAndTopicIdAndKeyword(1L, 1L, "OpenAI"))
        .thenReturn(Optional.empty());

    UserTopic saved = UserTopic.create(1L, mockTopic, "OpenAI", 1);
    when(userTopicRepository.save(any(UserTopic.class))).thenReturn(saved);

    UserTopicResponse response =
        userTopicService.addTopic(1L, new CreateUserTopicRequest(1L, "OpenAI", 1));

    assertThat(response.keyword()).isEqualTo("OpenAI");
    assertThat(response.topicId()).isEqualTo(1L);
    verify(userTopicRepository).save(any(UserTopic.class));
  }

  @Test
  void addTopic_trims_keyword_beforeDuplicateCheck() {
    when(topicRepository.findByIdAndActiveTrue(1L)).thenReturn(Optional.of(mockTopic));
    when(userTopicRepository.findByUserIdAndTopicIdAndKeyword(1L, 1L, "OpenAI"))
        .thenReturn(Optional.empty());

    UserTopic saved = UserTopic.create(1L, mockTopic, "OpenAI", 1);
    when(userTopicRepository.save(any(UserTopic.class))).thenReturn(saved);

    userTopicService.addTopic(1L, new CreateUserTopicRequest(1L, "  OpenAI  ", 1));

    verify(userTopicRepository).findByUserIdAndTopicIdAndKeyword(1L, 1L, "OpenAI");
  }

  @Test
  void addTopic_throwsDuplicateUserTopic_whenActiveSubscriptionExists() {
    when(topicRepository.findByIdAndActiveTrue(1L)).thenReturn(Optional.of(mockTopic));
    UserTopic existing = UserTopic.create(1L, mockTopic, "OpenAI", 1);
    when(userTopicRepository.findByUserIdAndTopicIdAndKeyword(1L, 1L, "OpenAI"))
        .thenReturn(Optional.of(existing));

    assertThatThrownBy(
            () -> userTopicService.addTopic(1L, new CreateUserTopicRequest(1L, "OpenAI", 1)))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            ex ->
                assertThat(((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.DUPLICATE_USER_TOPIC));

    verify(userTopicRepository, never()).save(any());
  }

  @Test
  void addTopic_reactivatesInactiveSubscription_insteadOfCreatingNew() {
    when(topicRepository.findByIdAndActiveTrue(1L)).thenReturn(Optional.of(mockTopic));
    UserTopic inactive = UserTopic.create(1L, mockTopic, "OpenAI", 1);
    inactive.deactivate();
    assertThat(inactive.isActive()).isFalse();

    when(userTopicRepository.findByUserIdAndTopicIdAndKeyword(1L, 1L, "OpenAI"))
        .thenReturn(Optional.of(inactive));

    UserTopicResponse response =
        userTopicService.addTopic(1L, new CreateUserTopicRequest(1L, "OpenAI", 2));

    assertThat(response.keyword()).isEqualTo("OpenAI");
    assertThat(inactive.isActive()).isTrue();
    assertThat(inactive.getPriority()).isEqualTo(2);
    verify(userTopicRepository, never()).save(any());
  }

  @Test
  void addTopic_throwsTopicNotFound_whenTopicInactiveOrMissing() {
    when(topicRepository.findByIdAndActiveTrue(99L)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () -> userTopicService.addTopic(1L, new CreateUserTopicRequest(99L, "keyword", 1)))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            ex ->
                assertThat(((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.TOPIC_NOT_FOUND));
  }

  // ── deleteTopic ────────────────────────────────────────────────────────────

  @Test
  void deleteTopic_success_setsInactive() {
    UserTopic ut = UserTopic.create(1L, mockTopic, "OpenAI", 1);
    when(userTopicRepository.findById(10L)).thenReturn(Optional.of(ut));

    userTopicService.deleteTopic(1L, 10L);

    assertThat(ut.isActive()).isFalse();
  }

  @Test
  void deleteTopic_throwsForbidden_whenNotOwner() {
    UserTopic ut = UserTopic.create(2L, mockTopic, "OpenAI", 1);
    when(userTopicRepository.findById(10L)).thenReturn(Optional.of(ut));

    assertThatThrownBy(() -> userTopicService.deleteTopic(1L, 10L))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            ex ->
                assertThat(((BusinessException) ex).getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
  }

  @Test
  void deleteTopic_throwsUserTopicNotFound_whenMissing() {
    when(userTopicRepository.findById(99L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> userTopicService.deleteTopic(1L, 99L))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            ex ->
                assertThat(((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.USER_TOPIC_NOT_FOUND));
  }

  // ── bulkAddTopics ──────────────────────────────────────────────────────────

  @Test
  void bulkAddTopics_returnsCreatedCountForNewAndReactivated() {
    when(topicRepository.findByIdAndActiveTrue(1L)).thenReturn(Optional.of(mockTopic));
    when(userTopicRepository.findByUserIdAndTopicIdAndKeyword(1L, 1L, "OpenAI"))
        .thenReturn(Optional.empty());
    when(userTopicRepository.findByUserIdAndTopicIdAndKeyword(1L, 1L, "Claude"))
        .thenReturn(Optional.empty());
    when(userTopicRepository.save(any(UserTopic.class))).thenAnswer(inv -> inv.getArgument(0));

    BulkCreateUserTopicRequest request =
        new BulkCreateUserTopicRequest(
            List.of(new BulkCreateUserTopicRequest.TopicEntry(1L, List.of("OpenAI", "Claude"))));

    BulkCreateUserTopicResponse response = userTopicService.bulkAddTopics(1L, request);

    assertThat(response.createdCount()).isEqualTo(2);
  }

  @Test
  void bulkAddTopics_skipsActiveDuplicates_doesNotCountThem() {
    when(topicRepository.findByIdAndActiveTrue(1L)).thenReturn(Optional.of(mockTopic));

    UserTopic existing = UserTopic.create(1L, mockTopic, "OpenAI", 1);
    when(userTopicRepository.findByUserIdAndTopicIdAndKeyword(1L, 1L, "OpenAI"))
        .thenReturn(Optional.of(existing));
    when(userTopicRepository.findByUserIdAndTopicIdAndKeyword(1L, 1L, "Claude"))
        .thenReturn(Optional.empty());
    when(userTopicRepository.save(any(UserTopic.class))).thenAnswer(inv -> inv.getArgument(0));

    BulkCreateUserTopicRequest request =
        new BulkCreateUserTopicRequest(
            List.of(new BulkCreateUserTopicRequest.TopicEntry(1L, List.of("OpenAI", "Claude"))));

    BulkCreateUserTopicResponse response = userTopicService.bulkAddTopics(1L, request);

    assertThat(response.createdCount()).isEqualTo(1);
    verify(userTopicRepository, never())
        .save(org.mockito.ArgumentMatchers.argThat(ut -> ut.getKeyword().equals("OpenAI")));
  }

  @Test
  void bulkAddTopics_throwsTopicNotFound_whenAnyTopicInvalid() {
    when(topicRepository.findByIdAndActiveTrue(99L)).thenReturn(Optional.empty());

    BulkCreateUserTopicRequest request =
        new BulkCreateUserTopicRequest(
            List.of(new BulkCreateUserTopicRequest.TopicEntry(99L, List.of("keyword"))));

    assertThatThrownBy(() -> userTopicService.bulkAddTopics(1L, request))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            ex ->
                assertThat(((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.TOPIC_NOT_FOUND));
  }
}
