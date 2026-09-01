package com.briefy.domain.candidatepool.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.briefy.config.AppProperties;
import com.briefy.config.JwtProperties;
import com.briefy.config.SecurityConfig;
import com.briefy.domain.auth.service.JwtTokenProvider;
import com.briefy.domain.candidatepool.dto.AnalysisStatsResponse;
import com.briefy.domain.candidatepool.dto.BackfillResponse;
import com.briefy.domain.candidatepool.service.AnalysisBackfillService;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdminAnalysisController.class)
@Import(SecurityConfig.class)
@EnableConfigurationProperties({AppProperties.class, JwtProperties.class})
@TestPropertySource(
    properties = {
      "app.frontend-base-url=http://localhost:3000",
      "jwt.secret=test-secret-key-with-at-least-32-bytes!!",
      "jwt.expiration-seconds=3600",
      "jwt.cookie-name=briefy_access_token",
      "jwt.cookie-secure=false",
      "jwt.cookie-same-site=Lax"
    })
class AdminAnalysisControllerSecurityTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private JwtTokenProvider jwtTokenProvider;
  @MockitoBean private AnalysisBackfillService backfillService;

  // ── POST /backfill ────────────────────────────────────────────────────────────

  @Test
  void backfill_unauthenticated_returns401() throws Exception {
    mockMvc
        .perform(
            post("/api/admin/job-posting-analyses/backfill")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"dryRun\":true,\"limit\":10}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
  }

  @Test
  @WithMockUser(roles = "USER")
  void backfill_userRole_returns403() throws Exception {
    mockMvc
        .perform(
            post("/api/admin/job-posting-analyses/backfill")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"dryRun\":true,\"limit\":10}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void backfill_adminRole_returns2xx() throws Exception {
    when(backfillService.backfill(any()))
        .thenReturn(new BackfillResponse(true, 0, 0, 0, Collections.emptyMap()));

    mockMvc
        .perform(
            post("/api/admin/job-posting-analyses/backfill")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"dryRun\":true,\"limit\":10}"))
        .andExpect(status().isOk());
  }

  // ── GET /stats ────────────────────────────────────────────────────────────────

  @Test
  void stats_unauthenticated_returns401() throws Exception {
    mockMvc
        .perform(get("/api/admin/job-posting-analyses/stats"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @WithMockUser(roles = "USER")
  void stats_userRole_returns403() throws Exception {
    mockMvc.perform(get("/api/admin/job-posting-analyses/stats")).andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void stats_adminRole_returns200() throws Exception {
    when(backfillService.stats())
        .thenReturn(new AnalysisStatsResponse(0L, Collections.emptyMap(), 0L, 0L, 0L, 0L, 0L, 0L));

    mockMvc.perform(get("/api/admin/job-posting-analyses/stats")).andExpect(status().isOk());
  }
}
