package com.briefy.domain.company.dto.admin;

import com.briefy.domain.company.entity.CompanyAlias;
import java.time.LocalDateTime;

public record CompanyAliasResponse(
    Long id, Long companyId, String alias, String normalizedAlias, LocalDateTime createdAt) {

  public static CompanyAliasResponse from(CompanyAlias alias) {
    return new CompanyAliasResponse(
        alias.getId(),
        alias.getCompany().getId(),
        alias.getAlias(),
        alias.getNormalizedAlias(),
        alias.getCreatedAt());
  }
}
