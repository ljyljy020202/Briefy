package com.briefy.domain.company.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.briefy.config.AppProperties;
import com.briefy.config.JwtProperties;
import com.briefy.config.SecurityConfig;
import com.briefy.domain.auth.service.JwtTokenProvider;
import com.briefy.domain.company.dto.admin.CompanySourceAdminResponse;
import com.briefy.domain.company.dto.admin.CompanySourcePreflightResponse;
import com.briefy.domain.company.service.CompanySourceAdminService;
import com.briefy.global.response.PageResult;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdminCompanySourceController.class)
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
class AdminCompanySourceControllerSecurityTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private JwtTokenProvider jwtTokenProvider;
  @MockitoBean private CompanySourceAdminService companySourceAdminService;

  // ── unauthenticated ────────────────────────────────────────────────────────

  @Test
  void listSources_unauthenticated_returns401() throws Exception {
    mockMvc
        .perform(get("/api/admin/company-sources"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
  }

  @Test
  void preflight_unauthenticated_returns401() throws Exception {
    mockMvc
        .perform(post("/api/admin/company-sources/1/preflight"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
  }

  @Test
  void activate_unauthenticated_returns401() throws Exception {
    mockMvc
        .perform(post("/api/admin/company-sources/1/activate"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
  }

  // ── ROLE_USER (insufficient) ───────────────────────────────────────────────

  @Test
  @WithMockUser(roles = "USER")
  void listSources_userRole_returns403() throws Exception {
    mockMvc
        .perform(get("/api/admin/company-sources"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
  }

  @Test
  @WithMockUser(roles = "USER")
  void preflight_userRole_returns403() throws Exception {
    mockMvc
        .perform(post("/api/admin/company-sources/1/preflight"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
  }

  @Test
  @WithMockUser(roles = "USER")
  void activate_userRole_returns403() throws Exception {
    mockMvc
        .perform(post("/api/admin/company-sources/1/activate"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
  }

  // ── ROLE_ADMIN (success) ───────────────────────────────────────────────────

  @Test
  @WithMockUser(roles = "ADMIN")
  void listSources_adminRole_returns200() throws Exception {
    when(companySourceAdminService.listSources(any()))
        .thenReturn(new PageResult<>(List.of(), 0, 20, 0, 0));

    mockMvc.perform(get("/api/admin/company-sources")).andExpect(status().isOk());
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void preflight_adminRole_returns200() throws Exception {
    CompanySourcePreflightResponse resp =
        new CompanySourcePreflightResponse(
            1L,
            "PENDING",
            "VERIFIED",
            LocalDateTime.now(),
            true,
            true,
            false,
            5,
            1,
            true,
            List.of(),
            null,
            null,
            "Preflight succeeded. Use POST /{id}/activate to activate the source.");
    when(companySourceAdminService.runPreflight(1L)).thenReturn(resp);

    mockMvc
        .perform(post("/api/admin/company-sources/1/preflight"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.verificationStatus").value("VERIFIED"));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void activate_adminRole_returns200() throws Exception {
    CompanySourceAdminResponse active =
        new CompanySourceAdminResponse(
            1L,
            1L,
            "네이버",
            "OFFICIAL_CAREER",
            "https://recruit.navercorp.com",
            "CUSTOM",
            "ACTIVE",
            "VERIFIED",
            null,
            Map.of("parser_key", "NAVER_CAREERS"),
            LocalDateTime.now(),
            null,
            LocalDateTime.now(),
            LocalDateTime.now());
    when(companySourceAdminService.activateSource(eq(1L))).thenReturn(active);

    mockMvc
        .perform(post("/api/admin/company-sources/1/activate"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("ACTIVE"));
  }
}
