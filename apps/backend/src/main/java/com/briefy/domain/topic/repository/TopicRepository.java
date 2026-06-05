package com.briefy.domain.topic.repository;

import com.briefy.domain.topic.entity.Topic;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TopicRepository extends JpaRepository<Topic, Long> {

  List<Topic> findAllByActiveTrueOrderByDisplayOrderAsc();

  Optional<Topic> findByIdAndActiveTrue(Long id);
}
