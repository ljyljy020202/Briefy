package com.briefy.domain.user.service;

import com.briefy.domain.user.dto.UpdateOnboardingRequest;
import com.briefy.domain.user.dto.UpdateOnboardingResponse;
import com.briefy.domain.user.dto.UserMeResponse;
import com.briefy.domain.user.entity.AuthProvider;
import com.briefy.domain.user.entity.User;
import com.briefy.domain.user.repository.UserRepository;
import com.briefy.global.exception.BusinessException;
import com.briefy.global.exception.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class UserService {

  private final UserRepository userRepository;

  public UserService(UserRepository userRepository) {
    this.userRepository = userRepository;
  }

  public UserMeResponse getMe(Long userId) {
    User user =
        userRepository
            .findById(userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    return UserMeResponse.from(user);
  }

  @Transactional
  public UpdateOnboardingResponse completeOnboarding(Long userId, UpdateOnboardingRequest request) {
    if (request.nickname() != null && request.nickname().isBlank()) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "nickname must not be blank");
    }
    User user =
        userRepository
            .findById(userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    user.completeOnboarding(request.nickname());
    return new UpdateOnboardingResponse(user.isOnboardingCompleted());
  }

  @Transactional
  public User findOrCreate(
      String email,
      AuthProvider provider,
      String providerId,
      String nickname,
      String profileImageUrl) {
    return userRepository
        .findByProviderAndProviderId(provider, providerId)
        .orElseGet(
            () ->
                userRepository.save(
                    User.create(email, nickname, profileImageUrl, provider, providerId)));
  }
}
