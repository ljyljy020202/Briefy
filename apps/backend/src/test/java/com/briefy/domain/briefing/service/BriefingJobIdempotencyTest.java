package com.briefy.domain.briefing.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.briefy.domain.briefing.entity.BriefingJob;
import com.briefy.domain.briefing.entity.BriefingJobStatus;
import com.briefy.domain.briefing.entity.BriefingReport;
import com.briefy.domain.briefing.repository.BriefingJobRepository;
import com.briefy.domain.briefing.repository.BriefingReportRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BriefingJobIdempotencyTest {

  @Mock private BriefingJobRepository briefingJobRepository;
  @Mock private BriefingReportRepository briefingReportRepository;

  private BriefingJobPersistenceService persistenceService;

  private static final LocalDate TODAY = LocalDate.of(2026, 8, 1);
  private static final Long USER_ID = 1L;

  @BeforeEach
  void setUp() {
    persistenceService =
        new BriefingJobPersistenceService(briefingJobRepository, briefingReportRepository);
  }

  private BriefingJob pendingJob() {
    return BriefingJob.createManual(USER_ID, TODAY);
  }

  // ── createOrGet ───────────────────────────────────────────────────────────

  @Test
  void createOrGet_firstCall_returnsNewPendingJob() {
    BriefingJob job = pendingJob();
    when(briefingJobRepository.save(any())).thenReturn(job);

    BriefingJob result = persistenceService.createOrGet(job);

    assertThat(result.getStatus()).isEqualTo(BriefingJobStatus.PENDING);
    assertThat(result.getBriefingDate()).isEqualTo(TODAY);
  }

  @Test
  void createOrGet_uniqueConflict_returnsExistingJob() {
    BriefingJob existingJob = pendingJob();
    when(briefingJobRepository.save(any()))
        .thenThrow(new DataIntegrityViolationException("Duplicate entry"));
    when(briefingJobRepository.findByUserIdAndBriefingDate(USER_ID, TODAY))
        .thenReturn(Optional.of(existingJob));

    BriefingJob result = persistenceService.createOrGet(pendingJob());

    assertThat(result).isEqualTo(existingJob);
  }

  @Test
  void createOrGet_uniqueConflict_findByDateAlsoFails_rethrowsOriginal() {
    when(briefingJobRepository.save(any()))
        .thenThrow(new DataIntegrityViolationException("Duplicate entry"));
    when(briefingJobRepository.findByUserIdAndBriefingDate(USER_ID, TODAY))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> persistenceService.createOrGet(pendingJob()))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  // ── claimForProcessing ────────────────────────────────────────────────────

  @Test
  void claimForProcessing_oneRowUpdated_returnsTrue() {
    when(briefingJobRepository.claimPending(eq(10L), any(LocalDateTime.class))).thenReturn(1);

    boolean claimed = persistenceService.claimForProcessing(10L);

    assertThat(claimed).isTrue();
  }

  @Test
  void claimForProcessing_zeroRowsUpdated_returnsFalse() {
    when(briefingJobRepository.claimPending(eq(10L), any(LocalDateTime.class))).thenReturn(0);

    boolean claimed = persistenceService.claimForProcessing(10L);

    assertThat(claimed).isFalse();
  }

  // ── findTodayReport ───────────────────────────────────────────────────────

  @Test
  void findTodayReport_reportExists_returnsIt() {
    BriefingReport report = mockReport();
    when(briefingReportRepository.findByUserIdAndReportDate(USER_ID, TODAY))
        .thenReturn(Optional.of(report));

    Optional<BriefingReport> result = persistenceService.findTodayReport(USER_ID, TODAY);

    assertThat(result).isPresent();
    assertThat(result.get()).isEqualTo(report);
  }

  @Test
  void findTodayReport_noReport_returnsEmpty() {
    when(briefingReportRepository.findByUserIdAndReportDate(USER_ID, TODAY))
        .thenReturn(Optional.empty());

    Optional<BriefingReport> result = persistenceService.findTodayReport(USER_ID, TODAY);

    assertThat(result).isEmpty();
  }

  // ── recordFailure ─────────────────────────────────────────────────────────

  @Test
  void recordFailure_jobExists_marksJobFailed() {
    BriefingJob job = pendingJob();
    job.startProcessing();
    when(briefingJobRepository.findById(10L)).thenReturn(Optional.of(job));

    persistenceService.recordFailure(10L, "Agent timeout");

    assertThat(job.getStatus()).isEqualTo(BriefingJobStatus.FAILED);
    assertThat(job.getErrorMessage()).isEqualTo("Agent timeout");
  }

  @Test
  void recordFailure_jobNotFound_doesNotThrow() {
    when(briefingJobRepository.findById(99L)).thenReturn(Optional.empty());

    // Should not throw — silently skips
    persistenceService.recordFailure(99L, "error");
  }

  // ── saveReportAndComplete ─────────────────────────────────────────────────

  @Test
  void saveReportAndComplete_jobExists_completesJobAndSavesReport() {
    BriefingJob job = pendingJob();
    job.startProcessing();
    when(briefingJobRepository.findById(10L)).thenReturn(Optional.of(job));

    BriefingReport report = mockReport();
    when(briefingReportRepository.save(any())).thenReturn(report);

    BriefingReport saved = persistenceService.saveReportAndComplete(10L, report, "LLM", null);

    assertThat(saved).isEqualTo(report);
    assertThat(job.getStatus()).isEqualTo(BriefingJobStatus.COMPLETED);
    assertThat(job.getGenerationMode()).isEqualTo("LLM");
  }

  @Test
  void saveReportAndComplete_withFallbackMode_recordsGenerationMode() {
    BriefingJob job = pendingJob();
    job.startProcessing();
    when(briefingJobRepository.findById(10L)).thenReturn(Optional.of(job));

    BriefingReport report = mockReport();
    when(briefingReportRepository.save(any())).thenReturn(report);

    BriefingReport saved =
        persistenceService.saveReportAndComplete(10L, report, "FALLBACK", "enrichment_failed");

    assertThat(saved).isEqualTo(report);
    assertThat(job.getStatus()).isEqualTo(BriefingJobStatus.COMPLETED);
    assertThat(job.getGenerationMode()).isEqualTo("FALLBACK");
    assertThat(job.getFallbackReason()).isEqualTo("enrichment_failed");
  }

  // ── Helpers ───────────────────────────────────────────────────────────────

  private BriefingReport mockReport() {
    // BriefingReport cannot be easily instantiated without a BriefingJob reference.
    // Use a real mock via Mockito for structural isolation.
    BriefingReport report = org.mockito.Mockito.mock(BriefingReport.class);
    when(report.getId()).thenReturn(100L);
    return report;
  }
}
