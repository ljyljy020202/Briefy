package com.briefy.domain.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.briefy.domain.briefing.repository.BriefingJobRepository;
import com.briefy.domain.briefing.repository.BriefingReportRepository;
import com.briefy.domain.briefingpreference.repository.UserBriefingPreferenceRepository;
import com.briefy.domain.user.dto.UpdateOnboardingRequest;
import com.briefy.domain.user.dto.UpdateOnboardingResponse;
import com.briefy.domain.user.dto.UserMeResponse;
import com.briefy.domain.user.entity.AuthProvider;
import com.briefy.domain.user.entity.User;
import com.briefy.domain.user.repository.UserRepository;
import com.briefy.global.exception.BusinessException;
import com.briefy.global.exception.ErrorCode;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

  @Mock private UserRepository userRepository;
  @Mock private UserBriefingPreferenceRepository userBriefingPreferenceRepository;
  @Mock private BriefingReportRepository briefingReportRepository;
  @Mock private BriefingJobRepository briefingJobRepository;

  @InjectMocks private UserService userService;

  private User sampleUser() {
    return User.create(
        "user@gmail.com",
        "Jiye",
        "https://img.example.com/photo.jpg",
        AuthProvider.GOOGLE,
        "google-sub-123");
  }

  @Test
  void getMe_returnsUserMeResponse() {
    User user = sampleUser();
    when(userRepository.findById(1L)).thenReturn(Optional.of(user));

    UserMeResponse response = userService.getMe(1L);

    assertThat(response.email()).isEqualTo("user@gmail.com");
    assertThat(response.nickname()).isEqualTo("Jiye");
    assertThat(response.role()).isEqualTo("USER");
    assertThat(response.onboardingCompleted()).isFalse();
  }

  @Test
  void getMe_throwsUserNotFound_whenUserMissing() {
    when(userRepository.findById(99L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> userService.getMe(99L))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            ex ->
                assertThat(((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.USER_NOT_FOUND));
  }

  @Test
  void completeOnboarding_setsNicknameAndCompletedFlag() {
    User user = sampleUser();
    when(userRepository.findById(1L)).thenReturn(Optional.of(user));

    UpdateOnboardingResponse response =
        userService.completeOnboarding(1L, new UpdateOnboardingRequest("NewNick"));

    assertThat(response.onboardingCompleted()).isTrue();
    assertThat(user.getNickname()).isEqualTo("NewNick");
    assertThat(user.isOnboardingCompleted()).isTrue();
  }

  @Test
  void completeOnboarding_withNullNickname_doesNotOverrideExistingNickname() {
    User user = sampleUser();
    when(userRepository.findById(1L)).thenReturn(Optional.of(user));

    UpdateOnboardingResponse response =
        userService.completeOnboarding(1L, new UpdateOnboardingRequest(null));

    assertThat(response.onboardingCompleted()).isTrue();
    assertThat(user.getNickname()).isEqualTo("Jiye");
  }

  @Test
  void completeOnboarding_withBlankNickname_throwsValidationError() {
    assertThatThrownBy(() -> userService.completeOnboarding(1L, new UpdateOnboardingRequest("  ")))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            ex ->
                assertThat(((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.VALIDATION_ERROR));

    verify(userRepository, never()).findById(any());
  }

  @Test
  void completeOnboarding_throwsUserNotFound_whenUserMissing() {
    when(userRepository.findById(99L)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () -> userService.completeOnboarding(99L, new UpdateOnboardingRequest("Nick")))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            ex ->
                assertThat(((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.USER_NOT_FOUND));
  }

  @Test
  void deleteAccount_deletesAllUserDataAndUser() {
    when(userRepository.existsById(1L)).thenReturn(true);
    when(briefingReportRepository.findAllByUserId(1L)).thenReturn(List.of());

    userService.deleteAccount(1L);

    verify(userBriefingPreferenceRepository).deleteAllByUserId(1L);
    verify(briefingReportRepository).deleteAll(List.of());
    verify(briefingJobRepository).deleteAllByUserId(1L);
    verify(userRepository).deleteById(1L);
  }

  @Test
  void deleteAccount_throwsUserNotFound_whenUserMissing() {
    when(userRepository.existsById(99L)).thenReturn(false);

    assertThatThrownBy(() -> userService.deleteAccount(99L))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            ex ->
                assertThat(((BusinessException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.USER_NOT_FOUND));

    verify(userRepository, never()).deleteById(any());
  }

  @Test
  void findOrCreate_returnsExistingUser_whenFound() {
    User existing = sampleUser();
    when(userRepository.findByProviderAndProviderId(AuthProvider.GOOGLE, "google-sub-123"))
        .thenReturn(Optional.of(existing));

    User result =
        userService.findOrCreate(
            "user@gmail.com",
            AuthProvider.GOOGLE,
            "google-sub-123",
            "Jiye",
            "https://img.example.com/photo.jpg");

    assertThat(result).isSameAs(existing);
    verify(userRepository, never()).save(any());
  }

  @Test
  void findOrCreate_createsNewUser_whenNotFound() {
    User newUser =
        User.create(
            "new@gmail.com",
            "New",
            "https://img.example.com/new.jpg",
            AuthProvider.GOOGLE,
            "google-sub-456");
    when(userRepository.findByProviderAndProviderId(AuthProvider.GOOGLE, "google-sub-456"))
        .thenReturn(Optional.empty());
    when(userRepository.save(any(User.class))).thenReturn(newUser);

    User result =
        userService.findOrCreate(
            "new@gmail.com",
            AuthProvider.GOOGLE,
            "google-sub-456",
            "New",
            "https://img.example.com/new.jpg");

    assertThat(result.getEmail()).isEqualTo("new@gmail.com");
    verify(userRepository).save(any(User.class));
  }
}
