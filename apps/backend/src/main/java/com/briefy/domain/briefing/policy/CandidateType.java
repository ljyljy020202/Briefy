package com.briefy.domain.briefing.policy;

public enum CandidateType {
  /** Published or first seen within the last 3 days. */
  NEW,
  /** Not NEW, but deadline is within 7 days. */
  URGENT,
  /** Active, un-expired, and not NEW or URGENT. */
  EVERGREEN
}
