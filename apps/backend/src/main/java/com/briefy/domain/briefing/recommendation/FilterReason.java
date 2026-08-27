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
}
