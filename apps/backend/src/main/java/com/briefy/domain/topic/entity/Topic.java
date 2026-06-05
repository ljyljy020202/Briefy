package com.briefy.domain.topic.entity;

import com.briefy.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
    name = "topics",
    uniqueConstraints = {@UniqueConstraint(name = "uq_topics_slug", columnNames = "slug")},
    indexes = {
      @Index(name = "idx_topics_category", columnList = "category"),
      @Index(name = "idx_topics_active", columnList = "is_active")
    })
public class Topic extends BaseTimeEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, length = 100)
  private String name;

  @Column(nullable = false, unique = true, length = 100)
  private String slug;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 50)
  private TopicCategory category;

  @Column(length = 500)
  private String description;

  @Column(name = "display_order")
  private Integer displayOrder;

  @Column(name = "is_active", nullable = false)
  private boolean active;

  protected Topic() {}

  public Topic(
      String name, String slug, TopicCategory category, String description, int displayOrder) {
    this.name = name;
    this.slug = slug;
    this.category = category;
    this.description = description;
    this.displayOrder = displayOrder;
    this.active = true;
  }

  public Long getId() {
    return id;
  }

  public String getName() {
    return name;
  }

  public String getSlug() {
    return slug;
  }

  public TopicCategory getCategory() {
    return category;
  }

  public String getDescription() {
    return description;
  }

  public Integer getDisplayOrder() {
    return displayOrder;
  }

  public boolean isActive() {
    return active;
  }
}
