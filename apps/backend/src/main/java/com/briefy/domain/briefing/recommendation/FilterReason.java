package com.briefy.domain.briefing.recommendation;

/** Reason a posting was excluded by {@link RecommendationFilter}. */
public enum FilterReason {
  /** deadline is before the reference date. */
  EXPIRED,
  /** title, company, or source URL is blank — briefing cannot be constructed. */
  MISSING_REQUIRED_FIELDS,
  /** JobRolePolicy returned MISMATCH. */
  ROLE_MISMATCH,
  /** ExperiencePolicy returned EXCLUDE. */
  EXPERIENCE_EXCLUDED,
  /** Both sides state an employment type and they do not match. */
  EMPLOYMENT_TYPE_MISMATCH,
  /** Classification analysis determined the posting is a NON_IT domain. ENFORCE mode only. */
  ANALYSIS_NON_IT,
  /**
   * Classification analysis determined the posting is IT but a different role. ENFORCE mode only.
   */
  ANALYSIS_ROLE_MISMATCH,
  /** No valid analysis is available and ENFORCE mode requires one. */
  ANALYSIS_DEFERRED,
  /** Classification result is in CONFLICT state; the posting cannot be evaluated. */
  ANALYSIS_CONFLICT,
}
