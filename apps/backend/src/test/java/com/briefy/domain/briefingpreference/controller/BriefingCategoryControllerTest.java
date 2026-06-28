package com.briefy.domain.briefingpreference.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.briefy.domain.briefingpreference.dto.BriefingCategoryResponse;
import com.briefy.domain.briefingpreference.service.BriefingCategoryService;
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
class BriefingCategoryControllerTest {

  @Mock private BriefingCategoryService briefingCategoryService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.standaloneSetup(new BriefingCategoryController(briefingCategoryService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  void getActiveCategories_returns200_withCategoryList() throws Exception {
    when(briefingCategoryService.getActiveCategories())
        .thenReturn(
            List.of(new BriefingCategoryResponse(1L, "JOB_POSTING", "채용 브리핑", "1st", true)));

    mockMvc
        .perform(get("/api/briefing-categories"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data[0].code").value("JOB_POSTING"))
        .andExpect(jsonPath("$.data[0].displayName").value("채용 브리핑"))
        .andExpect(jsonPath("$.data[0].phase").value("1st"))
        .andExpect(jsonPath("$.data[0].active").value(true));
  }

  @Test
  void getActiveCategories_returns200_withEmptyList() throws Exception {
    when(briefingCategoryService.getActiveCategories()).thenReturn(List.of());

    mockMvc
        .perform(get("/api/briefing-categories"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data").isArray())
        .andExpect(jsonPath("$.data").isEmpty());
  }
}
