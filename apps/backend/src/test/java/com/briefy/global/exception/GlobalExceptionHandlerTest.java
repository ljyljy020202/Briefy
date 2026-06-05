package com.briefy.global.exception;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.briefy.global.response.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

class GlobalExceptionHandlerTest {

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();

    mockMvc =
        MockMvcBuilders.standaloneSetup(new FakeController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .setValidator(validator)
            .build();
  }

  // ── Minimal controller that triggers each exception type ──────────────────

  @RestController
  static class FakeController {

    @GetMapping("/test/business")
    public ApiResponse<Void> businessException() {
      throw new BusinessException(ErrorCode.USER_NOT_FOUND);
    }

    @GetMapping("/test/business-custom-message")
    public ApiResponse<Void> businessExceptionCustomMessage() {
      throw new BusinessException(ErrorCode.DUPLICATE_USER_TOPIC, "Already subscribed to OpenAI");
    }

    @PostMapping("/test/validation")
    public ApiResponse<Void> validation(@RequestBody @Valid Request req) {
      return ApiResponse.success();
    }

    @GetMapping("/test/missing-param")
    public ApiResponse<Void> missingParam(@RequestParam String required) {
      return ApiResponse.success();
    }

    @GetMapping("/test/type-mismatch/{id}")
    public ApiResponse<Void> typeMismatch(@PathVariable Long id) {
      return ApiResponse.success();
    }

    @GetMapping("/test/access-denied")
    public ApiResponse<Void> accessDenied() {
      throw new AccessDeniedException("not allowed");
    }

    @GetMapping("/test/internal")
    public ApiResponse<Void> internalError() {
      throw new RuntimeException("unexpected database failure");
    }
  }

  record Request(@NotBlank String name) {}

  // ── Tests ─────────────────────────────────────────────────────────────────

  @Test
  void businessException_returnsMatchingHttpStatusAndErrorCode() throws Exception {
    mockMvc
        .perform(get("/test/business"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.data", nullValue()))
        .andExpect(jsonPath("$.error.code").value("USER_NOT_FOUND"))
        .andExpect(jsonPath("$.error.message").value(ErrorCode.USER_NOT_FOUND.getDefaultMessage()));
  }

  @Test
  void businessException_withCustomMessage_usesCustomMessage() throws Exception {
    mockMvc
        .perform(get("/test/business-custom-message"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("DUPLICATE_USER_TOPIC"))
        .andExpect(jsonPath("$.error.message").value("Already subscribed to OpenAI"));
  }

  @Test
  void validationError_returns400WithFieldDetails() throws Exception {
    mockMvc
        .perform(
            post("/test/validation")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.error.message").value(containsString("name")));
  }

  @Test
  void missingRequestParam_returns400() throws Exception {
    mockMvc
        .perform(get("/test/missing-param"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.error.message").value(containsString("Missing required parameter")));
  }

  @Test
  void typeMismatch_returns400() throws Exception {
    mockMvc
        .perform(get("/test/type-mismatch/not-a-number"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
  }

  @Test
  void accessDeniedException_returns403() throws Exception {
    mockMvc
        .perform(get("/test/access-denied"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
  }

  @Test
  void unhandledException_returns500WithoutStackTrace() throws Exception {
    mockMvc
        .perform(get("/test/internal"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.error.code").value("INTERNAL_SERVER_ERROR"))
        .andExpect(jsonPath("$.error.message").value("Internal server error"));
  }
}
