package com.briefy.domain.dashboard.service;

import com.briefy.domain.briefing.dto.BriefingListItem;
import com.briefy.domain.briefing.entity.BriefingReport;
import com.briefy.domain.briefing.repository.BriefingReportRepository;
import com.briefy.domain.briefingpreference.entity.UserBriefingPreference;
import com.briefy.domain.briefingpreference.repository.UserBriefingPreferenceRepository;
import com.briefy.domain.dashboard.dto.DashboardResponse;
import com.briefy.domain.user.entity.User;
import com.briefy.domain.user.repository.UserRepository;
import com.briefy.global.exception.BusinessException;
import com.briefy.global.exception.ErrorCode;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DashboardService {

  private final UserRepository userRepository;
  private final UserBriefingPreferenceRepository userBriefingPreferenceRepository;
  private final BriefingReportRepository briefingReportRepository;

  public DashboardService(
      UserRepository userRepository,
      UserBriefingPreferenceRepository userBriefingPreferenceRepository,
      BriefingReportRepository briefingReportRepository) {
    this.userRepository = userRepository;
    this.userBriefingPreferenceRepository = userBriefingPreferenceRepository;
    this.briefingReportRepository = briefingReportRepository;
  }

  @Transactional(readOnly = true)
  public DashboardResponse getDashboard(Long userId) {
    User user =
        userRepository
            .findById(userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

    List<UserBriefingPreference> activePreferences =
        userBriefingPreferenceRepository.findAllByUserIdAndActiveTrue(userId);

    List<DashboardResponse.BriefingPreferenceSummary> briefingPreferences =
        activePreferences.stream()
            .map(
                p ->
                    new DashboardResponse.BriefingPreferenceSummary(
                        p.getCategory().getCode().name(),
                        p.getCategory().getDisplayName(),
                        p.getPreference()))
            .toList();

    List<BriefingReport> latestReports =
        briefingReportRepository
            .findAllByUserIdOrderByReportDateDesc(userId, PageRequest.of(0, 3))
            .getContent();

    List<BriefingListItem> recentReports =
        latestReports.stream().map(BriefingListItem::from).toList();

    BriefingListItem latestBriefing = recentReports.isEmpty() ? null : recentReports.get(0);

    return new DashboardResponse(
        new DashboardResponse.UserSummary(
            user.getNickname(), user.getEmail(), user.isOnboardingCompleted()),
        briefingPreferences,
        null, // nextDeliveryTime — scheduler not yet implemented
        latestBriefing,
        null, // latestDeliveryStatus — email delivery not yet implemented
        recentReports);
  }
}
