package com.briefy.domain.briefingpreference.dto;

import com.briefy.domain.briefingpreference.entity.BriefingCategory;

public record BriefingCategoryResponse(
    Long id, String code, String displayName, String phase, boolean active) {

  public static BriefingCategoryResponse from(BriefingCategory category) {
    return new BriefingCategoryResponse(
        category.getId(),
        category.getCode().name(),
        category.getDisplayName(),
        category.getPhase(),
        category.isActive());
  }
}
