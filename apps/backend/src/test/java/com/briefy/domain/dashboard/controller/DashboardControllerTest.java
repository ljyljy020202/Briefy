package com.briefy.domain.dashboard.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.briefy.domain.briefing.dto.BriefingListItem;
import com.briefy.domain.dashboard.dto.DashboardResponse;
import com.briefy.domain.dashboard.service.DashboardService;
import com.briefy.global.auth.AuthenticatedUser;
import com.briefy.global.auth.CurrentUserProvider;
import com.briefy.global.exception.BusinessException;
import com.briefy.global.exception.ErrorCode;
import com.briefy.global.exception.GlobalExceptionHandler;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class DashboardControllerTest {

  @Mock private DashboardService dashboardService;
  @Mock private CurrentUserProvider currentUserProvider;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new DashboardController(dashboardService, currentUserProvider))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  void getDashboard_returns200_withFullSummary() throws Exception {
    when(currentUserProvider.getCurrentUser()).thenReturn(new AuthenticatedUser(1L));

    BriefingListItem latest =
        new BriefingListItem(1L, "오늘의 채용 브리핑", "요약", "2026-06-26", 5, "2026-06-26T09:00:00");

    DashboardResponse response =
        new DashboardResponse(
            new DashboardResponse.UserSummary("테스터", "test@example.com", true),
            List.of(
                new DashboardResponse.BriefingPreferenceSummary(
                    "JOB_POSTING", "채용 브리핑", Map.of("roles", List.of("백엔드 개발자")))),
            null,
            latest,
            null,
            List.of(latest));

    when(dashboardService.getDashboard(1L)).thenReturn(response);

    mockMvc
        .perform(get("/api/dashboard"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.user.nickname").value("테스터"))
        .andExpect(jsonPath("$.data.user.email").value("test@example.com"))
        .andExpect(jsonPath("$.data.user.onboardingCompleted").value(true))
        .andExpect(jsonPath("$.data.briefingPreferences[0].categoryCode").value("JOB_POSTING"))
        .andExpect(jsonPath("$.data.briefingPreferences[0].categoryDisplayName").value("채용 브리핑"))
        .andExpect(jsonPath("$.data.briefingPreferences[0].preference.roles[0]").value("백엔드 개발자"))
        .andExpect(jsonPath("$.data.latestBriefing.id").value(1))
        .andExpect(jsonPath("$.data.latestBriefing.title").value("오늘의 채용 브리핑"))
        .andExpect(jsonPath("$.data.latestBriefing.reportDate").value("2026-06-26"))
        .andExpect(jsonPath("$.data.latestBriefing.articleCount").value(5))
        .andExpect(jsonPath("$.data.recentReports").isArray())
        .andExpect(jsonPath("$.data.recentReports.length()").value(1))
        .andExpect(jsonPath("$.data.nextDeliveryTime").doesNotExist())
        .andExpect(jsonPath("$.data.latestDeliveryStatus").doesNotExist());
  }

  @Test
  void getDashboard_noReports_latestBriefingAbsent() throws Exception {
    when(currentUserProvider.getCurrentUser()).thenReturn(new AuthenticatedUser(1L));

    DashboardResponse response =
        new DashboardResponse(
            new DashboardResponse.UserSummary("테스터", "test@example.com", false),
            List.of(),
            null,
            null,
            null,
            List.of());

    when(dashboardService.getDashboard(1L)).thenReturn(response);

    mockMvc
        .perform(get("/api/dashboard"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.latestBriefing").doesNotExist())
        .andExpect(jsonPath("$.data.recentReports").isArray())
        .andExpect(jsonPath("$.data.recentReports").isEmpty());
  }

  @Test
  void getDashboard_userNotFound_returns404() throws Exception {
    when(currentUserProvider.getCurrentUser()).thenReturn(new AuthenticatedUser(99L));
    when(dashboardService.getDashboard(99L))
        .thenThrow(new BusinessException(ErrorCode.USER_NOT_FOUND));

    mockMvc
        .perform(get("/api/dashboard"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("USER_NOT_FOUND"));
  }
}
