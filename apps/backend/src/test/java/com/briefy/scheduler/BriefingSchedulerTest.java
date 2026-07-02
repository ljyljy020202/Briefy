package com.briefy.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.briefy.domain.briefing.dto.GenerateResult;
import com.briefy.domain.briefing.repository.BriefingReportRepository;
import com.briefy.domain.briefing.service.BriefingService;
import com.briefy.domain.briefingpreference.entity.BriefingCategoryCode;
import com.briefy.domain.briefingpreference.entity.UserBriefingPreference;
import com.briefy.domain.briefingpreference.repository.UserBriefingPreferenceRepository;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BriefingSchedulerTest {

  @Mock private BriefingService briefingService;
  @Mock private UserBriefingPreferenceRepository userBriefingPreferenceRepository;
  @Mock private BriefingReportRepository briefingReportRepository;

  @InjectMocks private BriefingScheduler scheduler;

  @Test
  void runScheduledBriefings_generatesForEachActiveUser() {
    LocalDate today = LocalDate.now();
    UserBriefingPreference pref1 = mock(UserBriefingPreference.class);
    UserBriefingPreference pref2 = mock(UserBriefingPreference.class);
    when(pref1.getUserId()).thenReturn(1L);
    when(pref2.getUserId()).thenReturn(2L);

    when(userBriefingPreferenceRepository.findAllByCategoryCodeAndActiveTrue(
            BriefingCategoryCode.JOB_POSTING))
        .thenReturn(List.of(pref1, pref2));
    when(briefingReportRepository.existsByUserIdAndReportDate(any(), eq(today))).thenReturn(false);
    when(briefingService.generateScheduledBriefing(any()))
        .thenReturn(new GenerateResult(10L, 20L, "COMPLETED"));

    scheduler.runScheduledBriefings();

    verify(briefingService, times(2)).generateScheduledBriefing(any());
    verify(briefingService).generateScheduledBriefing(1L);
    verify(briefingService).generateScheduledBriefing(2L);
  }

  @Test
  void runScheduledBriefings_skipsUserWithExistingReport() {
    LocalDate today = LocalDate.now();
    UserBriefingPreference pref = mock(UserBriefingPreference.class);
    when(pref.getUserId()).thenReturn(1L);

    when(userBriefingPreferenceRepository.findAllByCategoryCodeAndActiveTrue(
            BriefingCategoryCode.JOB_POSTING))
        .thenReturn(List.of(pref));
    when(briefingReportRepository.existsByUserIdAndReportDate(1L, today)).thenReturn(true);

    scheduler.runScheduledBriefings();

    verify(briefingService, never()).generateScheduledBriefing(any());
  }

  @Test
  void runScheduledBriefings_continuesOnPerUserFailure() {
    LocalDate today = LocalDate.now();
    UserBriefingPreference pref1 = mock(UserBriefingPreference.class);
    UserBriefingPreference pref2 = mock(UserBriefingPreference.class);
    when(pref1.getUserId()).thenReturn(1L);
    when(pref2.getUserId()).thenReturn(2L);

    when(userBriefingPreferenceRepository.findAllByCategoryCodeAndActiveTrue(
            BriefingCategoryCode.JOB_POSTING))
        .thenReturn(List.of(pref1, pref2));
    when(briefingReportRepository.existsByUserIdAndReportDate(any(), eq(today))).thenReturn(false);
    when(briefingService.generateScheduledBriefing(1L))
        .thenThrow(new RuntimeException("agent error"));
    when(briefingService.generateScheduledBriefing(2L))
        .thenReturn(new GenerateResult(10L, 20L, "COMPLETED"));

    scheduler.runScheduledBriefings();

    verify(briefingService).generateScheduledBriefing(1L);
    verify(briefingService).generateScheduledBriefing(2L);
  }
}
