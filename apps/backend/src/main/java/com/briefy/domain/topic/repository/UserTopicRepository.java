package com.briefy.domain.topic.repository;

import com.briefy.domain.topic.entity.UserTopic;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserTopicRepository extends JpaRepository<UserTopic, Long> {

  List<UserTopic> findAllByUserIdAndActiveTrue(Long userId);

  Optional<UserTopic> findByUserIdAndTopicIdAndKeyword(Long userId, Long topicId, String keyword);
}
