package com.briefy.domain.briefing.recommendation;

import com.briefy.domain.candidatepool.entity.JobPosting;
import java.time.LocalDate;
import java.util.Comparator;

/**
 * A job posting that has passed hard filtering and been fully scored.
 *
 * <p>{@code isNew} and {@code isUrgent} are computed independently — a posting may satisfy both
 * simultaneously. Neither flag is derived from the other.
 *
 * <p>The canonical ordering for selection is {@link #defaultOrder()}: adjustedScore descending,
 * then deterministic tie-break on deadline, first-seen date, and id.
 */
public record RecommendationCandidate(
    JobPosting posting,
    boolean isNew,
    boolean isUrgent,
    ScoreBreakdown scoreBreakdown,
    MatchEvidence matchEvidence) {

  /** Delegating accessor for convenience. */
  public int adjustedScore() {
    return scoreBreakdown.adjustedScore();
  }

  /** Delegating accessor for convenience. */
  public int relevanceScore() {
    return scoreBreakdown.relevanceScore();
  }

  /** Delegating accessor for convenience. */
  public int exposurePenalty() {
    return scoreBreakdown.exposurePenalty();
  }

  /**
   * Deterministic sort order for selection.
   *
   * <ol>
   *   <li>adjustedScore DESC (higher is better)
   *   <li>deadline ASC, nulls last (sooner deadline → higher priority among equals)
   *   <li>firstSeenAt DESC, nulls last (newer posting → higher priority)
   *   <li>id ASC (stable final tie-break — lower id means earlier ingestion)
   * </ol>
   */
  public static Comparator<RecommendationCandidate> defaultOrder() {
    return Comparator.comparingInt(RecommendationCandidate::adjustedScore)
        .reversed()
        .thenComparing(
            c -> c.posting().getDeadline(), Comparator.nullsLast(Comparator.naturalOrder()))
        .thenComparing(
            c -> firstSeenDate(c.posting()), Comparator.nullsLast(Comparator.reverseOrder()))
        .thenComparingLong(c -> c.posting().getId() != null ? c.posting().getId() : Long.MAX_VALUE);
  }

  /**
   * Computes {@code isNew}: true when the posting's published or first-seen date is within {@code
   * newDays} of {@code referenceDate} (inclusive).
   *
   * <p>If {@code publishedAt} is present it is used; otherwise {@code collectedDate} serves as a
   * proxy for when the posting was first discovered.
   */
  public static boolean computeIsNew(JobPosting posting, LocalDate referenceDate, int newDays) {
    LocalDate threshold = referenceDate.minusDays(newDays);
    if (posting.getPublishedAt() != null) {
      return !posting.getPublishedAt().toLocalDate().isBefore(threshold);
    }
    if (posting.getCollectedDate() != null) {
      return !posting.getCollectedDate().isBefore(threshold);
    }
    return false;
  }

  /**
   * Computes {@code isUrgent}: true when the posting has a deadline within {@code urgentDays} of
   * {@code referenceDate} (inclusive on both ends — deadline today counts, expired deadline does
   * not).
   */
  public static boolean computeIsUrgent(
      JobPosting posting, LocalDate referenceDate, int urgentDays) {
    if (posting.getDeadline() == null) return false;
    long daysUntil =
        java.time.temporal.ChronoUnit.DAYS.between(referenceDate, posting.getDeadline());
    return daysUntil >= 0 && daysUntil <= urgentDays;
  }

  private static LocalDate firstSeenDate(JobPosting p) {
    if (p.getPublishedAt() != null) return p.getPublishedAt().toLocalDate();
    return p.getCollectedDate();
  }
}
