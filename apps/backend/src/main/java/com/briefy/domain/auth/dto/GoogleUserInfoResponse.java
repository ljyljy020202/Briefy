package com.briefy.domain.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record GoogleUserInfoResponse(
    @JsonProperty("sub") String sub,
    @JsonProperty("email") String email,
    @JsonProperty("name") String name,
    @JsonProperty("picture") String picture) {}
