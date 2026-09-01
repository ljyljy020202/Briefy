package com.briefy.domain.candidatepool.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.briefy.domain.candidatepool.dto.AnalysisStatsResponse;
import com.briefy.domain.candidatepool.dto.BackfillRequest;
import com.briefy.domain.candidatepool.dto.BackfillResponse;
import com.briefy.domain.candidatepool.service.AnalysisBackfillService;
import com.briefy.global.exception.BusinessException;
import com.briefy.global.exception.ErrorCode;
import com.briefy.global.exception.GlobalExceptionHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class AdminAnalysisControllerTest {

  @Mock private AnalysisBackfillService backfillService;

  private MockMvc mockMvc;
  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.standaloneSetup(new AdminAnalysisController(backfillService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  // ── POST /api/admin/job-posting-analyses/backfill ────────────────────────────

  @Test
  void backfill_dryRun_returns200() throws Exception {
    BackfillRequest request =
        new BackfillRequest(true, List.of(), null, null, 10, null, false, null);
    BackfillResponse response = new BackfillResponse(true, 5, 0, 5, Map.of("NO_ANALYSIS_ROW", 5));
    when(backfillService.backfill(any())).thenReturn(response);

    mockMvc
        .perform(
            post("/api/admin/job-posting-analyses/backfill")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.dryRun").value(true))
        .andExpect(jsonPath("$.data.targetCount").value(5))
        .andExpect(jsonPath("$.data.enqueuedCount").value(0));
  }

  @Test
  void backfill_realRun_returns202() throws Exception {
    BackfillRequest request =
        new BackfillRequest(false, List.of(), null, null, 10, null, false, null);
    BackfillResponse response = new BackfillResponse(false, 10, 8, 2, Map.of("NO_ANALYSIS_ROW", 8));
    when(backfillService.backfill(any())).thenReturn(response);

    mockMvc
        .perform(
            post("/api/admin/job-posting-analyses/backfill")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.enqueuedCount").value(8));
  }

  @Test
  void backfill_limitExceeded_returns400() throws Exception {
    BackfillRequest request =
        new BackfillRequest(false, List.of(), null, null, 2000, null, false, null);
    when(backfillService.backfill(any()))
        .thenThrow(
            new BusinessException(
                ErrorCode.CLASSIFICATION_BACKFILL_LIMIT_EXCEEDED,
                "backfill limit은 1~1000 사이여야 합니다."));

    mockMvc
        .perform(
            post("/api/admin/job-posting-analyses/backfill")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.success").value(false));
  }

  @Test
  void backfill_withExplicitPostingIds_returns202() throws Exception {
    BackfillRequest request =
        new BackfillRequest(
            false, List.of(101L, 102L, 103L), null, null, 100, null, false, "1.1.0");
    BackfillResponse response = new BackfillResponse(false, 3, 3, 0, Map.of("NO_ANALYSIS_ROW", 3));
    when(backfillService.backfill(any())).thenReturn(response);

    mockMvc
        .perform(
            post("/api/admin/job-posting-analyses/backfill")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.data.targetCount").value(3))
        .andExpect(jsonPath("$.data.enqueuedCount").value(3));
  }

  @Test
  void backfill_forceReclassify_returns202() throws Exception {
    BackfillRequest request =
        new BackfillRequest(false, List.of(), null, null, 50, null, true, null);
    BackfillResponse response =
        new BackfillResponse(false, 50, 50, 0, Map.of("FORCE_RECLASSIFY", 50));
    when(backfillService.backfill(any())).thenReturn(response);

    mockMvc
        .perform(
            post("/api/admin/job-posting-analyses/backfill")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.data.reasons.FORCE_RECLASSIFY").value(50));
  }

  // ── GET /api/admin/job-posting-analyses/stats ────────────────────────────────

  @Test
  void stats_returns200WithCounts() throws Exception {
    AnalysisStatsResponse stats =
        new AnalysisStatsResponse(
            100L,
            Map.of("PENDING", 30L, "SUCCEEDED", 60L, "FAILED", 10L),
            30L,
            0L,
            60L,
            0L,
            10L,
            0L);
    when(backfillService.stats()).thenReturn(stats);

    mockMvc
        .perform(get("/api/admin/job-posting-analyses/stats"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.totalAnalysisRows").value(100))
        .andExpect(jsonPath("$.data.pendingCount").value(30))
        .andExpect(jsonPath("$.data.succeededCount").value(60))
        .andExpect(jsonPath("$.data.failedCount").value(10));
  }

  @Test
  void stats_emptyTable_returns200WithZeros() throws Exception {
    AnalysisStatsResponse stats =
        new AnalysisStatsResponse(0L, Collections.emptyMap(), 0L, 0L, 0L, 0L, 0L, 0L);
    when(backfillService.stats()).thenReturn(stats);

    mockMvc
        .perform(get("/api/admin/job-posting-analyses/stats"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.totalAnalysisRows").value(0));
  }
}
