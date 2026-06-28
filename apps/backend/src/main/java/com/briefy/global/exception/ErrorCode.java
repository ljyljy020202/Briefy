package com.briefy.global.exception;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

  // Auth
  UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Unauthorized"),
  FORBIDDEN(HttpStatus.FORBIDDEN, "Access denied"),

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
