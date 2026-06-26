package com.briefy.domain.briefing.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.briefy.domain.briefing.dto.BriefingDetailResponse;
import com.briefy.domain.briefing.dto.BriefingListItem;
import com.briefy.domain.briefing.dto.GenerateBriefingRequest;
import com.briefy.domain.briefing.dto.GenerateResult;
import com.briefy.domain.briefing.service.BriefingService;
import com.briefy.global.auth.AuthenticatedUser;
import com.briefy.global.auth.CurrentUserProvider;
import com.briefy.global.exception.BusinessException;
import com.briefy.global.exception.ErrorCode;
import com.briefy.global.exception.GlobalExceptionHandler;
import com.briefy.global.response.PageResult;
import java.util.List;
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
class BriefingControllerTest {

  @Mock private BriefingService briefingService;
  @Mock private CurrentUserProvider currentUserProvider;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();

    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new BriefingController(briefingService, currentUserProvider))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setValidator(validator)
            .build();
  }

  @Test
  void generate_returns201_withGenerateResult() throws Exception {
    when(currentUserProvider.getCurrentUser()).thenReturn(new AuthenticatedUser(1L));
    when(briefingService.generateBriefing(eq(1L), any(GenerateBriefingRequest.class)))
        .thenReturn(new GenerateResult(100L, 50L, "COMPLETED"));

    mockMvc
        .perform(
            post("/api/briefings/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tone\":\"easy\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.briefingReportId").value(100))
        .andExpect(jsonPath("$.data.jobId").value(50))
        .andExpect(jsonPath("$.data.status").value("COMPLETED"));
  }

  @Test
  void generate_withEmptyBody_returns201() throws Exception {
    when(currentUserProvider.getCurrentUser()).thenReturn(new AuthenticatedUser(1L));
    when(briefingService.generateBriefing(eq(1L), any(GenerateBriefingRequest.class)))
        .thenReturn(new GenerateResult(101L, 51L, "COMPLETED"));

    mockMvc
        .perform(
            post("/api/briefings/generate").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.status").value("COMPLETED"));
  }

  @Test
  void generate_toneExceedsMaxLength_returns400() throws Exception {
    mockMvc
        .perform(
            post("/api/briefings/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tone\":\"" + "x".repeat(31) + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
  }

  @Test
  void generate_agentError_returns502() throws Exception {
    when(currentUserProvider.getCurrentUser()).thenReturn(new AuthenticatedUser(1L));
    when(briefingService.generateBriefing(eq(1L), any(GenerateBriefingRequest.class)))
        .thenThrow(new BusinessException(ErrorCode.AGENT_SERVER_ERROR));

    mockMvc
        .perform(
            post("/api/briefings/generate").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.error.code").value("AGENT_SERVER_ERROR"));
  }

  @Test
  void list_returns200_withPageResult() throws Exception {
    when(currentUserProvider.getCurrentUser()).thenReturn(new AuthenticatedUser(1L));
    BriefingListItem item =
        new BriefingListItem(1L, "오늘의 채용 브리핑", "요약", "2026-06-26", 2, "2026-06-26T09:00:00");
    PageResult<BriefingListItem> pageResult = new PageResult<>(List.of(item), 0, 10, 1L, 1);
    when(briefingService.listBriefings(1L, 0, 10)).thenReturn(pageResult);

    mockMvc
        .perform(get("/api/briefings"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.content.length()").value(1))
        .andExpect(jsonPath("$.data.content[0].title").value("오늘의 채용 브리핑"))
        .andExpect(jsonPath("$.data.content[0].reportDate").value("2026-06-26"))
        .andExpect(jsonPath("$.data.totalElements").value(1))
        .andExpect(jsonPath("$.data.totalPages").value(1));
  }

  @Test
  void list_withCustomPagination_passesParamsToService() throws Exception {
    when(currentUserProvider.getCurrentUser()).thenReturn(new AuthenticatedUser(1L));
    PageResult<BriefingListItem> emptyPage = new PageResult<>(List.of(), 1, 5, 0L, 0);
    when(briefingService.listBriefings(1L, 1, 5)).thenReturn(emptyPage);

    mockMvc
        .perform(get("/api/briefings").param("page", "1").param("size", "5"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.content.length()").value(0));
  }

  @Test
  void getById_returns200_withDetailAndArticles() throws Exception {
    when(currentUserProvider.getCurrentUser()).thenReturn(new AuthenticatedUser(1L));
    BriefingDetailResponse detail =
        new BriefingDetailResponse(
            1L, "오늘의 채용 브리핑", "요약", "## 신규 공고\n...", "2026-06-26", List.of());
    when(briefingService.getBriefingDetail(1L, 1L)).thenReturn(detail);

    mockMvc
        .perform(get("/api/briefings/1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.title").value("오늘의 채용 브리핑"))
        .andExpect(jsonPath("$.data.reportDate").value("2026-06-26"))
        .andExpect(jsonPath("$.data.articles").isArray());
  }

  @Test
  void getById_notFound_returns404() throws Exception {
    when(currentUserProvider.getCurrentUser()).thenReturn(new AuthenticatedUser(1L));
    when(briefingService.getBriefingDetail(1L, 99L))
        .thenThrow(new BusinessException(ErrorCode.BRIEFING_REPORT_NOT_FOUND));

    mockMvc
        .perform(get("/api/briefings/99"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("BRIEFING_REPORT_NOT_FOUND"));
  }

  @Test
  void getById_forbidden_returns403() throws Exception {
    when(currentUserProvider.getCurrentUser()).thenReturn(new AuthenticatedUser(1L));
    when(briefingService.getBriefingDetail(1L, 5L))
        .thenThrow(new BusinessException(ErrorCode.FORBIDDEN));

    mockMvc
        .perform(get("/api/briefings/5"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
  }
}
