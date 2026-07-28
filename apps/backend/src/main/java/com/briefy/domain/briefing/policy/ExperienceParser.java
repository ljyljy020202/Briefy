package com.briefy.domain.briefing.policy;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ExperienceParser {

  private static final Pattern RANGE_PATTERN = Pattern.compile("(\\d+)[~\\-](\\d+)\\s*년");
  private static final Pattern MIN_ONLY_PATTERN = Pattern.compile("(\\d+)\\s*년\\s*이상");

  private ExperienceParser() {}

  /**
   * Parse a raw experience level string (e.g. "3년 이상", "신입/경력", "경력 무관") into a structured {@link
   * ParsedExperience}.
   */
  public static ParsedExperience parse(String raw) {
    if (raw == null || raw.isBlank()) return ParsedExperience.unknown();

    String s = raw.trim().toLowerCase().replace(" ", "");

    // ── MIXED ─────────────────────────────────────────────────────────────────
    if (s.contains("신입/경력")
        || s.contains("신입·경력")
        || s.contains("경력/신입")
        || s.contains("신입및경력")
        || s.contains("신입,경력")) {
      return new ParsedExperience(ExperienceCategory.MIXED, 0, -1, false, 1.0);
    }

    // ── NEW_GRAD ───────────────────────────────────────────────────────────────
    if (s.equals("신입") || s.equals("신입사원") || s.equals("졸업예정자") || s.equals("인턴")) {
      return new ParsedExperience(ExperienceCategory.NEW_GRAD, 0, 0, false, 1.0);
    }
    if (s.startsWith("신입")) {
      return new ParsedExperience(ExperienceCategory.NEW_GRAD, 0, 0, false, 0.9);
    }

    // ── ENTRY (경력 무관) ───────────────────────────────────────────────────────
    if (s.equals("경력무관") || s.equals("무관") || s.equals("경력불문") || s.equals("경력무방")) {
      return new ParsedExperience(ExperienceCategory.ENTRY, 0, -1, false, 1.0);
    }
    if (s.equals("0~1년") || s.equals("0-1년") || s.equals("0년이상")) {
      return new ParsedExperience(ExperienceCategory.ENTRY, 0, 1, false, 1.0);
    }

    // ── Range pattern: N~M년 ──────────────────────────────────────────────────
    Matcher rangeMatcher = RANGE_PATTERN.matcher(raw.toLowerCase());
    if (rangeMatcher.find()) {
      int min = Integer.parseInt(rangeMatcher.group(1));
      int max = Integer.parseInt(rangeMatcher.group(2));
      return buildFromRange(min, max);
    }

    // ── Minimum only: N년 이상 ─────────────────────────────────────────────────
    Matcher minMatcher = MIN_ONLY_PATTERN.matcher(raw.toLowerCase());
    if (minMatcher.find()) {
      int min = Integer.parseInt(minMatcher.group(1));
      ExperienceCategory cat =
          min >= 3 ? ExperienceCategory.EXPERIENCED : ExperienceCategory.JUNIOR;
      return new ParsedExperience(cat, min, -1, true, 1.0);
    }

    // ── Named levels ─────────────────────────────────────────────────────────
    if (s.equals("주니어") || s.equals("junior")) {
      return new ParsedExperience(ExperienceCategory.JUNIOR, 1, 2, false, 0.9);
    }
    if (s.equals("시니어") || s.equals("senior") || s.equals("중급이상") || s.equals("고급")) {
      return new ParsedExperience(ExperienceCategory.EXPERIENCED, 3, -1, false, 0.9);
    }

    // ── "경력" alone (ambiguous experienced) ─────────────────────────────────
    if (s.equals("경력") || s.startsWith("경력(")) {
      return new ParsedExperience(ExperienceCategory.EXPERIENCED, -1, -1, false, 0.7);
    }

    return ParsedExperience.unknown();
  }

  private static ParsedExperience buildFromRange(int min, int max) {
    if (min == 0) {
      // 0~N년 → ENTRY
      return new ParsedExperience(ExperienceCategory.ENTRY, 0, max, false, 1.0);
    }
    // min >= 3 → EXPERIENCED; otherwise JUNIOR
    ExperienceCategory cat = min >= 3 ? ExperienceCategory.EXPERIENCED : ExperienceCategory.JUNIOR;
    return new ParsedExperience(cat, min, max, false, 1.0);
  }
}
