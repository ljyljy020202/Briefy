package com.briefy.domain.preference.service;

import com.briefy.domain.preference.dto.BriefingPreferenceResponse;
import com.briefy.domain.preference.dto.PatchBriefingPreferenceRequest;
import com.briefy.domain.preference.dto.UpsertBriefingPreferenceRequest;
import com.briefy.domain.preference.entity.BriefingCategory;
import com.briefy.domain.preference.entity.UserBriefingPreference;
import com.briefy.domain.preference.repository.BriefingCategoryRepository;
import com.briefy.domain.preference.repository.UserBriefingPreferenceRepository;
import com.briefy.global.exception.BusinessException;
import com.briefy.global.exception.ErrorCode;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class UserBriefingPreferenceService {

  private final UserBriefingPreferenceRepository preferenceRepository;
  private final BriefingCategoryRepository categoryRepository;

  public UserBriefingPreferenceService(
      UserBriefingPreferenceRepository preferenceRepository,
      BriefingCategoryRepository categoryRepository) {
    this.preferenceRepository = preferenceRepository;
    this.categoryRepository = categoryRepository;
  }

  public List<BriefingPreferenceResponse> getMyPreferences(Long userId) {
    return preferenceRepository.findAllByUserIdAndActiveTrue(userId).stream()
        .map(BriefingPreferenceResponse::from)
        .toList();
  }

  @Transactional
  public BriefingPreferenceResponse upsertPreference(
      Long userId, UpsertBriefingPreferenceRequest request) {
    BriefingCategory category =
        categoryRepository
            .findByIdAndActiveTrue(request.categoryId())
            .orElseThrow(() -> new BusinessException(ErrorCode.BRIEFING_CATEGORY_NOT_FOUND));

    return preferenceRepository
        .findByUserIdAndCategoryId(userId, category.getId())
        .map(
            existing -> {
              if (existing.isActive()) {
                throw new BusinessException(ErrorCode.DUPLICATE_BRIEFING_PREFERENCE);
              }
              existing.reactivate(request.preference());
              return BriefingPreferenceResponse.from(existing);
            })
        .orElseGet(
            () -> {
              UserBriefingPreference created =
                  preferenceRepository.save(
                      UserBriefingPreference.create(userId, category, request.preference()));
              return BriefingPreferenceResponse.from(created);
            });
  }

  @Transactional
  public BriefingPreferenceResponse patchPreference(
      Long userId, Long preferenceId, PatchBriefingPreferenceRequest request) {
    UserBriefingPreference pref =
        preferenceRepository
            .findById(preferenceId)
            .orElseThrow(() -> new BusinessException(ErrorCode.BRIEFING_PREFERENCE_NOT_FOUND));

    if (!pref.getUserId().equals(userId)) {
      throw new BusinessException(ErrorCode.FORBIDDEN);
    }

    pref.mergePatch(request.preference());
    return BriefingPreferenceResponse.from(pref);
  }

  @Transactional
  public void deletePreference(Long userId, Long preferenceId) {
    UserBriefingPreference pref =
        preferenceRepository
            .findById(preferenceId)
            .orElseThrow(() -> new BusinessException(ErrorCode.BRIEFING_PREFERENCE_NOT_FOUND));

    if (!pref.getUserId().equals(userId)) {
      throw new BusinessException(ErrorCode.FORBIDDEN);
    }

    pref.deactivate();
  }
}
