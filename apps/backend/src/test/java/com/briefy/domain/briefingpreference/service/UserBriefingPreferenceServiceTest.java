package com.briefy.domain.briefingpreference.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.briefy.domain.briefingpreference.dto.BriefingPreferenceResponse;
import com.briefy.domain.briefingpreference.dto.PatchBriefingPreferenceRequest;
import com.briefy.domain.briefingpreference.dto.UpsertBriefingPreferenceRequest;
import com.briefy.domain.briefingpreference.entity.BriefingCategory;
import com.briefy.domain.briefingpreference.entity.BriefingCategoryCode;
import com.briefy.domain.briefingpreference.entity.UserBriefingPreference;
import com.briefy.domain.briefingpreference.repository.BriefingCategoryRepository;
import com.briefy.domain.briefingpreference.repository.UserBriefingPreferenceRepository;
import com.briefy.global.exception.BusinessException;
import com.briefy.global.exception.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserBriefingPreferenceServiceTest {

  @Mock private UserBriefingPreferenceRepository preferenceRepository;
  @Mock private BriefingCategoryRepository categoryRepository;

  @InjectMocks private UserBriefingPreferenceService preferenceService;

  private BriefingCategory mockCategory;

  @BeforeEach
  void setUp() {
    mockCategory = mock(BriefingCategory.class);
    when(mockCategory.getId()).thenReturn(1L);
    when(mockCategory.getCode()).thenReturn(BriefingCategoryCode.JOB_POSTING);
    when(mockCategory.getDisplayName()).thenReturn("채용 브리핑");
  }

  @Test
  void getMyPreferences_returnsActivePreferences() {
    UserBriefingPreference pref = mock(UserBriefingPreference.class);
    when(pref.getId()).thenReturn(1L);
    when(pref.getCategory()).thenReturn(mockCategory);
    when(pref.getPreference()).thenReturn(Map.of("roles", List.of("백엔드 개발자")));
    when(pref.isActive()).thenReturn(true);
    when(pref.getCreatedAt()).thenReturn(null);
    when(pref.getUpdatedAt()).thenReturn(null);

    when(preferenceRepository.findAllByUserIdAndActiveTrue(1L)).thenReturn(List.of(pref));

    List<BriefingPreferenceResponse> result = preferenceService.getMyPreferences(1L);

    assertThat(result).hasSize(1);
    assertThat(result.get(0).categoryCode()).isEqualTo("JOB_POSTING");
    assertThat(result.get(0).preference()).containsKey("roles");
  }

  @Test
  void upsertPreference_createsNew_whenNoneExists() {
    Map<String, Object> preference = Map.of("roles", List.of("백엔드 개발자"));
    UpsertBriefingPreferenceRequest request = new UpsertBriefingPreferenceRequest(1L, preference);

    when(categoryRepository.findByIdAndActiveTrue(1L)).thenReturn(Optional.of(mockCategory));
    when(preferenceRepository.findByUserIdAndCategoryId(10L, 1L)).thenReturn(Optional.empty());

    UserBriefingPreference saved = mock(UserBriefingPreference.class);
    when(saved.getId()).thenReturn(1L);
    when(saved.getCategory()).thenReturn(mockCategory);
    when(saved.getPreference()).thenReturn(preference);
    when(saved.isActive()).thenReturn(true);
    when(saved.getCreatedAt()).thenReturn(null);
    when(saved.getUpdatedAt()).thenReturn(null);
    when(preferenceRepository.save(any(UserBriefingPreference.class))).thenReturn(saved);

    BriefingPreferenceResponse result = preferenceService.upsertPreference(10L, request);

    assertThat(result.categoryCode()).isEqualTo("JOB_POSTING");
    verify(preferenceRepository).save(any(UserBriefingPreference.class));
  }

  @Test
  void upsertPreference_reactivates_whenInactiveExists() {
    Map<String, Object> preference = Map.of("roles", List.of("풀스택 개발자"));
    UpsertBriefingPreferenceRequest request = new UpsertBriefingPreferenceRequest(1L, preference);

    when(categoryRepository.findByIdAndActiveTrue(1L)).thenReturn(Optional.of(mockCategory));

    UserBriefingPreference existing = mock(UserBriefingPreference.class);
    when(existing.isActive()).thenReturn(false);
    when(existing.getCategory()).thenReturn(mockCategory);
    when(existing.getPreference()).thenReturn(preference);
    when(existing.getCreatedAt()).thenReturn(null);
    when(existing.getUpdatedAt()).thenReturn(null);
    when(preferenceRepository.findByUserIdAndCategoryId(10L, 1L)).thenReturn(Optional.of(existing));

    BriefingPreferenceResponse result = preferenceService.upsertPreference(10L, request);

    verify(existing).reactivate(preference);
    assertThat(result.categoryCode()).isEqualTo("JOB_POSTING");
  }

  @Test
  void upsertPreference_throwsDuplicate_whenActiveExists() {
    UpsertBriefingPreferenceRequest request =
        new UpsertBriefingPreferenceRequest(1L, Map.of("roles", List.of("백엔드 개발자")));

    when(categoryRepository.findByIdAndActiveTrue(1L)).thenReturn(Optional.of(mockCategory));

    UserBriefingPreference existing = mock(UserBriefingPreference.class);
    when(existing.isActive()).thenReturn(true);
    when(preferenceRepository.findByUserIdAndCategoryId(10L, 1L)).thenReturn(Optional.of(existing));

    assertThatThrownBy(() -> preferenceService.upsertPreference(10L, request))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            e ->
                assertThat(((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.DUPLICATE_BRIEFING_PREFERENCE));
  }

  @Test
  void upsertPreference_throwsCategoryNotFound_whenCategoryInactive() {
    UpsertBriefingPreferenceRequest request = new UpsertBriefingPreferenceRequest(99L, Map.of());

    when(categoryRepository.findByIdAndActiveTrue(99L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> preferenceService.upsertPreference(10L, request))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            e ->
                assertThat(((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.BRIEFING_CATEGORY_NOT_FOUND));
  }

  @Test
  void patchPreference_mergesPatch_whenOwner() {
    UserBriefingPreference pref = mock(UserBriefingPreference.class);
    when(pref.getUserId()).thenReturn(10L);
    when(pref.getCategory()).thenReturn(mockCategory);
    when(pref.getPreference()).thenReturn(Map.of("roles", List.of("백엔드 개발자")));
    when(pref.isActive()).thenReturn(true);
    when(pref.getCreatedAt()).thenReturn(null);
    when(pref.getUpdatedAt()).thenReturn(null);
    when(preferenceRepository.findById(1L)).thenReturn(Optional.of(pref));

    PatchBriefingPreferenceRequest request =
        new PatchBriefingPreferenceRequest(Map.of("companies", List.of("네이버")));

    BriefingPreferenceResponse result = preferenceService.patchPreference(10L, 1L, request);

    verify(pref).mergePatch(Map.of("companies", List.of("네이버")));
    assertThat(result.categoryCode()).isEqualTo("JOB_POSTING");
  }

  @Test
  void patchPreference_throwsNotFound_whenMissing() {
    when(preferenceRepository.findById(99L)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                preferenceService.patchPreference(
                    10L, 99L, new PatchBriefingPreferenceRequest(Map.of())))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            e ->
                assertThat(((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.BRIEFING_PREFERENCE_NOT_FOUND));
  }

  @Test
  void patchPreference_throwsForbidden_whenNotOwner() {
    UserBriefingPreference pref = mock(UserBriefingPreference.class);
    when(pref.getUserId()).thenReturn(99L);
    when(preferenceRepository.findById(1L)).thenReturn(Optional.of(pref));

    assertThatThrownBy(
            () ->
                preferenceService.patchPreference(
                    10L, 1L, new PatchBriefingPreferenceRequest(Map.of())))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
  }

  @Test
  void deletePreference_deactivates_whenOwner() {
    UserBriefingPreference pref = mock(UserBriefingPreference.class);
    when(pref.getUserId()).thenReturn(10L);
    when(preferenceRepository.findById(1L)).thenReturn(Optional.of(pref));

    preferenceService.deletePreference(10L, 1L);

    verify(pref).deactivate();
  }

  @Test
  void deletePreference_throwsNotFound_whenMissing() {
    when(preferenceRepository.findById(99L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> preferenceService.deletePreference(10L, 99L))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            e ->
                assertThat(((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.BRIEFING_PREFERENCE_NOT_FOUND));
  }

  @Test
  void deletePreference_throwsForbidden_whenNotOwner() {
    UserBriefingPreference pref = mock(UserBriefingPreference.class);
    when(pref.getUserId()).thenReturn(99L);
    when(preferenceRepository.findById(1L)).thenReturn(Optional.of(pref));

    assertThatThrownBy(() -> preferenceService.deletePreference(10L, 1L))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(ErrorCode.FORBIDDEN));
  }
}
