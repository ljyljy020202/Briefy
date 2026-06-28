package com.briefy.domain.briefingpreference.entity;

import com.briefy.global.converter.JsonMapConverter;
import com.briefy.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
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
import java.util.HashMap;
import java.util.Map;

@Entity
@Table(
    name = "user_briefing_preferences",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uq_ubp_user_category",
          columnNames = {"user_id", "category_id"})
    },
    indexes = {
      @Index(name = "idx_ubp_user", columnList = "user_id"),
      @Index(name = "idx_ubp_category", columnList = "category_id")
    })
public class UserBriefingPreference extends BaseTimeEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "user_id", nullable = false)
  private Long userId;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "category_id", nullable = false)
  private BriefingCategory category;

  @Convert(converter = JsonMapConverter.class)
  @Column(name = "preference_json", columnDefinition = "TEXT")
  private Map<String, Object> preference;

  @Column(name = "is_active", nullable = false)
  private boolean active;

  protected UserBriefingPreference() {}

  public static UserBriefingPreference create(
      Long userId, BriefingCategory category, Map<String, Object> preference) {
    UserBriefingPreference p = new UserBriefingPreference();
    p.userId = userId;
    p.category = category;
    p.preference = preference != null ? new HashMap<>(preference) : new HashMap<>();
    p.active = true;
    return p;
  }

  public void mergePatch(Map<String, Object> patch) {
    Map<String, Object> merged =
        this.preference != null ? new HashMap<>(this.preference) : new HashMap<>();
    if (patch != null) {
      merged.putAll(patch);
    }
    this.preference = merged;
  }

  public void deactivate() {
    this.active = false;
  }

  public void reactivate(Map<String, Object> newPreference) {
    this.active = true;
    this.preference = newPreference != null ? new HashMap<>(newPreference) : new HashMap<>();
  }

  public Long getId() {
    return id;
  }

  public Long getUserId() {
    return userId;
  }

  public BriefingCategory getCategory() {
    return category;
  }

  public Map<String, Object> getPreference() {
    return preference;
  }

  public boolean isActive() {
    return active;
  }
}
