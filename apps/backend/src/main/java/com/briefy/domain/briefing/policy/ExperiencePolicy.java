package com.briefy.domain.briefing.policy;

import java.util.List;

public final class ExperiencePolicy {

  private ExperiencePolicy() {}

  public enum Verdict {
    /** Fully compatible; eligible for experience score bonus. */
    PASS_FULL,
    /**
     * Compatible but not ideal (e.g. new grad user seeing a 1~2yr posting). Eligible; no experience
     * bonus.
     */
    PASS_PARTIAL,
    /** Incompatible; posting must be excluded. */
    EXCLUDE,
  }

  /**
   * Evaluate whether a posting's experience requirement is compatible with the user's preference.
   *
   * <ul>
   *   <li>ENTRY (경력 무관) and MIXED postings always pass for any user — they are open to all.
   *   <li>UNKNOWN postings pass with partial credit (no experience bonus).
   *   <li>New-grad users are hard-excluded from EXPERIENCED postings.
   *   <li>"인턴" in experienceLevels is treated as a new-grad preference (legacy compatibility).
   * </ul>
   */
  public static Verdict evaluate(List<String> userExpLevels, ParsedExperience posting) {
    // ── Always pass: posting accepts anyone ───────────────────────────────────
    if (posting.category() == ExperienceCategory.ENTRY
        || posting.category() == ExperienceCategory.MIXED) {
      return Verdict.PASS_FULL;
    }

    // ── No preference: no filtering ───────────────────────────────────────────
    if (userExpLevels == null || userExpLevels.isEmpty()) {
      if (posting.category() == ExperienceCategory.UNKNOWN) return Verdict.PASS_PARTIAL;
      return Verdict.PASS_FULL;
    }

    // ── Unknown posting: pass for all users, but partially ────────────────────
    if (posting.category() == ExperienceCategory.UNKNOWN) {
      return Verdict.PASS_PARTIAL;
    }

    boolean userIsNewGrad = isNewGradUser(userExpLevels);

    if (userIsNewGrad) {
      return evaluateForNewGrad(posting);
    }

    // ── Non-new-grad users: ENTRY/MIXED already handled above; use category match ──
    return evaluateForExperiencedUser(userExpLevels, posting);
  }

  private static Verdict evaluateForNewGrad(ParsedExperience posting) {
    return switch (posting.category()) {
      case NEW_GRAD -> Verdict.PASS_FULL;
        // ENTRY and MIXED handled before this method is reached
      case ENTRY, MIXED -> Verdict.PASS_FULL;
      case JUNIOR -> {
        // 1~2년 경력 요구: 신입 지원자에게 불리하지만 지원은 가능
        // minYears >= 3 인 JUNIOR는 없음 (ExperienceParser가 3+ → EXPERIENCED로 분류)
        yield Verdict.PASS_PARTIAL;
      }
      case EXPERIENCED -> Verdict.EXCLUDE;
      case UNKNOWN -> Verdict.PASS_PARTIAL;
    };
  }

  private static Verdict evaluateForExperiencedUser(
      List<String> userExpLevels, ParsedExperience posting) {
    // Category-based compatibility check for non-new-grad users.
    for (String pref : userExpLevels) {
      ParsedExperience prefParsed = ExperienceParser.parse(pref);
      if (isCategoryCompatible(prefParsed.category(), posting.category())) {
        return Verdict.PASS_FULL;
      }
    }
    return Verdict.PASS_PARTIAL;
  }

  private static boolean isCategoryCompatible(
      ExperienceCategory userCat, ExperienceCategory postingCat) {
    return switch (userCat) {
      case NEW_GRAD -> postingCat == ExperienceCategory.NEW_GRAD;
      case ENTRY -> postingCat == ExperienceCategory.ENTRY;
      case JUNIOR -> postingCat == ExperienceCategory.JUNIOR;
      case EXPERIENCED -> postingCat == ExperienceCategory.EXPERIENCED;
      case MIXED -> true;
      case UNKNOWN -> true;
    };
  }

  /** Returns true if any preference value indicates a new-graduate or intern level. */
  public static boolean isNewGradUser(List<String> expLevels) {
    if (expLevels == null) return false;
    for (String e : expLevels) {
      String l = e.trim().toLowerCase();
      if (l.equals("신입") || l.equals("신입사원") || l.equals("인턴") || l.equals("new grad")) {
        return true;
      }
    }
    return false;
  }
}
