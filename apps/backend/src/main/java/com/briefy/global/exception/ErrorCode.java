package com.briefy.global.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

  // Auth
  UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Unauthorized"),
  FORBIDDEN(HttpStatus.FORBIDDEN, "Access denied"),
  KAKAO_EMAIL_REQUIRED(HttpStatus.BAD_REQUEST, "Kakao account email is required"),
  KAKAO_EMAIL_INVALID(HttpStatus.BAD_REQUEST, "Kakao account email is not valid or verified"),

  // Validation
  VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Validation failed"),

  // User
  USER_NOT_FOUND(HttpStatus.NOT_FOUND, "User not found"),

  // BriefingCategory / UserBriefingPreference
  BRIEFING_CATEGORY_NOT_FOUND(HttpStatus.NOT_FOUND, "Briefing category not found"),
  BRIEFING_PREFERENCE_NOT_FOUND(HttpStatus.NOT_FOUND, "Briefing preference not found"),
  DUPLICATE_BRIEFING_PREFERENCE(HttpStatus.CONFLICT, "Briefing preference already exists"),

  // Briefing
  BRIEFING_REPORT_NOT_FOUND(HttpStatus.NOT_FOUND, "Briefing report not found"),
  BRIEFING_JOB_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "Briefing generation failed"),

  // Collection
  COLLECTION_JOB_NOT_FOUND(HttpStatus.NOT_FOUND, "Collection job not found"),
  COLLECTION_JOB_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "Daily collection failed"),

  // Company
  COMPANY_NOT_FOUND(HttpStatus.NOT_FOUND, "Company not found"),
  COMPANY_ALIAS_NOT_FOUND(HttpStatus.NOT_FOUND, "Company alias not found"),
  COMPANY_SOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "Company source not found"),
  DUPLICATE_COMPANY_NORMALIZED_NAME(
      HttpStatus.CONFLICT, "Company with this normalized name already exists"),
  DUPLICATE_COMPANY_ALIAS(HttpStatus.CONFLICT, "Company alias already exists"),
  DUPLICATE_COMPANY_SOURCE(HttpStatus.CONFLICT, "Company source already exists"),
  INVALID_ADAPTER_TYPE(HttpStatus.BAD_REQUEST, "Invalid adapter type"),
  INVALID_SOURCE_STATUS(HttpStatus.BAD_REQUEST, "Invalid or disallowed source status"),
  INVALID_SOURCE_URL(HttpStatus.BAD_REQUEST, "Invalid source URL"),
  INVALID_CONFIG(HttpStatus.BAD_REQUEST, "Invalid config"),
  SOURCE_NOT_VERIFIED(HttpStatus.CONFLICT, "Source must be VERIFIED before activation"),
  SOURCE_ALREADY_ACTIVE(HttpStatus.CONFLICT, "Source is already ACTIVE"),
  COMPANY_INACTIVE(HttpStatus.UNPROCESSABLE_ENTITY, "Company is not active"),

  // External
  AGENT_SERVER_ERROR(HttpStatus.BAD_GATEWAY, "Agent server error"),
  DELIVERY_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "Email delivery failed"),

  // Fallback
  INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");

  private final HttpStatus status;
  private final String defaultMessage;

  ErrorCode(HttpStatus status, String defaultMessage) {
    this.status = status;
    this.defaultMessage = defaultMessage;
  }

  public HttpStatus getStatus() {
    return status;
  }

  public String getDefaultMessage() {
    return defaultMessage;
  }
}
