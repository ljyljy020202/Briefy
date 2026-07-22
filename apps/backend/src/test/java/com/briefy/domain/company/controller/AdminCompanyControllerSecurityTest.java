package com.briefy.domain.company.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.briefy.config.AppProperties;
import com.briefy.config.JwtProperties;
import com.briefy.config.SecurityConfig;
import com.briefy.domain.auth.service.JwtTokenProvider;
import com.briefy.domain.company.service.CompanyAdminService;
import com.briefy.global.response.PageResult;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdminCompanyController.class)
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
class AdminCompanyControllerSecurityTest {

  @Autowired private MockMvc mockMvc;

  @MockitoBean private JwtTokenProvider jwtTokenProvider;
  @MockitoBean private CompanyAdminService companyAdminService;

  @Test
  void listCompanies_unauthenticated_returns401() throws Exception {
    mockMvc
        .perform(get("/api/admin/companies"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.error.code").value("UNAUTHORIZED"));
  }

  @Test
  @WithMockUser(roles = "USER")
  void listCompanies_userRole_returns403() throws Exception {
    mockMvc
        .perform(get("/api/admin/companies"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void listCompanies_adminRole_returns200() throws Exception {
    when(companyAdminService.listCompanies(any()))
        .thenReturn(new PageResult<>(List.of(), 0, 20, 0, 0));

    mockMvc.perform(get("/api/admin/companies")).andExpect(status().isOk());
  }
}
