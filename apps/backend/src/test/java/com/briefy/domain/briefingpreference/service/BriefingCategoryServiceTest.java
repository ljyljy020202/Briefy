package com.briefy.domain.briefingpreference.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.briefy.domain.briefingpreference.dto.BriefingCategoryResponse;
import com.briefy.domain.briefingpreference.entity.BriefingCategory;
import com.briefy.domain.briefingpreference.entity.BriefingCategoryCode;
import com.briefy.domain.briefingpreference.repository.BriefingCategoryRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BriefingCategoryServiceTest {

  @Mock private BriefingCategoryRepository briefingCategoryRepository;

  @InjectMocks private BriefingCategoryService briefingCategoryService;

  @Test
  void getActiveCategories_returnsActiveCategoriesInOrder() {
    BriefingCategory cat = mock(BriefingCategory.class);
    when(cat.getId()).thenReturn(1L);
    when(cat.getCode()).thenReturn(BriefingCategoryCode.JOB_POSTING);
    when(cat.getDisplayName()).thenReturn("채용 브리핑");
    when(cat.getPhase()).thenReturn("1st");
    when(cat.isActive()).thenReturn(true);

    when(briefingCategoryRepository.findAllByActiveTrueOrderByIdAsc()).thenReturn(List.of(cat));

    List<BriefingCategoryResponse> result = briefingCategoryService.getActiveCategories();

    assertThat(result).hasSize(1);
    assertThat(result.get(0).code()).isEqualTo("JOB_POSTING");
    assertThat(result.get(0).displayName()).isEqualTo("채용 브리핑");
    assertThat(result.get(0).phase()).isEqualTo("1st");
    assertThat(result.get(0).active()).isTrue();
  }

  @Test
  void getActiveCategories_returnsEmpty_whenNoneActive() {
    when(briefingCategoryRepository.findAllByActiveTrueOrderByIdAsc()).thenReturn(List.of());

    List<BriefingCategoryResponse> result = briefingCategoryService.getActiveCategories();

    assertThat(result).isEmpty();
  }
}
