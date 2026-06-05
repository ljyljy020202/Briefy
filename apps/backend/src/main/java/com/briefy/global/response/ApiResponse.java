package com.briefy.global.response;

import com.briefy.global.exception.ErrorCode;

public class ApiResponse<T> {

  private final boolean success;
  private final T data;
  private final ErrorResponse error;

  private ApiResponse(boolean success, T data, ErrorResponse error) {
    this.success = success;
    this.data = data;
    this.error = error;
  }

  public static <T> ApiResponse<T> success(T data) {
    return new ApiResponse<>(true, data, null);
  }

  public static ApiResponse<Void> success() {
    return new ApiResponse<>(true, null, null);
  }

  public static <T> ApiResponse<T> failure(ErrorCode errorCode) {
    return new ApiResponse<>(false, null, ErrorResponse.of(errorCode));
  }

  public static <T> ApiResponse<T> failure(ErrorCode errorCode, String message) {
    return new ApiResponse<>(false, null, ErrorResponse.of(errorCode, message));
  }

  public boolean isSuccess() {
    return success;
  }

  public T getData() {
    return data;
  }

  public ErrorResponse getError() {
    return error;
  }
}
