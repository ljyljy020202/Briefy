package com.briefy.domain.dashboard.service;

import com.briefy.domain.briefing.dto.BriefingListItem;
import com.briefy.domain.briefing.entity.BriefingReport;
import com.briefy.domain.briefing.repository.BriefingReportRepository;
import com.briefy.domain.dashboard.dto.DashboardResponse;
import com.briefy.domain.topic.entity.UserTopic;
import com.briefy.domain.topic.repository.UserTopicRepository;
import com.briefy.domain.user.entity.User;
import com.briefy.domain.user.repository.UserRepository;
import com.briefy.global.exception.BusinessException;
import com.briefy.global.exception.ErrorCode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DashboardService {

  private final UserRepository userRepository;
  private final UserTopicRepository userTopicRepository;
  private final BriefingReportRepository briefingReportRepository;

  public DashboardService(
      UserRepository userRepository,
      UserTopicRepository userTopicRepository,
      BriefingReportRepository briefingReportRepository) {
    this.userRepository = userRepository;
    this.userTopicRepository = userTopicRepository;
    this.briefingReportRepository = briefingReportRepository;
  }

  @Transactional(readOnly = true)
  public DashboardResponse getDashboard(Long userId) {
    User user =
        userRepository
            .findById(userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));

    List<UserTopic> activeTopics = userTopicRepository.findAllByUserIdAndActiveTrue(userId);

    Map<String, List<String>> grouped =
        activeTopics.stream()
            .collect(
                Collectors.groupingBy(
                    ut -> ut.getTopic().getName(),
                    LinkedHashMap::new,
                    Collectors.mapping(UserTopic::getKeyword, Collectors.toList())));

    List<DashboardResponse.SubscribedTopicGroup> subscribedTopics =
        grouped.entrySet().stream()
            .map(e -> new DashboardResponse.SubscribedTopicGroup(e.getKey(), e.getValue()))
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
        subscribedTopics,
        null, // nextDeliveryTime — scheduler not yet implemented
        latestBriefing,
        null, // latestDeliveryStatus — email delivery not yet implemented
        recentReports);
  }
}
