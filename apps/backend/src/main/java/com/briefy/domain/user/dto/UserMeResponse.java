package com.briefy.domain.user.dto;

import com.briefy.domain.user.entity.User;

public record UserMeResponse(
    Long id,
    String email,
    String nickname,
    String profileImageUrl,
    String role,
    boolean onboardingCompleted) {

  public static UserMeResponse from(User user) {
    return new UserMeResponse(
        user.getId(),
        user.getEmail(),
        user.getNickname(),
        user.getProfileImageUrl(),
        user.getRole().name(),
        user.isOnboardingCompleted());
  }
}
