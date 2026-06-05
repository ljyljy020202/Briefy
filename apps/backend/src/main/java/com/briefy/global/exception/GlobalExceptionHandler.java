package com.briefy.global.exception;

import com.briefy.global.response.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(BusinessException.class)
  public ResponseEntity<ApiResponse<?>> handleBusinessException(BusinessException e) {
    ErrorCode code = e.getErrorCode();
    log.warn("Business exception: [{}] {}", code, e.getMessage());
    return ResponseEntity.status(code.getStatus()).body(ApiResponse.failure(code, e.getMessage()));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ApiResponse<?>> handleMethodArgumentNotValid(
      MethodArgumentNotValidException e) {
    String message =
        e.getBindingResult().getFieldErrors().stream()
            .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
            .collect(Collectors.joining(", "));
    log.warn("Validation failed: {}", message);
    return ResponseEntity.badRequest()
        .body(ApiResponse.failure(ErrorCode.VALIDATION_ERROR, message));
  }

  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<ApiResponse<?>> handleConstraintViolation(ConstraintViolationException e) {
    String message =
        e.getConstraintViolations().stream()
            .map(cv -> cv.getPropertyPath() + ": " + cv.getMessage())
            .collect(Collectors.joining(", "));
    log.warn("Constraint violation: {}", message);
    return ResponseEntity.badRequest()
        .body(ApiResponse.failure(ErrorCode.VALIDATION_ERROR, message));
  }

  @ExceptionHandler(MissingServletRequestParameterException.class)
  public ResponseEntity<ApiResponse<?>> handleMissingServletRequestParameter(
      MissingServletRequestParameterException e) {
    String message = "Missing required parameter: " + e.getParameterName();
    log.warn("Missing request parameter: {}", e.getParameterName());
    return ResponseEntity.badRequest()
        .body(ApiResponse.failure(ErrorCode.VALIDATION_ERROR, message));
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ResponseEntity<ApiResponse<?>> handleMethodArgumentTypeMismatch(
      MethodArgumentTypeMismatchException e) {
    String message = "Invalid value for parameter '" + e.getName() + "': " + e.getValue();
    log.warn("Type mismatch: {}", message);
    return ResponseEntity.badRequest()
        .body(ApiResponse.failure(ErrorCode.VALIDATION_ERROR, message));
  }

  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ApiResponse<?>> handleAccessDenied(AccessDeniedException e) {
    log.info("Access denied: {}", e.getMessage());
    return ResponseEntity.status(ErrorCode.FORBIDDEN.getStatus())
        .body(ApiResponse.failure(ErrorCode.FORBIDDEN));
  }

  @ExceptionHandler(AuthenticationException.class)
  public ResponseEntity<ApiResponse<?>> handleAuthentication(AuthenticationException e) {
    log.info("Authentication failed: {}", e.getMessage());
    return ResponseEntity.status(ErrorCode.UNAUTHORIZED.getStatus())
        .body(ApiResponse.failure(ErrorCode.UNAUTHORIZED));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiResponse<?>> handleException(Exception e) {
    log.error("Unexpected error", e);
    return ResponseEntity.status(ErrorCode.INTERNAL_SERVER_ERROR.getStatus())
        .body(ApiResponse.failure(ErrorCode.INTERNAL_SERVER_ERROR));
  }
}
