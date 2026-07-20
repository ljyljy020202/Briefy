package com.briefy.domain.company.dto.admin;

import com.briefy.domain.company.entity.CompanySource;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.Map;

public record CompanySourceAdminResponse(
    Long id,
    Long companyId,
    String companyName,
    String sourceType,
    String sourceUrl,
    String adapterType,
    String status,
    Map<String, Object> config,
    LocalDateTime lastVerifiedAt,
    LocalDateTime lastCollectedAt,
    LocalDateTime createdAt,
    LocalDateTime updatedAt) {

  public static CompanySourceAdminResponse from(CompanySource source, ObjectMapper objectMapper) {
    return new CompanySourceAdminResponse(
        source.getId(),
        source.getCompany().getId(),
        source.getCompany().getCanonicalName(),
        source.getSourceType(),
        source.getSourceUrl(),
        source.getAdapterType(),
        source.getStatus(),
        parseConfig(source.getConfigJson(), objectMapper),
        source.getLastVerifiedAt(),
        source.getLastCollectedAt(),
        source.getCreatedAt(),
        source.getUpdatedAt());
  }

  private static Map<String, Object> parseConfig(String json, ObjectMapper objectMapper) {
    if (json == null || json.isBlank()) {
      return null;
    }
    try {
      return objectMapper.readValue(json, new TypeReference<>() {});
    } catch (JsonProcessingException e) {
      return null;
    }
  }
}
