package com.briefy.domain.company.dto.admin;

import com.briefy.domain.company.entity.Company;
import com.briefy.domain.company.entity.CompanyAlias;
import com.briefy.domain.company.entity.CompanySource;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

public record CompanyAdminResponse(
    Long id,
    String canonicalName,
    String normalizedName,
    String companySize,
    List<String> industryCodes,
    boolean active,
    List<CompanyAliasResponse> aliases,
    List<CompanySourceAdminResponse> sources,
    LocalDateTime createdAt,
    LocalDateTime updatedAt) {

  public static CompanyAdminResponse from(
      Company company,
      List<CompanyAlias> aliases,
      List<CompanySource> sources,
      ObjectMapper objectMapper) {
    return new CompanyAdminResponse(
        company.getId(),
        company.getCanonicalName(),
        company.getNormalizedName(),
        company.getCompanySize(),
        parseIndustryCodes(company.getIndustryCodes()),
        company.isActive(),
        aliases.stream().map(CompanyAliasResponse::from).toList(),
        sources.stream().map(s -> CompanySourceAdminResponse.from(s, objectMapper)).toList(),
        company.getCreatedAt(),
        company.getUpdatedAt());
  }

  private static List<String> parseIndustryCodes(String raw) {
    if (raw == null || raw.isBlank()) {
      return List.of();
    }
    return Arrays.stream(raw.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
  }
}
