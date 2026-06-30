package com.briefy.domain.user.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.briefy.domain.auth.service.AuthService;
import com.briefy.domain.user.dto.UpdateOnboardingRequest;
import com.briefy.domain.user.dto.UpdateOnboardingResponse;
import com.briefy.domain.user.dto.UserMeResponse;
import com.briefy.domain.user.service.UserService;
import com.briefy.global.auth.AuthenticatedUser;
import com.briefy.global.auth.CurrentUserProvider;
import com.briefy.global.exception.BusinessException;
import com.briefy.global.exception.ErrorCode;
import com.briefy.global.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

@ExtendWith(MockitoExtension.class)
class UserControllerTest {

  @Mock private UserService userService;
  @Mock private CurrentUserProvider currentUserProvider;
  @Mock private AuthService authService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();

    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new UserController(userService, currentUserProvider, authService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setValidator(validator)
            .build();
  }

  @Test
  void getMe_returnsUserProfile() throws Exception {
    when(currentUserProvider.getCurrentUser()).thenReturn(new AuthenticatedUser(1L));
    when(userService.getMe(1L))
        .thenReturn(
            new UserMeResponse(
                1L,
                "user@gmail.com",
                "Jiye",
                "report@example.com",
                "https://img.example.com/photo.jpg",
                "USER",
                true));

    mockMvc
        .perform(get("/api/users/me"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.id").value(1))
        .andExpect(jsonPath("$.data.email").value("user@gmail.com"))
        .andExpect(jsonPath("$.data.nickname").value("Jiye"))
        .andExpect(jsonPath("$.data.role").value("USER"))
        .andExpect(jsonPath("$.data.onboardingCompleted").value(true));
  }

  @Test
  void getMe_returns404_whenUserNotFound() throws Exception {
    when(currentUserProvider.getCurrentUser()).thenReturn(new AuthenticatedUser(99L));
    when(userService.getMe(99L)).thenThrow(new BusinessException(ErrorCode.USER_NOT_FOUND));

    mockMvc
        .perform(get("/api/users/me"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.success").value(false))
        .andExpect(jsonPath("$.error.code").value("USER_NOT_FOUND"));
  }

  @Test
  void completeOnboarding_returnsOnboardingCompleted() throws Exception {
    when(currentUserProvider.getCurrentUser()).thenReturn(new AuthenticatedUser(1L));
    when(userService.completeOnboarding(eq(1L), any(UpdateOnboardingRequest.class)))
        .thenReturn(new UpdateOnboardingResponse(true));

    mockMvc
        .perform(
            patch("/api/users/me/onboarding")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"Jiye\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true))
        .andExpect(jsonPath("$.data.onboardingCompleted").value(true));
  }

  @Test
  void completeOnboarding_withNullNickname_succeeds() throws Exception {
    when(currentUserProvider.getCurrentUser()).thenReturn(new AuthenticatedUser(1L));
    when(userService.completeOnboarding(eq(1L), any(UpdateOnboardingRequest.class)))
        .thenReturn(new UpdateOnboardingResponse(true));

    mockMvc
        .perform(
            patch("/api/users/me/onboarding").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.onboardingCompleted").value(true));
  }

  @Test
  void deleteAccount_returns200_andClearsCookie() throws Exception {
    when(currentUserProvider.getCurrentUser()).thenReturn(new AuthenticatedUser(1L));
    doNothing().when(userService).deleteAccount(1L);
    when(authService.buildExpiredJwtCookie())
        .thenReturn(ResponseCookie.from("jwt", "").maxAge(0).build());

    mockMvc
        .perform(delete("/api/users/me"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.success").value(true));
  }

  @Test
  void deleteAccount_returns404_whenUserNotFound() throws Exception {
    when(currentUserProvider.getCurrentUser()).thenReturn(new AuthenticatedUser(99L));
    doThrow(new BusinessException(ErrorCode.USER_NOT_FOUND)).when(userService).deleteAccount(99L);

    mockMvc
        .perform(delete("/api/users/me"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error.code").value("USER_NOT_FOUND"));
  }

  @Test
  void completeOnboarding_returns400_whenNicknameTooLong() throws Exception {
    String tooLong = "a".repeat(101);

    mockMvc
        .perform(
            patch("/api/users/me/onboarding")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"nickname\":\"" + tooLong + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
  }

  @Test
  void completeOnboarding_returns400_whenReportEmailInvalid() throws Exception {
    mockMvc
        .perform(
            patch("/api/users/me/onboarding")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reportEmail\":\"not-an-email\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
  }
}
