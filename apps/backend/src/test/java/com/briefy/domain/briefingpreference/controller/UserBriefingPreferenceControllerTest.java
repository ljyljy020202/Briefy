package com.briefy.domain.briefingpreference.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.briefy.domain.briefingpreference.dto.BriefingPreferenceResponse;
import com.briefy.domain.briefingpreference.dto.PatchBriefingPreferenceRequest;
import com.briefy.domain.briefingpreference.dto.UpsertBriefingPreferenceRequest;
import com.briefy.domain.briefingpreference.service.UserBriefingPreferenceService;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

@ExtendWith(MockitoExtension.class)
class UserBriefingPreferenceControllerTest {

  @Mock private UserBriefingPreferenceService preferenceService;
  @Mock private CurrentUserProvider currentUserProvider;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();

    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new UserBriefingPreferenceController(preferenceService, currentUserProvider))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setValidator(validator)
            .build();
  }

  private BriefingPreferenceResponse sampleResponse() {
    return new BriefingPreferenceResponse(
        1L, "JOB_POSTING", "채용 브리핑", Map.of("roles", List.of("백엔드 개발자")), true, null, null);
  }

  @Test
  void getMyPreferences_returns200_withList() throws Exception {
    when(currentUserProvider.getCurrentUser()).thenReturn(new AuthenticatedUser(1L));
    when(preferenceService.getMyPreferences(1L)).thenReturn(List.of(sampleResponse()));

    mockMvc
        .perform(get("/api/me/briefing-preferences"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data[0].categoryCode").value("JOB_POSTING"))
        .andExpect(jsonPath("$.data[0].categoryDisplayName").value("채용 브리핑"))
        .andExpect(jsonPath("$.data[0].active").value(true));
  }

  @Test
  void createPreference_returns201_whenSuccessful() throws Exception {
    when(currentUserProvider.getCurrentUser()).thenReturn(new AuthenticatedUser(1L));
    when(preferenceService.upsertPreference(eq(1L), any(UpsertBriefingPreferenceRequest.class)))
        .thenReturn(sampleResponse());

    mockMvc
        .perform(
            post("/api/me/briefing-preferences")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "categoryId": 1,
                      "preference": {
                        "roles": ["백엔드 개발자"]
                      }
                    }
                    """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.categoryCode").value("JOB_POSTING"));
  }

  @Test
  void createPreference_returns409_whenDuplicate() throws Exception {
    when(currentUserProvider.getCurrentUser()).thenReturn(new AuthenticatedUser(1L));
    when(preferenceService.upsertPreference(eq(1L), any(UpsertBriefingPreferenceRequest.class)))
        .thenThrow(new BusinessException(ErrorCode.DUPLICATE_BRIEFING_PREFERENCE));

    mockMvc
        .perform(
            post("/api/me/briefing-preferences")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "categoryId": 1,
                      "preference": {}
                    }
                    """))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error.code").value("DUPLICATE_BRIEFING_PREFERENCE"));
  }

  @Test
  void patchPreference_returns200_whenSuccessful() throws Exception {
    when(currentUserProvider.getCurrentUser()).thenReturn(new AuthenticatedUser(1L));
    when(preferenceService.patchPreference(
            eq(1L), eq(1L), any(PatchBriefingPreferenceRequest.class)))
        .thenReturn(sampleResponse());

    mockMvc
        .perform(
            patch("/api/me/briefing-preferences/1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "preference": {
                        "roles": ["백엔드 개발자"]
                      }
                    }
                    """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.categoryCode").value("JOB_POSTING"));
  }

  @Test
  void deletePreference_returns200_whenSuccessful() throws Exception {
    when(currentUserProvider.getCurrentUser()).thenReturn(new AuthenticatedUser(1L));

    mockMvc
        .perform(delete("/api/me/briefing-preferences/1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true));
  }

  @Test
  void deletePreference_returns404_whenNotFound() throws Exception {
    when(currentUserProvider.getCurrentUser()).thenReturn(new AuthenticatedUser(1L));
    doThrow(new BusinessException(ErrorCode.BRIEFING_PREFERENCE_NOT_FOUND))
        .when(preferenceService)
        .deletePreference(1L, 99L);

    mockMvc
        .perform(delete("/api/me/briefing-preferences/99"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("BRIEFING_PREFERENCE_NOT_FOUND"));
  }
}
