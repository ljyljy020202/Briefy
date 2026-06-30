package com.briefy.domain.collection.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.briefy.domain.collection.dto.DailyCollectionResult;
import com.briefy.domain.collection.service.DailyCollectionService;
import com.briefy.global.exception.GlobalExceptionHandler;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class CollectionControllerTest {

  @Mock private DailyCollectionService dailyCollectionService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.standaloneSetup(new CollectionController(dailyCollectionService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  void triggerDailyCollection_returns200_withCompletedResult() throws Exception {
    DailyCollectionResult result =
        new DailyCollectionResult(1L, "COMPLETED", LocalDate.of(2026, 6, 30), 3, 3, 0, null);
    when(dailyCollectionService.triggerDailyCollection(any(), any())).thenReturn(result);

    mockMvc
        .perform(
            post("/api/admin/collections/daily")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"collectDate\":\"2026-06-30\",\"categories\":[\"JOB_POSTING\"]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.status").value("COMPLETED"))
        .andExpect(jsonPath("$.data.collectionJobId").value(1))
        .andExpect(jsonPath("$.data.savedCount").value(3))
        .andExpect(jsonPath("$.data.collectedCount").value(3));
  }

  @Test
  void triggerDailyCollection_withEmptyBody_usesToday() throws Exception {
    DailyCollectionResult result =
        new DailyCollectionResult(2L, "COMPLETED", LocalDate.now(), 0, 0, 0, null);
    when(dailyCollectionService.triggerDailyCollection(any(), any())).thenReturn(result);

    mockMvc
        .perform(
            post("/api/admin/collections/daily")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("COMPLETED"));
  }

  @Test
  void triggerDailyCollection_failedStatus_returns200WithErrorMessage() throws Exception {
    DailyCollectionResult result =
        new DailyCollectionResult(
            3L, "FAILED", LocalDate.of(2026, 6, 30), 0, 0, 0, "Agent server error");
    when(dailyCollectionService.triggerDailyCollection(any(), any())).thenReturn(result);

    mockMvc
        .perform(
            post("/api/admin/collections/daily")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"collectDate\":\"2026-06-30\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("FAILED"))
        .andExpect(jsonPath("$.data.errorMessage").value("Agent server error"))
        .andExpect(jsonPath("$.data.collectedCount").value(0));
  }
}
