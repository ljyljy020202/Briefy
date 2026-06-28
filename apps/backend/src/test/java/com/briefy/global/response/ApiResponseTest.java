package com.briefy.global.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.briefy.global.exception.ErrorCode;
import org.junit.jupiter.api.Test;

class ApiResponseTest {

  @Test
  void success_withData_setsSuccessTrueAndData() {
    ApiResponse<String> response = ApiResponse.success("hello");

    assertThat(response.isSuccess()).isTrue();
    assertThat(response.getData()).isEqualTo("hello");
    assertThat(response.getError()).isNull();
  }

  @Test
  void success_withoutData_setsSuccessTrueAndNullData() {
    ApiResponse<Void> response = ApiResponse.success();

    assertThat(response.isSuccess()).isTrue();
    assertThat(response.getData()).isNull();
    assertThat(response.getError()).isNull();
  }

  @Test
  void failure_withErrorCode_setsSuccessFalseAndDefaultMessage() {
    ApiResponse<?> response = ApiResponse.failure(ErrorCode.USER_NOT_FOUND);

    assertThat(response.isSuccess()).isFalse();
    assertThat(response.getData()).isNull();
    assertThat(response.getError()).isNotNull();
    assertThat(response.getError().code()).isEqualTo("USER_NOT_FOUND");
    assertThat(response.getError().message())
        .isEqualTo(ErrorCode.USER_NOT_FOUND.getDefaultMessage());
  }

  @Test
  void failure_withCustomMessage_overridesDefaultMessage() {
    ApiResponse<?> response =
        ApiResponse.failure(ErrorCode.VALIDATION_ERROR, "name: must not be blank");

    assertThat(response.isSuccess()).isFalse();
    assertThat(response.getError().code()).isEqualTo("VALIDATION_ERROR");
    assertThat(response.getError().message()).isEqualTo("name: must not be blank");
  }

  @Test
  void errorCode_httpStatus_matchesApiDocSpec() {
    assertThat(ErrorCode.UNAUTHORIZED.getStatus().value()).isEqualTo(401);
    assertThat(ErrorCode.FORBIDDEN.getStatus().value()).isEqualTo(403);
    assertThat(ErrorCode.VALIDATION_ERROR.getStatus().value()).isEqualTo(400);
    assertThat(ErrorCode.USER_NOT_FOUND.getStatus().value()).isEqualTo(404);
    assertThat(ErrorCode.BRIEFING_CATEGORY_NOT_FOUND.getStatus().value()).isEqualTo(404);
    assertThat(ErrorCode.BRIEFING_PREFERENCE_NOT_FOUND.getStatus().value()).isEqualTo(404);
    assertThat(ErrorCode.DUPLICATE_BRIEFING_PREFERENCE.getStatus().value()).isEqualTo(409);
    assertThat(ErrorCode.BRIEFING_REPORT_NOT_FOUND.getStatus().value()).isEqualTo(404);
    assertThat(ErrorCode.BRIEFING_JOB_FAILED.getStatus().value()).isEqualTo(500);
    assertThat(ErrorCode.AGENT_SERVER_ERROR.getStatus().value()).isEqualTo(502);
    assertThat(ErrorCode.DELIVERY_FAILED.getStatus().value()).isEqualTo(500);
    assertThat(ErrorCode.INTERNAL_SERVER_ERROR.getStatus().value()).isEqualTo(500);
  }

  @Test
  void errorResponse_of_usesEnumNameAsCode() {
    ErrorResponse er = ErrorResponse.of(ErrorCode.AGENT_SERVER_ERROR);

    assertThat(er.code()).isEqualTo("AGENT_SERVER_ERROR");
    assertThat(er.message()).isEqualTo(ErrorCode.AGENT_SERVER_ERROR.getDefaultMessage());
  }

  @Test
  void errorResponse_ofWithMessage_overridesMessage() {
    ErrorResponse er = ErrorResponse.of(ErrorCode.BRIEFING_JOB_FAILED, "timeout after 30s");

    assertThat(er.code()).isEqualTo("BRIEFING_JOB_FAILED");
    assertThat(er.message()).isEqualTo("timeout after 30s");
  }
}
