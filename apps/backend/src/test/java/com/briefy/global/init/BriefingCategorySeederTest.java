package com.briefy.global.init;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.briefy.domain.preference.entity.BriefingCategory;
import com.briefy.domain.preference.entity.BriefingCategoryCode;
import com.briefy.domain.preference.repository.BriefingCategoryRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BriefingCategorySeederTest {

  @Mock private BriefingCategoryRepository briefingCategoryRepository;

  @InjectMocks private BriefingCategorySeeder seeder;

  @Test
  void seed_insertsAllThreeCategories_whenNoneExist() {
    when(briefingCategoryRepository.findByCode(any(BriefingCategoryCode.class)))
        .thenReturn(Optional.empty());

    seeder.seed();

    verify(briefingCategoryRepository, times(3)).save(any(BriefingCategory.class));
  }

  @Test
  void seed_skipsExisting_andInsertsNew() {
    when(briefingCategoryRepository.findByCode(BriefingCategoryCode.JOB_POSTING))
        .thenReturn(Optional.of(mock(BriefingCategory.class)));
    when(briefingCategoryRepository.findByCode(BriefingCategoryCode.COMPANY_NEWS))
        .thenReturn(Optional.empty());
    when(briefingCategoryRepository.findByCode(BriefingCategoryCode.INDUSTRY_TREND))
        .thenReturn(Optional.empty());

    seeder.seed();

    verify(briefingCategoryRepository, times(2)).save(any(BriefingCategory.class));
  }

  @Test
  void seed_doesNothing_whenAllExist() {
    when(briefingCategoryRepository.findByCode(any(BriefingCategoryCode.class)))
        .thenReturn(Optional.of(mock(BriefingCategory.class)));

    seeder.seed();

    verify(briefingCategoryRepository, times(0)).save(any(BriefingCategory.class));
  }

  @Test
  void seed_jobPostingIsActive_companyNewsAndIndustryTrendAreInactive() {
    when(briefingCategoryRepository.findByCode(any(BriefingCategoryCode.class)))
        .thenReturn(Optional.empty());

    seeder.seed();

    ArgumentCaptor<BriefingCategory> captor = ArgumentCaptor.forClass(BriefingCategory.class);
    verify(briefingCategoryRepository, times(3)).save(captor.capture());

    BriefingCategory jobPosting =
        captor.getAllValues().stream()
            .filter(c -> c.getCode() == BriefingCategoryCode.JOB_POSTING)
            .findFirst()
            .orElseThrow();
    BriefingCategory companyNews =
        captor.getAllValues().stream()
            .filter(c -> c.getCode() == BriefingCategoryCode.COMPANY_NEWS)
            .findFirst()
            .orElseThrow();

    assertThat(jobPosting.isActive()).isTrue();
    assertThat(companyNews.isActive()).isFalse();
  }
}
