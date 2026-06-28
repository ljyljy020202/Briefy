package com.briefy.domain.dashboard.dto;

import com.briefy.domain.briefing.dto.BriefingListItem;
import java.util.List;
import java.util.Map;

public record DashboardResponse(
    UserSummary user,
    List<BriefingPreferenceSummary> briefingPreferences,
    String nextDeliveryTime,
    BriefingListItem latestBriefing,
    String latestDeliveryStatus,
    List<BriefingListItem> recentReports) {

  public record UserSummary(String nickname, String email, boolean onboardingCompleted) {}

  public record BriefingPreferenceSummary(
      String categoryCode, String categoryDisplayName, Map<String, Object> preference) {}
}
