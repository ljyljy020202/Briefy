package com.briefy.domain.dashboard.dto;

import com.briefy.domain.briefing.dto.BriefingListItem;
import java.util.List;

public record DashboardResponse(
    UserSummary user,
    List<SubscribedTopicGroup> subscribedTopics,
    String nextDeliveryTime,
    BriefingListItem latestBriefing,
    String latestDeliveryStatus,
    List<BriefingListItem> recentReports) {

  public record UserSummary(String nickname, String email, boolean onboardingCompleted) {}

  public record SubscribedTopicGroup(String topicName, List<String> keywords) {}
}
