package com.briefy.domain.user.service;

import com.briefy.domain.briefing.entity.BriefingReport;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class UserService {

  private final UserRepository userRepository;
  private final UserBriefingPreferenceRepository userBriefingPreferenceRepository;
  private final BriefingReportRepository briefingReportRepository;
  private final BriefingJobRepository briefingJobRepository;

  public UserService(
      UserRepository userRepository,
      UserBriefingPreferenceRepository userBriefingPreferenceRepository,
      BriefingReportRepository briefingReportRepository,
      BriefingJobRepository briefingJobRepository) {
    this.userRepository = userRepository;
    this.userBriefingPreferenceRepository = userBriefingPreferenceRepository;
    this.briefingReportRepository = briefingReportRepository;
    this.briefingJobRepository = briefingJobRepository;
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
    if (request.reportEmail() != null && request.reportEmail().isBlank()) {
      throw new BusinessException(ErrorCode.VALIDATION_ERROR, "reportEmail must not be blank");
    }
    User user =
        userRepository
            .findById(userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
    user.completeOnboarding(request.nickname(), request.reportEmail());
    return new UpdateOnboardingResponse(user.isOnboardingCompleted());
  }

  @Transactional
  public void deleteAccount(Long userId) {
    if (!userRepository.existsById(userId)) {
      throw new BusinessException(ErrorCode.USER_NOT_FOUND);
    }
    userBriefingPreferenceRepository.deleteAllByUserId(userId);
    List<BriefingReport> reports = briefingReportRepository.findAllByUserId(userId);
    briefingReportRepository.deleteAll(reports);
    briefingJobRepository.deleteAllByUserId(userId);
    userRepository.deleteById(userId);
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
