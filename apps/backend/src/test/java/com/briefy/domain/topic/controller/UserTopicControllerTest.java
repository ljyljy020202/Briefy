package com.briefy.domain.topic.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.briefy.domain.topic.dto.BulkCreateUserTopicRequest;
import com.briefy.domain.topic.dto.BulkCreateUserTopicResponse;
import com.briefy.domain.topic.dto.CreateUserTopicRequest;
import com.briefy.domain.topic.dto.UserTopicResponse;
import com.briefy.domain.topic.service.UserTopicService;
import com.briefy.global.auth.AuthenticatedUser;
import com.briefy.global.auth.CurrentUserProvider;
import com.briefy.global.exception.BusinessException;
import com.briefy.global.exception.ErrorCode;
import com.briefy.global.exception.GlobalExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

@ExtendWith(MockitoExtension.class)
class UserTopicControllerTest {

  @Mock private UserTopicService userTopicService;
  @Mock private CurrentUserProvider currentUserProvider;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();

    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new UserTopicController(userTopicService, currentUserProvider))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setValidator(validator)
            .build();
  }

  @Test
  void getMyTopics_returnsSubscriptionList() throws Exception {
    when(currentUserProvider.getCurrentUser()).thenReturn(new AuthenticatedUser(1L));
    when(userTopicService.getMyTopics(1L))
        .thenReturn(List.of(new UserTopicResponse(10L, 1L, "Target Role", "백엔드 개발자", 1, true)));

    mockMvc
        .perform(get("/api/me/topics"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.length()").value(1))
        .andExpect(jsonPath("$.data[0].id").value(10))
        .andExpect(jsonPath("$.data[0].topicName").value("Target Role"))
        .andExpect(jsonPath("$.data[0].keyword").value("백엔드 개발자"))
        .andExpect(jsonPath("$.data[0].isActive").value(true));
  }

  @Test
  void addTopic_returns201_withCreatedSubscription() throws Exception {
    when(currentUserProvider.getCurrentUser()).thenReturn(new AuthenticatedUser(1L));
    when(userTopicService.addTopic(eq(1L), any(CreateUserTopicRequest.class)))
        .thenReturn(new UserTopicResponse(10L, 1L, "Target Role", "백엔드 개발자", 1, true));

    mockMvc
        .perform(
            post("/api/me/topics")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"topicId\":1,\"keyword\":\"백엔드 개발자\",\"priority\":1}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.id").value(10))
        .andExpect(jsonPath("$.data.keyword").value("백엔드 개발자"));
  }

  @Test
  void addTopic_returns409_whenDuplicate() throws Exception {
    when(currentUserProvider.getCurrentUser()).thenReturn(new AuthenticatedUser(1L));
    when(userTopicService.addTopic(eq(1L), any(CreateUserTopicRequest.class)))
        .thenThrow(new BusinessException(ErrorCode.DUPLICATE_USER_TOPIC));

    mockMvc
        .perform(
            post("/api/me/topics")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"topicId\":1,\"keyword\":\"백엔드 개발자\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("DUPLICATE_USER_TOPIC"));
  }

  @Test
  void addTopic_returns400_whenKeywordBlank() throws Exception {
    mockMvc
        .perform(
            post("/api/me/topics")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"topicId\":1,\"keyword\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
  }

  @Test
  void bulkAddTopics_returns200_withCreatedCount() throws Exception {
    when(currentUserProvider.getCurrentUser()).thenReturn(new AuthenticatedUser(1L));
    when(userTopicService.bulkAddTopics(eq(1L), any(BulkCreateUserTopicRequest.class)))
        .thenReturn(new BulkCreateUserTopicResponse(3));

    mockMvc
        .perform(
            post("/api/me/topics/bulk")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"topics\":[{\"topicId\":1,\"keywords\":[\"OpenAI\",\"Claude\",\"LangGraph\"]}]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.createdCount").value(3));
  }

  @Test
  void deleteTopic_returns200_onSuccess() throws Exception {
    when(currentUserProvider.getCurrentUser()).thenReturn(new AuthenticatedUser(1L));

    mockMvc
        .perform(delete("/api/me/topics/10"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data").doesNotExist());
  }

  @Test
  void deleteTopic_returns403_whenNotOwner() throws Exception {
    when(currentUserProvider.getCurrentUser()).thenReturn(new AuthenticatedUser(1L));
    doThrow(new BusinessException(ErrorCode.FORBIDDEN)).when(userTopicService).deleteTopic(1L, 10L);

    mockMvc
        .perform(delete("/api/me/topics/10"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
  }
}
