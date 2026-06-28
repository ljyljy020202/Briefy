package com.briefy.domain.briefingpreference.dto;

import com.briefy.domain.briefingpreference.entity.UserBriefingPreference;
import java.util.Map;

public record BriefingPreferenceResponse(
    Long id,
    String categoryCode,
    String categoryDisplayName,
    Map<String, Object> preference,
    boolean active,
    String createdAt,
    String updatedAt) {

  public static BriefingPreferenceResponse from(UserBriefingPreference pref) {
    return new BriefingPreferenceResponse(
        pref.getId(),
        pref.getCategory().getCode().name(),
        pref.getCategory().getDisplayName(),
        pref.getPreference(),
        pref.isActive(),
        pref.getCreatedAt() != null ? pref.getCreatedAt().toString() : null,
        pref.getUpdatedAt() != null ? pref.getUpdatedAt().toString() : null);
  }
}
