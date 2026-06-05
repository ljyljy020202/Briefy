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

  // Topic / UserTopic
  TOPIC_NOT_FOUND(HttpStatus.NOT_FOUND, "Topic not found"),
  USER_TOPIC_NOT_FOUND(HttpStatus.NOT_FOUND, "Subscription not found"),
  DUPLICATE_USER_TOPIC(HttpStatus.CONFLICT, "Subscription already exists"),

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
