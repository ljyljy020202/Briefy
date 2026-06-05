package com.briefy.domain.topic.service;

import com.briefy.domain.topic.dto.BulkCreateUserTopicRequest;
import com.briefy.domain.topic.dto.BulkCreateUserTopicResponse;
import com.briefy.domain.topic.dto.CreateUserTopicRequest;
import com.briefy.domain.topic.dto.UserTopicResponse;
import com.briefy.domain.topic.entity.Topic;
import com.briefy.domain.topic.entity.UserTopic;
import com.briefy.domain.topic.repository.TopicRepository;
import com.briefy.domain.topic.repository.UserTopicRepository;
import com.briefy.global.exception.BusinessException;
import com.briefy.global.exception.ErrorCode;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class UserTopicService {

  private final UserTopicRepository userTopicRepository;
  private final TopicRepository topicRepository;

  public UserTopicService(
      UserTopicRepository userTopicRepository, TopicRepository topicRepository) {
    this.userTopicRepository = userTopicRepository;
    this.topicRepository = topicRepository;
  }

  public List<UserTopicResponse> getMyTopics(Long userId) {
    return userTopicRepository.findAllByUserIdAndActiveTrue(userId).stream()
        .map(UserTopicResponse::from)
        .toList();
  }

  @Transactional
  public UserTopicResponse addTopic(Long userId, CreateUserTopicRequest request) {
    String keyword = request.keyword().trim();
    int priority = request.priority() != null ? request.priority() : 1;

    Topic topic =
        topicRepository
            .findByIdAndActiveTrue(request.topicId())
            .orElseThrow(() -> new BusinessException(ErrorCode.TOPIC_NOT_FOUND));

    return userTopicRepository
        .findByUserIdAndTopicIdAndKeyword(userId, topic.getId(), keyword)
        .map(
            existing -> {
              if (existing.isActive()) {
                throw new BusinessException(ErrorCode.DUPLICATE_USER_TOPIC);
              }
              existing.reactivate(priority);
              return UserTopicResponse.from(existing);
            })
        .orElseGet(
            () -> {
              UserTopic created =
                  userTopicRepository.save(UserTopic.create(userId, topic, keyword, priority));
              return UserTopicResponse.from(created);
            });
  }

  @Transactional
  public BulkCreateUserTopicResponse bulkAddTopics(
      Long userId, BulkCreateUserTopicRequest request) {
    int createdCount = 0;

    for (BulkCreateUserTopicRequest.TopicEntry entry : request.topics()) {
      Topic topic =
          topicRepository
              .findByIdAndActiveTrue(entry.topicId())
              .orElseThrow(() -> new BusinessException(ErrorCode.TOPIC_NOT_FOUND));

      for (String rawKeyword : entry.keywords()) {
        String keyword = rawKeyword.trim();
        createdCount += upsertSubscription(userId, topic, keyword);
      }
    }

    return new BulkCreateUserTopicResponse(createdCount);
  }

  @Transactional
  public void deleteTopic(Long userId, Long userTopicId) {
    UserTopic userTopic =
        userTopicRepository
            .findById(userTopicId)
            .orElseThrow(() -> new BusinessException(ErrorCode.USER_TOPIC_NOT_FOUND));

    if (!userTopic.getUserId().equals(userId)) {
      throw new BusinessException(ErrorCode.FORBIDDEN);
    }

    userTopic.deactivate();
  }

  private int upsertSubscription(Long userId, Topic topic, String keyword) {
    return userTopicRepository
        .findByUserIdAndTopicIdAndKeyword(userId, topic.getId(), keyword)
        .map(
            existing -> {
              if (existing.isActive()) {
                return 0;
              }
              existing.reactivate(1);
              return 1;
            })
        .orElseGet(
            () -> {
              userTopicRepository.save(UserTopic.create(userId, topic, keyword, 1));
              return 1;
            });
  }
}
