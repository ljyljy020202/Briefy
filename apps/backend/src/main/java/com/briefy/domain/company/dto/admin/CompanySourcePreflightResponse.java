package com.briefy.domain.company.dto.admin;

import java.time.LocalDateTime;
import java.util.List;

public record CompanySourcePreflightResponse(
    Long sourceId,
    String sourceStatus,
    String verificationStatus,
    LocalDateTime lastVerifiedAt,
    Boolean reachable,
    Boolean adapterSupported,
    Boolean parserRegistered,
    Integer discoveredCount,
    Integer sampleParsedCount,
    Boolean requiredFieldsValid,
    List<String> warnings,
    String failureCode,
    String failureMessage,
    String recommendedAction) {}
