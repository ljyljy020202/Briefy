package com.briefy.global.scheduler;

import com.briefy.config.EmailProperties;
import com.briefy.domain.briefing.dto.GenerateResult;
import com.briefy.domain.briefing.service.BriefingService;
import com.briefy.domain.preference.entity.BriefingCategoryCode;
import com.briefy.domain.preference.entity.UserBriefingPreference;
import com.briefy.domain.preference.repository.UserBriefingPreferenceRepository;
import com.briefy.domain.delivery.service.EmailDeliveryService;
import com.briefy.domain.user.repository.UserRepository;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "briefy.scheduler.enabled", havingValue = "true")
public class BriefingScheduler {

  private static final Logger log = LoggerFactory.getLogger(BriefingScheduler.class);

  private final BriefingService briefingService;
  private final UserBriefingPreferenceRepository userBriefingPreferenceRepository;
  private final EmailDeliveryService emailDeliveryService;
  private final EmailProperties emailProperties;
  private final UserRepository userRepository;

  public BriefingScheduler(
      BriefingService briefingService,
      UserBriefingPreferenceRepository userBriefingPreferenceRepository,
      EmailDeliveryService emailDeliveryService,
      EmailProperties emailProperties,
      UserRepository userRepository) {
    this.briefingService = briefingService;
    this.userBriefingPreferenceRepository = userBriefingPreferenceRepository;
    this.emailDeliveryService = emailDeliveryService;
    this.emailProperties = emailProperties;
    this.userRepository = userRepository;
  }

  @Scheduled(
      cron = "${briefy.scheduler.daily-briefing-cron:0 0 8 * * *}",
      zone = "${briefy.scheduler.zone:Asia/Seoul}")
  public void runScheduledBriefings() {
    LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
    log.info("Scheduled briefing generation started for {}", today);

    List<Long> preferenceUserIds =
        userBriefingPreferenceRepository
            .findAllByCategoryCodeAndActiveTrue(BriefingCategoryCode.JOB_POSTING)
            .stream()
            .map(UserBriefingPreference::getUserId)
            .distinct()
            .toList();

    Set<Long> subscribedUserIds =
        userRepository.findAllByIdInAndBriefingEmailEnabledTrue(preferenceUserIds).stream()
            .map(u -> u.getId())
            .collect(Collectors.toSet());

    if (subscribedUserIds.isEmpty()) {
      log.info("No subscribed users with active preferences — skipping briefing generation");
      return;
    }

    List<Long> userIds = preferenceUserIds.stream().filter(subscribedUserIds::contains).toList();

    for (Long userId : userIds) {
      try {
        GenerateResult result = briefingService.generateScheduledBriefing(userId);
        log.info("Briefing generated for user {}: reportId={}", userId, result.briefingReportId());

        if (emailProperties.autoSendEnabled()) {
          try {
            emailDeliveryService.autoDeliverBriefingReport(result.briefingReportId());
            log.info("Email delivered for user {}: reportId={}", userId, result.briefingReportId());
          } catch (Exception e) {
            log.error("Failed to deliver email for user {}: {}", userId, e.getMessage(), e);
          }
        }
      } catch (Exception e) {
        log.error("Failed to generate briefing for user {}: {}", userId, e.getMessage(), e);
      }
    }
  }
}
