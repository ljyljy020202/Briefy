package com.briefy.scheduler;

import com.briefy.domain.collection.dto.DailyCollectionResult;
import com.briefy.domain.collection.service.DailyCollectionService;
import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "briefy.scheduler.enabled", havingValue = "true")
public class DailyCollectionScheduler {

  private static final Logger log = LoggerFactory.getLogger(DailyCollectionScheduler.class);

  private final DailyCollectionService dailyCollectionService;

  public DailyCollectionScheduler(DailyCollectionService dailyCollectionService) {
    this.dailyCollectionService = dailyCollectionService;
  }

  @Scheduled(
      cron = "${briefy.scheduler.daily-collection-cron:0 0 6 * * *}",
      zone = "${briefy.scheduler.zone:Asia/Seoul}")
  public void runDailyCollection() {
    LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
    log.info("Scheduled daily collection started for {}", today);
    try {
      DailyCollectionResult result = dailyCollectionService.triggerScheduledDailyCollection(today);
      log.info(
          "Scheduled daily collection result: status={}, saved={}",
          result.status(),
          result.savedCount());
    } catch (Exception e) {
      log.error("Scheduled daily collection failed: {}", e.getMessage(), e);
    }
  }
}
