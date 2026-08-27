package com.briefy.domain.briefing.recommendation;

import com.briefy.domain.briefing.policy.ExperienceParser;
import com.briefy.domain.briefing.policy.ExperiencePolicy;
import com.briefy.domain.briefing.policy.JobRolePolicy;
import com.briefy.domain.briefing.policy.ParsedExperience;
import com.briefy.domain.candidatepool.entity.JobPosting;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Authoritative hard filter for recommendation candidates.
 *
 * <p>Each rule is conservative: when either side lacks a value, the posting is not excluded on that
 * criterion alone. Only clear, explicit mismatches cause exclusion.
 *
 * <p>This class is stateless; all methods are static.
 */
public final class RecommendationFilter {

  private RecommendationFilter() {}

  /**
   * Evaluates whether a posting passes all hard filters.
   *
   * @param posting the job posting to evaluate
   * @param preference user preference map (keys: roles, experienceLevels, employmentTypes, etc.)
   * @param referenceDate the briefing date (typically today in KST)
   * @return {@link FilterResult#pass()} if eligible, or an exclusion result with the reason
   */
  public static FilterResult evaluate(
      JobPosting posting, Map<String, Object> preference, LocalDate referenceDate) {

    // 1. Missing required fields — briefing cannot be shown without these
    if (isMissingRequiredFields(posting)) {
      return FilterResult.exclude(FilterReason.MISSING_REQUIRED_FIELDS);
    }

    // 2. Expired: deadline strictly before reference date
    if (posting.getDeadline() != null && posting.getDeadline().isBefore(referenceDate)) {
      return FilterResult.exclude(FilterReason.EXPIRED);
    }

    // 3. Role mismatch (hard filter only on clear MISMATCH; AMBIGUOUS passes)
    List<String> prefRoles = extractList(preference, "roles");
    JobRolePolicy.Verdict roleVerdict =
        JobRolePolicy.evaluate(prefRoles, posting.getTitle(), posting.getRoles());
    if (roleVerdict == JobRolePolicy.Verdict.MISMATCH) {
      return FilterResult.exclude(FilterReason.ROLE_MISMATCH);
    }

    // 4. Experience excluded
    List<String> prefExpLevels = extractList(preference, "experienceLevels");
    ParsedExperience parsedExp = ExperienceParser.parse(posting.getExperienceLevel());
    ExperiencePolicy.Verdict expVerdict = ExperiencePolicy.evaluate(prefExpLevels, parsedExp);
    if (expVerdict == ExperiencePolicy.Verdict.EXCLUDE) {
      return FilterResult.exclude(FilterReason.EXPERIENCE_EXCLUDED);
    }

    // 5. Employment type mismatch — only when BOTH sides are explicit
    List<String> prefEmpTypes = extractList(preference, "employmentTypes");
    String postingEmpType = posting.getEmploymentType();
    if (!prefEmpTypes.isEmpty() && postingEmpType != null && !postingEmpType.isBlank()) {
      boolean anyMatch = prefEmpTypes.stream().anyMatch(e -> e.equalsIgnoreCase(postingEmpType));
      if (!anyMatch) {
        return FilterResult.exclude(FilterReason.EMPLOYMENT_TYPE_MISMATCH);
      }
    }

    return FilterResult.pass();
  }

  private static boolean isMissingRequiredFields(JobPosting posting) {
    return isBlank(posting.getTitle())
        || isBlank(posting.getCompany())
        || isBlank(posting.getUrl());
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }

  @SuppressWarnings("unchecked")
  private static List<String> extractList(Map<String, Object> pref, String key) {
    Object val = pref.get(key);
    if (val instanceof List<?> list) {
      return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }
    return List.of();
  }
}
