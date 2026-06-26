package com.briefy.domain.topic.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.briefy.domain.topic.dto.TopicResponse;
import com.briefy.domain.topic.service.TopicService;
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
class TopicControllerTest {

  @Mock private TopicService topicService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.standaloneSetup(new TopicController(topicService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  void getAllTopics_returnsActiveTopicList() throws Exception {
    when(topicService.getActiveTopics())
        .thenReturn(
            List.of(
                new TopicResponse(1L, "Target Role", "target-role", "JOB_PREFERENCE", "목표 직무", 1),
                new TopicResponse(
                    2L, "Target Companies", "target-companies", "JOB_PREFERENCE", "관심 회사", 2)));

    mockMvc
        .perform(get("/api/topics"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.length()").value(2))
        .andExpect(jsonPath("$.data[0].name").value("Target Role"))
        .andExpect(jsonPath("$.data[0].category").value("JOB_PREFERENCE"))
        .andExpect(jsonPath("$.data[0].slug").value("target-role"))
        .andExpect(jsonPath("$.data[1].name").value("Target Companies"));
  }

  @Test
  void getAllTopics_returnsEmptyList_whenNoActiveTopics() throws Exception {
    when(topicService.getActiveTopics()).thenReturn(List.of());

    mockMvc
        .perform(get("/api/topics"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.length()").value(0));
  }
}
