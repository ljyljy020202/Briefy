package com.briefy.domain.briefing.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.briefy.domain.briefing.dto.GenerateResult;
import com.briefy.domain.briefing.service.BriefingService;
import com.briefy.global.exception.BusinessException;
import com.briefy.global.exception.ErrorCode;
import com.briefy.global.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

@ExtendWith(MockitoExtension.class)
class AdminBriefingControllerTest {

  @Mock private BriefingService briefingService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();

    mockMvc =
        MockMvcBuilders.standaloneSetup(new AdminBriefingController(briefingService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setValidator(validator)
            .build();
  }

  @Test
  void generate_returns201_withGenerateResult() throws Exception {
    when(briefingService.generateBriefing(eq(4L)))
        .thenReturn(new GenerateResult(100L, 50L, "COMPLETED"));

    mockMvc
        .perform(
            post("/api/admin/briefings/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":4}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.briefingReportId").value(100))
        .andExpect(jsonPath("$.data.jobId").value(50))
        .andExpect(jsonPath("$.data.status").value("COMPLETED"));
  }

  @Test
  void generate_missingUserId_returns400() throws Exception {
    mockMvc
        .perform(
            post("/api/admin/briefings/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
  }

  @Test
  void generate_agentError_returns502() throws Exception {
    when(briefingService.generateBriefing(eq(4L)))
        .thenThrow(new BusinessException(ErrorCode.AGENT_SERVER_ERROR));

    mockMvc
        .perform(
            post("/api/admin/briefings/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":4}"))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.error.code").value("AGENT_SERVER_ERROR"));
  }
}
