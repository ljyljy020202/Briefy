package com.briefy.domain.company.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.briefy.domain.company.dto.admin.CompanySourceAdminResponse;
import com.briefy.domain.company.service.CompanySourceAdminService;
import com.briefy.global.exception.BusinessException;
import com.briefy.global.exception.ErrorCode;
import com.briefy.global.exception.GlobalExceptionHandler;
import com.briefy.global.response.PageResult;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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
class AdminCompanySourceControllerTest {

  @Mock private CompanySourceAdminService companySourceAdminService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();

    mockMvc =
        MockMvcBuilders.standaloneSetup(new AdminCompanySourceController(companySourceAdminService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setValidator(validator)
            .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
            .build();
  }

  // ── listSources ───────────────────────────────────────────────────────────

  @Test
  void listSources_returns200() throws Exception {
    PageResult<CompanySourceAdminResponse> result =
        new PageResult<>(List.of(sampleSourceResponse()), 0, 20, 1, 1);
    when(companySourceAdminService.listSources(any())).thenReturn(result);

    mockMvc
        .perform(get("/api/admin/company-sources"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.content[0].id").value(1))
        .andExpect(jsonPath("$.data.totalElements").value(1));
  }

  // ── getSource ─────────────────────────────────────────────────────────────

  @Test
  void getSource_returns200() throws Exception {
    when(companySourceAdminService.getSource(1L)).thenReturn(sampleSourceResponse());

    mockMvc
        .perform(get("/api/admin/company-sources/1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.id").value(1))
        .andExpect(jsonPath("$.data.adapterType").value("CUSTOM"))
        .andExpect(jsonPath("$.data.status").value("PENDING"));
  }

  @Test
  void getSource_notFound_returns404() throws Exception {
    when(companySourceAdminService.getSource(99L))
        .thenThrow(new BusinessException(ErrorCode.COMPANY_SOURCE_NOT_FOUND));

    mockMvc
        .perform(get("/api/admin/company-sources/99"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("COMPANY_SOURCE_NOT_FOUND"));
  }

  // ── createSource ──────────────────────────────────────────────────────────

  @Test
  void createSource_returns201() throws Exception {
    when(companySourceAdminService.createSource(any())).thenReturn(sampleSourceResponse());

    mockMvc
        .perform(
            post("/api/admin/company-sources")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "companyId": 1,
                      "sourceType": "OFFICIAL_CAREER",
                      "sourceUrl": "https://careers.example.com",
                      "adapterType": "CUSTOM",
                      "status": "PENDING",
                      "config": {"parser_key": "EXAMPLE_CAREERS"}
                    }
                    """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.id").value(1));
  }

  @Test
  void createSource_missingCompanyId_returns400() throws Exception {
    mockMvc
        .perform(
            post("/api/admin/company-sources")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "sourceType": "OFFICIAL_CAREER",
                      "adapterType": "SITEMAP",
                      "status": "PENDING"
                    }
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
  }

  @Test
  void createSource_invalidStatus_returns400() throws Exception {
    when(companySourceAdminService.createSource(any()))
        .thenThrow(new BusinessException(ErrorCode.INVALID_SOURCE_STATUS));

    mockMvc
        .perform(
            post("/api/admin/company-sources")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "companyId": 1,
                      "sourceType": "OFFICIAL_CAREER",
                      "sourceUrl": "https://example.com",
                      "adapterType": "SITEMAP",
                      "status": "ACTIVE"
                    }
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_SOURCE_STATUS"));
  }

  @Test
  void createSource_invalidAdapterType_returns400() throws Exception {
    when(companySourceAdminService.createSource(any()))
        .thenThrow(new BusinessException(ErrorCode.INVALID_ADAPTER_TYPE));

    mockMvc
        .perform(
            post("/api/admin/company-sources")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "companyId": 1,
                      "sourceType": "OFFICIAL_CAREER",
                      "sourceUrl": "https://example.com",
                      "adapterType": "SCRAPER",
                      "status": "PENDING"
                    }
                    """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_ADAPTER_TYPE"));
  }

  @Test
  void createSource_duplicateSource_returns409() throws Exception {
    when(companySourceAdminService.createSource(any()))
        .thenThrow(new BusinessException(ErrorCode.DUPLICATE_COMPANY_SOURCE));

    mockMvc
        .perform(
            post("/api/admin/company-sources")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "companyId": 1,
                      "sourceType": "OFFICIAL_CAREER",
                      "sourceUrl": "https://example.com",
                      "adapterType": "SITEMAP",
                      "status": "PENDING"
                    }
                    """))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("DUPLICATE_COMPANY_SOURCE"));
  }

  // ── updateSource ──────────────────────────────────────────────────────────

  @Test
  void updateSource_returns200() throws Exception {
    when(companySourceAdminService.updateSource(eq(1L), any())).thenReturn(sampleSourceResponse());

    mockMvc
        .perform(
            patch("/api/admin/company-sources/1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"INACTIVE\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true));
  }

  @Test
  void updateSource_patchActiveStatus_returns400() throws Exception {
    when(companySourceAdminService.updateSource(eq(1L), any()))
        .thenThrow(new BusinessException(ErrorCode.INVALID_SOURCE_STATUS));

    mockMvc
        .perform(
            patch("/api/admin/company-sources/1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"ACTIVE\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("INVALID_SOURCE_STATUS"));
  }

  // ── deactivateSource ──────────────────────────────────────────────────────

  @Test
  void deactivateSource_returns200() throws Exception {
    CompanySourceAdminResponse inactive =
        new CompanySourceAdminResponse(
            1L,
            1L,
            "네이버",
            "OFFICIAL_CAREER",
            "https://recruit.navercorp.com",
            "CUSTOM",
            "INACTIVE",
            Map.of("parser_key", "NAVER_CAREERS"),
            null,
            null,
            LocalDateTime.now(),
            LocalDateTime.now());
    when(companySourceAdminService.deactivateSource(1L)).thenReturn(inactive);

    mockMvc
        .perform(post("/api/admin/company-sources/1/deactivate"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("INACTIVE"));
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  private CompanySourceAdminResponse sampleSourceResponse() {
    return new CompanySourceAdminResponse(
        1L,
        1L,
        "네이버",
        "OFFICIAL_CAREER",
        "https://recruit.navercorp.com",
        "CUSTOM",
        "PENDING",
        Map.of("parser_key", "NAVER_CAREERS"),
        null,
        null,
        LocalDateTime.now(),
        LocalDateTime.now());
  }
}
