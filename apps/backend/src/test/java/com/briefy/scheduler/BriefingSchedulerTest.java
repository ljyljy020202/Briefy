package com.briefy.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.briefy.config.EmailProperties;
import com.briefy.domain.briefing.dto.GenerateResult;
import com.briefy.domain.briefing.repository.BriefingReportRepository;
import com.briefy.domain.briefing.service.BriefingService;
import com.briefy.domain.briefingpreference.entity.BriefingCategoryCode;
import com.briefy.domain.briefingpreference.entity.UserBriefingPreference;
import com.briefy.domain.briefingpreference.repository.UserBriefingPreferenceRepository;
import com.briefy.domain.delivery.service.EmailDeliveryService;
import com.briefy.domain.user.entity.User;
import com.briefy.domain.user.repository.UserRepository;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@ExtendWith(MockitoExtension.class)
class BriefingSchedulerTest {

  @Mock private BriefingService briefingService;
  @Mock private UserBriefingPreferenceRepository userBriefingPreferenceRepository;
  @Mock private BriefingReportRepository briefingReportRepository;
  @Mock private EmailDeliveryService emailDeliveryService;
  @Mock private EmailProperties emailProperties;
  @Mock private UserRepository userRepository;

  @InjectMocks private BriefingScheduler scheduler;

  private User subscribedUserMock(Long id) {
    User u = mock(User.class);
    when(u.getId()).thenReturn(id);
    return u;
  }

  @Test
  void runScheduledBriefings_generatesForEachActiveSubscribedUser() {
    User u1 = subscribedUserMock(1L);
    User u2 = subscribedUserMock(2L);
    LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
    UserBriefingPreference pref1 = mock(UserBriefingPreference.class);
    UserBriefingPreference pref2 = mock(UserBriefingPreference.class);
    when(pref1.getUserId()).thenReturn(1L);
    when(pref2.getUserId()).thenReturn(2L);

    when(userBriefingPreferenceRepository.findAllByCategoryCodeAndActiveTrue(
            BriefingCategoryCode.JOB_POSTING))
        .thenReturn(List.of(pref1, pref2));
    when(userRepository.findAllByIdInAndBriefingEmailEnabledTrue(List.of(1L, 2L)))
        .thenReturn(List.of(u1, u2));
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
    User u1 = subscribedUserMock(1L);
    LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
    UserBriefingPreference pref = mock(UserBriefingPreference.class);
    when(pref.getUserId()).thenReturn(1L);

    when(userBriefingPreferenceRepository.findAllByCategoryCodeAndActiveTrue(
            BriefingCategoryCode.JOB_POSTING))
        .thenReturn(List.of(pref));
    when(userRepository.findAllByIdInAndBriefingEmailEnabledTrue(List.of(1L)))
        .thenReturn(List.of(u1));
    when(briefingReportRepository.existsByUserIdAndReportDate(1L, today)).thenReturn(true);

    scheduler.runScheduledBriefings();

    verify(briefingService, never()).generateScheduledBriefing(any());
  }

  @Test
  void runScheduledBriefings_continuesOnPerUserFailure() {
    User u1 = subscribedUserMock(1L);
    User u2 = subscribedUserMock(2L);
    LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
    UserBriefingPreference pref1 = mock(UserBriefingPreference.class);
    UserBriefingPreference pref2 = mock(UserBriefingPreference.class);
    when(pref1.getUserId()).thenReturn(1L);
    when(pref2.getUserId()).thenReturn(2L);

    when(userBriefingPreferenceRepository.findAllByCategoryCodeAndActiveTrue(
            BriefingCategoryCode.JOB_POSTING))
        .thenReturn(List.of(pref1, pref2));
    when(userRepository.findAllByIdInAndBriefingEmailEnabledTrue(List.of(1L, 2L)))
        .thenReturn(List.of(u1, u2));
    when(briefingReportRepository.existsByUserIdAndReportDate(any(), eq(today))).thenReturn(false);
    when(briefingService.generateScheduledBriefing(1L))
        .thenThrow(new RuntimeException("agent error"));
    when(briefingService.generateScheduledBriefing(2L))
        .thenReturn(new GenerateResult(10L, 20L, "COMPLETED"));

    scheduler.runScheduledBriefings();

    verify(briefingService).generateScheduledBriefing(1L);
    verify(briefingService).generateScheduledBriefing(2L);
  }

  @Test
  void runScheduledBriefings_callsAutoEmailDelivery_whenAutoSendEnabled() {
    User u1 = subscribedUserMock(1L);
    LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
    UserBriefingPreference pref = mock(UserBriefingPreference.class);
    when(pref.getUserId()).thenReturn(1L);

    when(userBriefingPreferenceRepository.findAllByCategoryCodeAndActiveTrue(
            BriefingCategoryCode.JOB_POSTING))
        .thenReturn(List.of(pref));
    when(userRepository.findAllByIdInAndBriefingEmailEnabledTrue(List.of(1L)))
        .thenReturn(List.of(u1));
    when(briefingReportRepository.existsByUserIdAndReportDate(1L, today)).thenReturn(false);
    when(briefingService.generateScheduledBriefing(1L))
        .thenReturn(new GenerateResult(10L, 20L, "COMPLETED"));
    when(emailProperties.autoSendEnabled()).thenReturn(true);

    scheduler.runScheduledBriefings();

    verify(emailDeliveryService).autoDeliverBriefingReport(10L);
    verify(emailDeliveryService, never()).deliverBriefingReport(any());
  }

  @Test
  void runScheduledBriefings_skipsEmailDelivery_whenAutoSendDisabled() {
    User u1 = subscribedUserMock(1L);
    LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
    UserBriefingPreference pref = mock(UserBriefingPreference.class);
    when(pref.getUserId()).thenReturn(1L);

    when(userBriefingPreferenceRepository.findAllByCategoryCodeAndActiveTrue(
            BriefingCategoryCode.JOB_POSTING))
        .thenReturn(List.of(pref));
    when(userRepository.findAllByIdInAndBriefingEmailEnabledTrue(List.of(1L)))
        .thenReturn(List.of(u1));
    when(briefingReportRepository.existsByUserIdAndReportDate(1L, today)).thenReturn(false);
    when(briefingService.generateScheduledBriefing(1L))
        .thenReturn(new GenerateResult(10L, 20L, "COMPLETED"));
    when(emailProperties.autoSendEnabled()).thenReturn(false);

    scheduler.runScheduledBriefings();

    verify(emailDeliveryService, never()).autoDeliverBriefingReport(any());
    verify(emailDeliveryService, never()).deliverBriefingReport(any());
  }

  @Test
  void runScheduledBriefings_continuesWhenEmailDeliveryFails() {
    User u1 = subscribedUserMock(1L);
    User u2 = subscribedUserMock(2L);
    LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
    UserBriefingPreference pref1 = mock(UserBriefingPreference.class);
    UserBriefingPreference pref2 = mock(UserBriefingPreference.class);
    when(pref1.getUserId()).thenReturn(1L);
    when(pref2.getUserId()).thenReturn(2L);

    when(userBriefingPreferenceRepository.findAllByCategoryCodeAndActiveTrue(
            BriefingCategoryCode.JOB_POSTING))
        .thenReturn(List.of(pref1, pref2));
    when(userRepository.findAllByIdInAndBriefingEmailEnabledTrue(List.of(1L, 2L)))
        .thenReturn(List.of(u1, u2));
    when(briefingReportRepository.existsByUserIdAndReportDate(any(), eq(today))).thenReturn(false);
    when(briefingService.generateScheduledBriefing(1L))
        .thenReturn(new GenerateResult(10L, 20L, "COMPLETED"));
    when(briefingService.generateScheduledBriefing(2L))
        .thenReturn(new GenerateResult(11L, 21L, "COMPLETED"));
    when(emailProperties.autoSendEnabled()).thenReturn(true);
    when(emailDeliveryService.autoDeliverBriefingReport(10L))
        .thenThrow(new RuntimeException("mail server down"));

    scheduler.runScheduledBriefings();

    verify(emailDeliveryService).autoDeliverBriefingReport(10L);
    verify(emailDeliveryService).autoDeliverBriefingReport(11L);
  }

  @Test
  void runScheduledBriefings_skipsAllWhenNoSubscribedUsers() {
    UserBriefingPreference pref = mock(UserBriefingPreference.class);
    when(pref.getUserId()).thenReturn(1L);

    when(userBriefingPreferenceRepository.findAllByCategoryCodeAndActiveTrue(
            BriefingCategoryCode.JOB_POSTING))
        .thenReturn(List.of(pref));
    when(userRepository.findAllByIdInAndBriefingEmailEnabledTrue(anyCollection()))
        .thenReturn(List.of());

    scheduler.runScheduledBriefings();

    verify(briefingService, never()).generateScheduledBriefing(any());
    verify(emailDeliveryService, never()).autoDeliverBriefingReport(any());
  }

  @Test
  void runScheduledBriefings_excludesUnsubscribedUser() {
    User u2 = subscribedUserMock(2L);
    LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
    UserBriefingPreference pref1 = mock(UserBriefingPreference.class);
    UserBriefingPreference pref2 = mock(UserBriefingPreference.class);
    when(pref1.getUserId()).thenReturn(1L);
    when(pref2.getUserId()).thenReturn(2L);

    when(userBriefingPreferenceRepository.findAllByCategoryCodeAndActiveTrue(
            BriefingCategoryCode.JOB_POSTING))
        .thenReturn(List.of(pref1, pref2));
    when(userRepository.findAllByIdInAndBriefingEmailEnabledTrue(List.of(1L, 2L)))
        .thenReturn(List.of(u2));
    when(briefingReportRepository.existsByUserIdAndReportDate(2L, today)).thenReturn(false);
    when(briefingService.generateScheduledBriefing(2L))
        .thenReturn(new GenerateResult(11L, 21L, "COMPLETED"));

    scheduler.runScheduledBriefings();

    verify(briefingService, never()).generateScheduledBriefing(1L);
    verify(briefingService).generateScheduledBriefing(2L);
  }

  @Test
  void schedulerBeanIsConditionalOnSchedulerEnabled() {
    ConditionalOnProperty annotation =
        BriefingScheduler.class.getAnnotation(ConditionalOnProperty.class);
    assertThat(annotation).isNotNull();
    assertThat(annotation.name()).containsExactly("briefy.scheduler.enabled");
    assertThat(annotation.havingValue()).isEqualTo("true");
  }
}
