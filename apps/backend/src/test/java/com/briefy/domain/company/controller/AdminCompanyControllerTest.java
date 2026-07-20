package com.briefy.domain.company.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.briefy.domain.company.dto.admin.CompanyAdminResponse;
import com.briefy.domain.company.dto.admin.CompanyAliasResponse;
import com.briefy.domain.company.service.CompanyAdminService;
import com.briefy.global.exception.BusinessException;
import com.briefy.global.exception.ErrorCode;
import com.briefy.global.exception.GlobalExceptionHandler;
import com.briefy.global.response.PageResult;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

@ExtendWith(MockitoExtension.class)
class AdminCompanyControllerTest {

  @Mock private CompanyAdminService companyAdminService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();

    mockMvc =
        MockMvcBuilders.standaloneSetup(new AdminCompanyController(companyAdminService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setValidator(validator)
            .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
            .build();
  }

  // ── listCompanies ─────────────────────────────────────────────────────────

  @Test
  void listCompanies_returns200() throws Exception {
    PageResult<CompanyAdminResponse> result =
        new PageResult<>(List.of(sampleCompanyResponse()), 0, 20, 1, 1);
    when(companyAdminService.listCompanies(any())).thenReturn(result);

    mockMvc
        .perform(get("/api/admin/companies"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.content").isArray())
        .andExpect(jsonPath("$.data.content[0].id").value(1))
        .andExpect(jsonPath("$.data.totalElements").value(1));
  }

  // ── getCompany ────────────────────────────────────────────────────────────

  @Test
  void getCompany_returns200() throws Exception {
    when(companyAdminService.getCompany(1L)).thenReturn(sampleCompanyResponse());

    mockMvc
        .perform(get("/api/admin/companies/1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.id").value(1))
        .andExpect(jsonPath("$.data.canonicalName").value("네이버"))
        .andExpect(jsonPath("$.data.active").value(true));
  }

  @Test
  void getCompany_notFound_returns404() throws Exception {
    when(companyAdminService.getCompany(99L))
        .thenThrow(new BusinessException(ErrorCode.COMPANY_NOT_FOUND));

    mockMvc
        .perform(get("/api/admin/companies/99"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("COMPANY_NOT_FOUND"));
  }

  // ── createCompany ─────────────────────────────────────────────────────────

  @Test
  void createCompany_returns201() throws Exception {
    when(companyAdminService.createCompany(any())).thenReturn(sampleCompanyResponse());

    mockMvc
        .perform(
            post("/api/admin/companies")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"canonicalName\":\"네이버\",\"companySize\":\"대기업\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.canonicalName").value("네이버"));
  }

  @Test
  void createCompany_missingCanonicalName_returns400() throws Exception {
    mockMvc
        .perform(
            post("/api/admin/companies")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"companySize\":\"대기업\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
  }

  @Test
  void createCompany_blankCanonicalName_returns400() throws Exception {
    mockMvc
        .perform(
            post("/api/admin/companies")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"canonicalName\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
  }

  @Test
  void createCompany_duplicateNormalizedName_returns409() throws Exception {
    when(companyAdminService.createCompany(any()))
        .thenThrow(new BusinessException(ErrorCode.DUPLICATE_COMPANY_NORMALIZED_NAME));

    mockMvc
        .perform(
            post("/api/admin/companies")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"canonicalName\":\"네이버\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("DUPLICATE_COMPANY_NORMALIZED_NAME"));
  }

  // ── updateCompany ─────────────────────────────────────────────────────────

  @Test
  void updateCompany_returns200() throws Exception {
    when(companyAdminService.updateCompany(eq(1L), any())).thenReturn(sampleCompanyResponse());

    mockMvc
        .perform(
            patch("/api/admin/companies/1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"companySize\":\"중견기업\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true));
  }

  // ── activate / deactivate ─────────────────────────────────────────────────

  @Test
  void activateCompany_returns200() throws Exception {
    CompanyAdminResponse active = sampleCompanyResponse();
    when(companyAdminService.activateCompany(1L)).thenReturn(active);

    mockMvc
        .perform(post("/api/admin/companies/1/activate"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.active").value(true));
  }

  @Test
  void deactivateCompany_returns200() throws Exception {
    CompanyAdminResponse inactive =
        new CompanyAdminResponse(
            1L, "네이버", "naver", "대기업", List.of(), false, List.of(), List.of(), null, null);
    when(companyAdminService.deactivateCompany(1L)).thenReturn(inactive);

    mockMvc
        .perform(post("/api/admin/companies/1/deactivate"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.active").value(false));
  }

  // ── createAlias ───────────────────────────────────────────────────────────

  @Test
  void createAlias_returns201() throws Exception {
    CompanyAliasResponse aliasResponse =
        new CompanyAliasResponse(10L, 1L, "네이버랩스", "네이버랩스", LocalDateTime.now());
    when(companyAdminService.createAlias(eq(1L), any())).thenReturn(aliasResponse);

    mockMvc
        .perform(
            post("/api/admin/companies/1/aliases")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"alias\":\"네이버랩스\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.id").value(10))
        .andExpect(jsonPath("$.data.alias").value("네이버랩스"));
  }

  @Test
  void createAlias_missingAlias_returns400() throws Exception {
    mockMvc
        .perform(
            post("/api/admin/companies/1/aliases")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
  }

  @Test
  void createAlias_duplicate_returns409() throws Exception {
    when(companyAdminService.createAlias(eq(1L), any()))
        .thenThrow(new BusinessException(ErrorCode.DUPLICATE_COMPANY_ALIAS));

    mockMvc
        .perform(
            post("/api/admin/companies/1/aliases")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"alias\":\"kakao\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("DUPLICATE_COMPANY_ALIAS"));
  }

  // ── deleteAlias ───────────────────────────────────────────────────────────

  @Test
  void deleteAlias_returns204() throws Exception {
    doNothing().when(companyAdminService).deleteAlias(5L);

    mockMvc.perform(delete("/api/admin/company-aliases/5")).andExpect(status().isNoContent());
  }

  @Test
  void deleteAlias_notFound_returns404() throws Exception {
    doThrow(new BusinessException(ErrorCode.COMPANY_ALIAS_NOT_FOUND))
        .when(companyAdminService)
        .deleteAlias(99L);

    mockMvc
        .perform(delete("/api/admin/company-aliases/99"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("COMPANY_ALIAS_NOT_FOUND"));
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private CompanyAdminResponse sampleCompanyResponse() {
    return new CompanyAdminResponse(
        1L,
        "네이버",
        "naver",
        "대기업",
        List.of("IT/소프트웨어"),
        true,
        List.of(),
        List.of(),
        LocalDateTime.now(),
        LocalDateTime.now());
  }
}
