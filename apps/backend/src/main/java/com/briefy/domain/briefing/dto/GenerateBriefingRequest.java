package com.briefy.domain.briefing.dto;

import jakarta.validation.constraints.Size;

public record GenerateBriefingRequest(@Size(max = 30) String tone) {

  public String resolvedTone() {
    return (tone != null && !tone.isBlank()) ? tone : "easy";
  }
}
