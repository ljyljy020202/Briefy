package com.briefy.domain.preference.entity;

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
    name = "briefing_categories",
    uniqueConstraints = {
      @UniqueConstraint(name = "uq_briefing_categories_code", columnNames = "code")
    },
    indexes = {@Index(name = "idx_briefing_categories_active", columnList = "is_active")})
public class BriefingCategory extends BaseTimeEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 50)
  private BriefingCategoryCode code;

  @Column(name = "display_name", nullable = false, length = 100)
  private String displayName;

  @Column(nullable = false, length = 10)
  private String phase;

  @Column(name = "is_active", nullable = false)
  private boolean active;

  protected BriefingCategory() {}

  public static BriefingCategory create(
      BriefingCategoryCode code, String displayName, String phase, boolean active) {
    BriefingCategory c = new BriefingCategory();
    c.code = code;
    c.displayName = displayName;
    c.phase = phase;
    c.active = active;
    return c;
  }

  public Long getId() {
    return id;
  }

  public BriefingCategoryCode getCode() {
    return code;
  }

  public String getDisplayName() {
    return displayName;
  }

  public String getPhase() {
    return phase;
  }

  public boolean isActive() {
    return active;
  }
}
