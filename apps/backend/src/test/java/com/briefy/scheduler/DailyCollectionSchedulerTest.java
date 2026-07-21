package com.briefy.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.briefy.domain.collection.dto.DailyCollectionResult;
import com.briefy.domain.collection.service.DailyCollectionService;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DailyCollectionSchedulerTest {

  @Mock private DailyCollectionService dailyCollectionService;

  @InjectMocks private DailyCollectionScheduler scheduler;

  private DailyCollectionResult completedResult(LocalDate date) {
    return new DailyCollectionResult(1L, "COMPLETED", date, null, 5, 0, List.of(), null);
  }

  private DailyCollectionResult skippedResult(LocalDate date) {
    return new DailyCollectionResult(
        null, "SKIPPED", date, null, 0, 0, List.of(), "Already active");
  }

  @Test
  void runDailyCollection_delegatesToService() {
    LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
    when(dailyCollectionService.triggerScheduledDailyCollection(any()))
        .thenReturn(completedResult(today));

    scheduler.runDailyCollection();

    verify(dailyCollectionService).triggerScheduledDailyCollection(today);
  }

  @Test
  void runDailyCollection_serviceThrows_logsAndDoesNotRethrow() {
    when(dailyCollectionService.triggerScheduledDailyCollection(any()))
        .thenThrow(new RuntimeException("agent down"));

    scheduler.runDailyCollection();

    verify(dailyCollectionService).triggerScheduledDailyCollection(any());
  }

  @Test
  void runDailyCollection_skippedResult_completesNormally() {
    LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
    when(dailyCollectionService.triggerScheduledDailyCollection(any()))
        .thenReturn(skippedResult(today));

    scheduler.runDailyCollection();

    verify(dailyCollectionService).triggerScheduledDailyCollection(today);
  }
}
