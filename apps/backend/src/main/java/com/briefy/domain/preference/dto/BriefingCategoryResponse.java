package com.briefy.domain.preference.dto;

import com.briefy.domain.preference.entity.BriefingCategory;

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
