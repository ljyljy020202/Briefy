package com.briefy.domain.dashboard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.briefy.domain.briefing.entity.BriefingReport;
import com.briefy.domain.briefing.repository.BriefingReportRepository;
import com.briefy.domain.dashboard.dto.DashboardResponse;
import com.briefy.domain.preference.entity.BriefingCategory;
import com.briefy.domain.preference.entity.BriefingCategoryCode;
import com.briefy.domain.preference.entity.UserBriefingPreference;
import com.briefy.domain.preference.repository.UserBriefingPreferenceRepository;
import com.briefy.domain.user.entity.User;
import com.briefy.domain.user.repository.UserRepository;
import com.briefy.global.exception.BusinessException;
import com.briefy.global.exception.ErrorCode;
import java.time.LocalDate;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DashboardServiceTest {

  @Mock private UserRepository userRepository;
  @Mock private UserBriefingPreferenceRepository userBriefingPreferenceRepository;
  @Mock private BriefingReportRepository briefingReportRepository;

  @InjectMocks private DashboardService dashboardService;

  private User mockUser;

  @BeforeEach
  void setUp() {
    mockUser = mock(User.class);
    when(mockUser.getNickname()).thenReturn("테스터");
    when(mockUser.getEmail()).thenReturn("test@example.com");
    when(mockUser.isOnboardingCompleted()).thenReturn(true);
  }

  @Test
  void getDashboard_success_returnsUserAndPreferencesAndRecentReports() {
    when(userRepository.findById(1L)).thenReturn(Optional.of(mockUser));

    BriefingCategory category = mock(BriefingCategory.class);
    when(category.getCode()).thenReturn(BriefingCategoryCode.JOB_POSTING);
    when(category.getDisplayName()).thenReturn("채용 브리핑");

    UserBriefingPreference pref = mock(UserBriefingPreference.class);
    when(pref.getCategory()).thenReturn(category);
    when(pref.getPreference()).thenReturn(Map.of("roles", List.of("백엔드 개발자")));

    when(userBriefingPreferenceRepository.findAllByUserIdAndActiveTrue(1L))
        .thenReturn(List.of(pref));

    BriefingReport report = mock(BriefingReport.class);
    when(report.getId()).thenReturn(1L);
    when(report.getTitle()).thenReturn("오늘의 채용 브리핑");
    when(report.getSummary()).thenReturn("요약");
    when(report.getReportDate()).thenReturn(LocalDate.of(2026, 6, 26));
    when(report.getArticleCount()).thenReturn(5);
    when(report.getCreatedAt()).thenReturn(null);
    Page<BriefingReport> page = new PageImpl<>(List.of(report));
    when(briefingReportRepository.findAllByUserIdOrderByReportDateDesc(eq(1L), any(Pageable.class)))
        .thenReturn(page);

    DashboardResponse response = dashboardService.getDashboard(1L);

    assertThat(response.user().nickname()).isEqualTo("테스터");
    assertThat(response.user().email()).isEqualTo("test@example.com");
    assertThat(response.user().onboardingCompleted()).isTrue();
    assertThat(response.briefingPreferences()).hasSize(1);
    assertThat(response.briefingPreferences().get(0).categoryCode()).isEqualTo("JOB_POSTING");
    assertThat(response.briefingPreferences().get(0).categoryDisplayName()).isEqualTo("채용 브리핑");
    assertThat(response.briefingPreferences().get(0).preference()).containsKey("roles");
    assertThat(response.recentReports()).hasSize(1);
    assertThat(response.recentReports().get(0).title()).isEqualTo("오늘의 채용 브리핑");
    assertThat(response.latestBriefing()).isNotNull();
    assertThat(response.latestBriefing().id()).isEqualTo(1L);
    assertThat(response.nextDeliveryTime()).isNull();
    assertThat(response.latestDeliveryStatus()).isNull();
  }

  @Test
  void getDashboard_noReports_latestBriefingIsNull() {
    when(userRepository.findById(1L)).thenReturn(Optional.of(mockUser));
    when(userBriefingPreferenceRepository.findAllByUserIdAndActiveTrue(1L)).thenReturn(List.of());
    when(briefingReportRepository.findAllByUserIdOrderByReportDateDesc(eq(1L), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of()));

    DashboardResponse response = dashboardService.getDashboard(1L);

    assertThat(response.recentReports()).isEmpty();
    assertThat(response.latestBriefing()).isNull();
    assertThat(response.briefingPreferences()).isEmpty();
  }

  @Test
  void getDashboard_userNotFound_throwsUserNotFound() {
    when(userRepository.findById(99L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> dashboardService.getDashboard(99L))
        .isInstanceOf(BusinessException.class)
        .satisfies(
            e ->
                assertThat(((BusinessException) e).getErrorCode())
                    .isEqualTo(ErrorCode.USER_NOT_FOUND));
  }

  @Test
  void getDashboard_multiplePreferences_allReturned() {
    when(userRepository.findById(1L)).thenReturn(Optional.of(mockUser));

    BriefingCategory jobCat = mock(BriefingCategory.class);
    when(jobCat.getCode()).thenReturn(BriefingCategoryCode.JOB_POSTING);
    when(jobCat.getDisplayName()).thenReturn("채용 브리핑");

    BriefingCategory companyCat = mock(BriefingCategory.class);
    when(companyCat.getCode()).thenReturn(BriefingCategoryCode.COMPANY_NEWS);
    when(companyCat.getDisplayName()).thenReturn("관심 기업 브리핑");

    UserBriefingPreference jobPref = mock(UserBriefingPreference.class);
    when(jobPref.getCategory()).thenReturn(jobCat);
    when(jobPref.getPreference()).thenReturn(Map.of("roles", List.of("백엔드 개발자")));

    UserBriefingPreference companyPref = mock(UserBriefingPreference.class);
    when(companyPref.getCategory()).thenReturn(companyCat);
    when(companyPref.getPreference()).thenReturn(Map.of("companies", List.of("네이버")));

    when(userBriefingPreferenceRepository.findAllByUserIdAndActiveTrue(1L))
        .thenReturn(List.of(jobPref, companyPref));
    when(briefingReportRepository.findAllByUserIdOrderByReportDateDesc(eq(1L), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of()));

    DashboardResponse response = dashboardService.getDashboard(1L);

    assertThat(response.briefingPreferences()).hasSize(2);
    assertThat(
            response.briefingPreferences().stream()
                .map(DashboardResponse.BriefingPreferenceSummary::categoryCode))
        .containsExactlyInAnyOrder("JOB_POSTING", "COMPANY_NEWS");
  }

  @Test
  void getDashboard_latestBriefingIsFirstOfRecentReports() {
    when(userRepository.findById(1L)).thenReturn(Optional.of(mockUser));
    when(userBriefingPreferenceRepository.findAllByUserIdAndActiveTrue(1L)).thenReturn(List.of());

    BriefingReport r1 = mock(BriefingReport.class);
    when(r1.getId()).thenReturn(3L);
    when(r1.getTitle()).thenReturn("최신 브리핑");
    when(r1.getSummary()).thenReturn(null);
    when(r1.getReportDate()).thenReturn(LocalDate.of(2026, 6, 26));
    when(r1.getArticleCount()).thenReturn(5);
    when(r1.getCreatedAt()).thenReturn(null);

    BriefingReport r2 = mock(BriefingReport.class);
    when(r2.getId()).thenReturn(2L);
    when(r2.getTitle()).thenReturn("이전 브리핑");
    when(r2.getSummary()).thenReturn(null);
    when(r2.getReportDate()).thenReturn(LocalDate.of(2026, 6, 25));
    when(r2.getArticleCount()).thenReturn(3);
    when(r2.getCreatedAt()).thenReturn(null);

    when(briefingReportRepository.findAllByUserIdOrderByReportDateDesc(eq(1L), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(r1, r2)));

    DashboardResponse response = dashboardService.getDashboard(1L);

    assertThat(response.latestBriefing().id()).isEqualTo(3L);
    assertThat(response.recentReports()).hasSize(2);
  }
}
