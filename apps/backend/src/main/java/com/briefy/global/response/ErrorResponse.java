package com.briefy.global.response;

import com.briefy.global.exception.ErrorCode;

public record ErrorResponse(String code, String message) {

  public static ErrorResponse of(ErrorCode errorCode) {
    return new ErrorResponse(errorCode.name(), errorCode.getDefaultMessage());
  }

  public static ErrorResponse of(ErrorCode errorCode, String message) {
    return new ErrorResponse(errorCode.name(), message);
  }
}
