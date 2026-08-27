package com.briefy.domain.briefing.recommendation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SequencedSet;

/**
 * Selects up to {@link #MAX_RECOMMENDATIONS} candidates from a scored pool using the briefing
 * editorial policy.
 *
 * <h2>Selection algorithm</h2>
 *
 * <ol>
 *   <li>Sort the input by {@link RecommendationCandidate#defaultOrder()}.
 *   <li>Reserve at least {@link #MIN_URGENT} URGENT candidates (in score order, company cap
 *       respected). A candidate that is both isNew and isUrgent fulfils this requirement and also
 *       counts toward the NEW tally.
 *   <li>If the current selection contains fewer than {@link #MIN_NEW} NEW candidates, pick
 *       additional NEW candidates (in score order, company cap respected) until the minimum is met
 *       or the NEW pool is exhausted.
 *   <li>Fill remaining slots from the globally sorted list (all candidate types, company cap and
 *       deduplication respected) until {@link #MAX_RECOMMENDATIONS} is reached or the pool is
 *       exhausted.
 * </ol>
 *
 * <h2>Invariants</h2>
 *
 * <ul>
 *   <li>No posting appears more than once in the result.
 *   <li>No company appears more than {@link #MAX_PER_COMPANY} times in the result.
 *   <li>If a minimum cannot be satisfied (insufficient candidates or company cap), the selector
 *       degrades gracefully without error.
 *   <li>The company cap is never silently violated to satisfy a minimum.
 *   <li>Total result size ≤ {@link #MAX_RECOMMENDATIONS}.
 * </ul>
 *
 * <p>This class is stateless; all methods are static.
 */
public final class RecommendationSelector {

  public static final int MAX_RECOMMENDATIONS = 7;
  public static final int MIN_NEW = 2;
  public static final int MIN_URGENT = 1;
  public static final int MAX_PER_COMPANY = 2;

  /** Published/collected within this many days of the reference date → isNew. */
  public static final int NEW_DAYS = 3;

  /** Deadline within this many days of the reference date (and not expired) → isUrgent. */
  public static final int URGENT_DAYS = 7;

  private RecommendationSelector() {}

  /**
   * Selects up to {@link #MAX_RECOMMENDATIONS} candidates from {@code candidates}.
   *
   * <p>The input list does not need to be pre-sorted; this method sorts it internally using {@link
   * RecommendationCandidate#defaultOrder()}.
   *
   * @param candidates all scored, filtered candidates for this user
   * @return an ordered list of selected recommendations (≤ MAX_RECOMMENDATIONS, in selection
   *     priority order)
   */
  public static List<RecommendationCandidate> select(List<RecommendationCandidate> candidates) {
    List<RecommendationCandidate> sorted =
        candidates.stream().sorted(RecommendationCandidate.defaultOrder()).toList();

    // Use SequencedSet to preserve insertion order while preventing duplicates.
    // Key by posting id (or url as fallback) since equals/hashCode on the record
    // compares all fields including the JPA entity reference.
    SequencedSet<String> selectedKeys = new LinkedHashSet<>();
    List<RecommendationCandidate> selected = new ArrayList<>();
    Map<String, Integer> companyCount = new HashMap<>();

    // ── Step 1: Reserve URGENT minimum ───────────────────────────────────────
    int urgentReserved = 0;
    for (RecommendationCandidate c : sorted) {
      if (urgentReserved >= MIN_URGENT) break;
      if (c.isUrgent() && tryAdd(c, selected, selectedKeys, companyCount)) {
        urgentReserved++;
      }
    }

    // ── Step 2: Ensure NEW minimum ────────────────────────────────────────────
    // A NEW+URGENT posting already in selected counts toward the NEW tally.
    int newInSelected = (int) selected.stream().filter(RecommendationCandidate::isNew).count();
    if (newInSelected < MIN_NEW) {
      int newNeeded = MIN_NEW - newInSelected;
      for (RecommendationCandidate c : sorted) {
        if (newNeeded <= 0) break;
        if (c.isNew() && tryAdd(c, selected, selectedKeys, companyCount)) {
          newNeeded--;
        }
      }
    }

    // ── Step 3: Fill remaining slots from all candidates ──────────────────────
    for (RecommendationCandidate c : sorted) {
      if (selected.size() >= MAX_RECOMMENDATIONS) break;
      tryAdd(c, selected, selectedKeys, companyCount);
    }

    return List.copyOf(selected);
  }

  /**
   * Attempts to add {@code candidate} to the selection.
   *
   * <p>Returns {@code true} and mutates the selection state if the candidate is not a duplicate and
   * does not violate the per-company cap. Returns {@code false} otherwise.
   */
  private static boolean tryAdd(
      RecommendationCandidate candidate,
      List<RecommendationCandidate> selected,
      SequencedSet<String> selectedKeys,
      Map<String, Integer> companyCount) {

    String key = candidateKey(candidate);
    if (selectedKeys.contains(key)) return false;

    String companyLower = companyLower(candidate);
    if (companyCount.getOrDefault(companyLower, 0) >= MAX_PER_COMPANY) return false;

    selected.add(candidate);
    selectedKeys.add(key);
    companyCount.merge(companyLower, 1, Integer::sum);
    return true;
  }

  /** Stable identity key: posting id if present, otherwise source URL. */
  private static String candidateKey(RecommendationCandidate c) {
    Long id = c.posting().getId();
    if (id != null) return "id:" + id;
    String url = c.posting().getUrl();
    return "url:" + (url != null ? url : "");
  }

  private static String companyLower(RecommendationCandidate c) {
    String company = c.posting().getCompany();
    return company != null ? company.toLowerCase() : "";
  }
}
