package com.briefy.domain.company.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.briefy.domain.company.dto.CompanySearchResult;
import com.briefy.domain.company.service.CompanyService;
import com.briefy.global.exception.GlobalExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class CompanyControllerTest {

  @Mock private CompanyService companyService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.standaloneSetup(new CompanyController(companyService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  void search_returns200_withMatchingCompanies() throws Exception {
    when(companyService.search(any(), anyInt()))
        .thenReturn(
            List.of(
                new CompanySearchResult(1L, "카카오", "대기업", List.of("IT/소프트웨어", "핀테크")),
                new CompanySearchResult(2L, "카카오엔터테인먼트", "대기업", List.of("IT/소프트웨어"))));

    mockMvc
        .perform(get("/api/companies/search").param("q", "카카오"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data").isArray())
        .andExpect(jsonPath("$.data.length()").value(2))
        .andExpect(jsonPath("$.data[0].id").value(1))
        .andExpect(jsonPath("$.data[0].canonicalName").value("카카오"))
        .andExpect(jsonPath("$.data[0].companySize").value("대기업"))
        .andExpect(jsonPath("$.data[0].industryCodes[0]").value("IT/소프트웨어"))
        .andExpect(jsonPath("$.data[0].industryCodes[1]").value("핀테크"));
  }

  @Test
  void search_returns200_withEmptyList_whenNoMatch() throws Exception {
    when(companyService.search(any(), anyInt())).thenReturn(List.of());

    mockMvc
        .perform(get("/api/companies/search").param("q", "없는회사"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data").isArray())
        .andExpect(jsonPath("$.data").isEmpty());
  }

  @Test
  void search_returns200_withEmptyList_whenBlankQuery() throws Exception {
    when(companyService.search(any(), anyInt())).thenReturn(List.of());

    mockMvc
        .perform(get("/api/companies/search").param("q", ""))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data").isEmpty());
  }

  @Test
  void search_returns400_whenQueryParamMissing() throws Exception {
    mockMvc.perform(get("/api/companies/search")).andExpect(status().isBadRequest());
  }

  @Test
  void search_usesDefaultLimit_whenLimitNotGiven() throws Exception {
    when(companyService.search(any(), anyInt())).thenReturn(List.of());

    mockMvc.perform(get("/api/companies/search").param("q", "네이버")).andExpect(status().isOk());
  }
}
