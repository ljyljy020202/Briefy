package com.briefy.domain.topic.entity;

import com.briefy.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
    name = "user_topics",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uq_user_topic_keyword",
          columnNames = {"user_id", "topic_id", "keyword"})
    },
    indexes = {
      @Index(name = "idx_user_topics_user", columnList = "user_id"),
      @Index(name = "idx_user_topics_topic", columnList = "topic_id")
    })
public class UserTopic extends BaseTimeEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "topic_id", nullable = false)
  private Topic topic;

  @Column(nullable = false, length = 100)
  private String keyword;

  @Column(nullable = false)
  private int priority;

  @Column(name = "is_active", nullable = false)
  private boolean active;

  protected UserTopic() {}

  private UserTopic(Long userId, Topic topic, String keyword, int priority) {
    this.userId = userId;
    this.topic = topic;
    this.keyword = keyword;
    this.priority = priority;
    this.active = true;
  }

  public static UserTopic create(Long userId, Topic topic, String keyword, int priority) {
    return new UserTopic(userId, topic, keyword, priority);
  }

  public void deactivate() {
    this.active = false;
  }

  public void reactivate(int priority) {
    this.active = true;
    this.priority = priority;
  }

  public Long getId() {
    return id;
  }

  public Long getUserId() {
    return userId;
  }

  public Topic getTopic() {
    return topic;
  }

  public String getKeyword() {
    return keyword;
  }

  public int getPriority() {
    return priority;
  }

  public boolean isActive() {
    return active;
  }
}
