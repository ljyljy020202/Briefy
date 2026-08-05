package com.briefy.domain.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record KakaoUserInfoResponse(
    Long id, @JsonProperty("kakao_account") KakaoAccount kakaoAccount) {

  public record KakaoAccount(
      @JsonProperty("email") String email,
      @JsonProperty("email_needs_agreement") Boolean emailNeedsAgreement,
      @JsonProperty("is_email_valid") Boolean isEmailValid,
      @JsonProperty("is_email_verified") Boolean isEmailVerified,
      @JsonProperty("has_email") Boolean hasEmail,
      @JsonProperty("profile") KakaoProfile profile) {}

  public record KakaoProfile(
      @JsonProperty("nickname") String nickname,
      @JsonProperty("profile_image_url") String profileImageUrl,
      @JsonProperty("is_default_image") Boolean isDefaultImage) {}
}
