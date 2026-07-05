package com.briefy.scheduler;

import com.briefy.config.EmailProperties;
import com.briefy.domain.briefing.dto.GenerateResult;
import com.briefy.domain.briefing.repository.BriefingReportRepository;
import com.briefy.domain.briefing.service.BriefingService;
import com.briefy.domain.briefingpreference.entity.BriefingCategoryCode;
import com.briefy.domain.briefingpreference.entity.UserBriefingPreference;
import com.briefy.domain.briefingpreference.repository.UserBriefingPreferenceRepository;
import com.briefy.domain.delivery.service.EmailDeliveryService;
import java.time.LocalDate;
import java.util.List;
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
  private final BriefingReportRepository briefingReportRepository;
  private final EmailDeliveryService emailDeliveryService;
  private final EmailProperties emailProperties;

  public BriefingScheduler(
      BriefingService briefingService,
      UserBriefingPreferenceRepository userBriefingPreferenceRepository,
      BriefingReportRepository briefingReportRepository,
      EmailDeliveryService emailDeliveryService,
      EmailProperties emailProperties) {
    this.briefingService = briefingService;
    this.userBriefingPreferenceRepository = userBriefingPreferenceRepository;
    this.briefingReportRepository = briefingReportRepository;
    this.emailDeliveryService = emailDeliveryService;
    this.emailProperties = emailProperties;
  }

  @Scheduled(
      cron = "${briefy.scheduler.daily-briefing-cron:0 0 8 * * *}",
      zone = "${briefy.scheduler.zone:Asia/Seoul}")
  public void runScheduledBriefings() {
    LocalDate today = LocalDate.now();
    log.info("Scheduled briefing generation started for {}", today);

    List<Long> userIds =
        userBriefingPreferenceRepository
            .findAllByCategoryCodeAndActiveTrue(BriefingCategoryCode.JOB_POSTING)
            .stream()
            .map(UserBriefingPreference::getUserId)
            .distinct()
            .toList();

    for (Long userId : userIds) {
      if (briefingReportRepository.existsByUserIdAndReportDate(userId, today)) {
        log.info("Skipping user {} — briefing already exists for {}", userId, today);
        continue;
      }
      try {
        GenerateResult result = briefingService.generateScheduledBriefing(userId);
        log.info("Briefing generated for user {}: reportId={}", userId, result.briefingReportId());

        if (emailProperties.autoSendEnabled()) {
          try {
            emailDeliveryService.deliverBriefingReport(result.briefingReportId());
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
