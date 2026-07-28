package com.briefy.domain.briefing.policy;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public final class JobRolePolicy {

  private JobRolePolicy() {}

  public enum Verdict {
    /** User's preferred group found in posting title/roles. */
    MATCH,
    /**
     * No specific role group found (generic "개발자", "Software Engineer", or blank). Posting is not
     * excluded but does not receive a role-match score bonus.
     */
    AMBIGUOUS,
    /** A clear role group is present but it differs from the user's preferences. */
    MISMATCH,
  }

  /**
   * Evaluate role eligibility.
   *
   * <p>Primary evidence: title + roles JSON array field. Skills and description are not used.
   *
   * @param userRoles raw role strings from preference (e.g., ["백엔드 개발자"])
   * @param postingTitle posting title
   * @param postingRolesJson JSON array stored in the roles column (e.g., ["백엔드","서버"])
   */
  public static Verdict evaluate(
      List<String> userRoles, String postingTitle, String postingRolesJson) {
    if (userRoles == null || userRoles.isEmpty()) {
      // No role preference set — no filtering applied.
      return Verdict.AMBIGUOUS;
    }

    Set<RoleGroup> acceptableGroups = resolveAcceptableGroups(userRoles);
    if (acceptableGroups.isEmpty()) {
      // User's role strings didn't map to any known group (free-form text).
      return Verdict.AMBIGUOUS;
    }

    String primaryText = buildPrimaryText(postingTitle, postingRolesJson);
    Set<RoleGroup> postingGroups = detectGroups(primaryText);

    // Check if any posting group is acceptable to the user.
    for (RoleGroup g : postingGroups) {
      if (acceptableGroups.contains(g)) {
        return Verdict.MATCH;
      }
    }

    if (postingGroups.isEmpty()) {
      // No specific group signal in title/roles — ambiguous, pass with penalty.
      return Verdict.AMBIGUOUS;
    }

    // Posting belongs to one or more clear groups, none of which match the user's preference.
    return Verdict.MISMATCH;
  }

  private static Set<RoleGroup> resolveAcceptableGroups(List<String> userRoles) {
    Set<RoleGroup> result = EnumSet.noneOf(RoleGroup.class);
    for (String role : userRoles) {
      String lowerRole = role.toLowerCase();
      for (RoleGroup g : RoleGroup.values()) {
        if (g == RoleGroup.NON_DEV) continue;
        if (g.matches(lowerRole)) {
          result.addAll(g.compatiblePostingGroups());
        }
      }
    }
    return result;
  }

  /** Combines title and the roles JSON array into a single lowercase string for matching. */
  static String buildPrimaryText(String title, String rolesJson) {
    StringBuilder sb = new StringBuilder();
    if (title != null) sb.append(title.toLowerCase()).append(' ');
    if (rolesJson != null && !rolesJson.isBlank()) {
      if (rolesJson.startsWith("[")) {
        String inner = rolesJson.substring(1, rolesJson.length() - 1).replace("\"", " ");
        sb.append(inner.toLowerCase());
      } else {
        sb.append(rolesJson.toLowerCase());
      }
    }
    return sb.toString();
  }

  private static Set<RoleGroup> detectGroups(String text) {
    if (text.isBlank()) return Set.of();
    Set<RoleGroup> found = EnumSet.noneOf(RoleGroup.class);
    for (RoleGroup g : RoleGroup.values()) {
      if (g.matches(text)) {
        found.add(g);
      }
    }
    return found;
  }
}
