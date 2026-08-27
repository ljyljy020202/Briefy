package com.briefy.domain.briefing.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class JobRolePolicyTest {

  // ── Backend user ─────────────────────────────────────────────────────────

  @Test
  void backend_posting_matches_backend_user() {
    assertThat(evaluate(List.of("백엔드 개발자"), "백엔드 개발자 채용", null))
        .isEqualTo(JobRolePolicy.Verdict.MATCH);
  }

  @Test
  void english_backend_posting_matches_backend_user() {
    assertThat(evaluate(List.of("백엔드 개발자"), "Backend Engineer", null))
        .isEqualTo(JobRolePolicy.Verdict.MATCH);
  }

  @Test
  void server_engineer_posting_matches_backend_user() {
    assertThat(evaluate(List.of("백엔드 개발자"), "Server Engineer", null))
        .isEqualTo(JobRolePolicy.Verdict.MATCH);
  }

  @Test
  void frontend_posting_mismatches_backend_user() {
    assertThat(evaluate(List.of("백엔드 개발자"), "프론트엔드 개발자", null))
        .isEqualTo(JobRolePolicy.Verdict.MISMATCH);
  }

  @Test
  void marketing_intern_mismatches_backend_user() {
    assertThat(evaluate(List.of("백엔드 개발자"), "마케팅 인턴", null))
        .isEqualTo(JobRolePolicy.Verdict.MISMATCH);
  }

  @Test
  void marketing_with_sql_skills_mismatches_backend_user() {
    // Skills (description) must NOT override the role determination from title/roles.
    // Title is clearly marketing — role mismatch despite SQL mention elsewhere.
    assertThat(evaluate(List.of("백엔드 개발자"), "마케팅 분석가", null))
        .isEqualTo(JobRolePolicy.Verdict.MISMATCH);
  }

  @Test
  void ambiguous_software_engineer_passes_with_ambiguous_verdict() {
    assertThat(evaluate(List.of("백엔드 개발자"), "Software Engineer", null))
        .isEqualTo(JobRolePolicy.Verdict.AMBIGUOUS);
  }

  @Test
  void ambiguous_general_developer_passes_with_ambiguous_verdict() {
    assertThat(evaluate(List.of("백엔드 개발자"), "개발자", null))
        .isEqualTo(JobRolePolicy.Verdict.AMBIGUOUS);
  }

  @Test
  void fullstack_posting_matches_backend_user() {
    // FULLSTACK is compatible for BACKEND users.
    assertThat(evaluate(List.of("백엔드 개발자"), "풀스택 개발자", null))
        .isEqualTo(JobRolePolicy.Verdict.MATCH);
  }

  @Test
  void composite_posting_with_backend_passes_backend_user() {
    // Post has both marketing context and backend keyword — backend user should see it.
    assertThat(evaluate(List.of("백엔드 개발자"), "마케팅 플랫폼 백엔드 개발자", null))
        .isEqualTo(JobRolePolicy.Verdict.MATCH);
  }

  // ── No user preference ───────────────────────────────────────────────────

  @Test
  void no_preference_always_returns_ambiguous() {
    assertThat(evaluate(List.of(), "백엔드 개발자", null)).isEqualTo(JobRolePolicy.Verdict.AMBIGUOUS);
    assertThat(evaluate(List.of(), "마케팅 인턴", null)).isEqualTo(JobRolePolicy.Verdict.AMBIGUOUS);
  }

  // ── Roles JSON field ─────────────────────────────────────────────────────

  @Test
  void role_match_via_roles_json_field() {
    // Title is generic; role keyword found in the roles JSON array.
    assertThat(evaluate(List.of("백엔드 개발자"), "개발자 채용", "[\"백엔드\",\"서버\"]"))
        .isEqualTo(JobRolePolicy.Verdict.MATCH);
  }

  @Test
  void mismatch_when_roles_json_has_only_other_group() {
    assertThat(evaluate(List.of("백엔드 개발자"), "개발자 채용", "[\"프론트엔드\"]"))
        .isEqualTo(JobRolePolicy.Verdict.MISMATCH);
  }

  // ── Other role groups ────────────────────────────────────────────────────

  @Test
  void data_engineer_posting_matches_data_user() {
    assertThat(evaluate(List.of("데이터 엔지니어"), "Data Engineer", null))
        .isEqualTo(JobRolePolicy.Verdict.MATCH);
  }

  @Test
  void ios_posting_matches_mobile_user() {
    assertThat(evaluate(List.of("iOS 개발자"), "iOS Developer", null))
        .isEqualTo(JobRolePolicy.Verdict.MATCH);
  }

  @Test
  void devops_posting_matches_devops_user() {
    assertThat(evaluate(List.of("DevOps 엔지니어"), "DevOps Engineer", null))
        .isEqualTo(JobRolePolicy.Verdict.MATCH);
  }

  // ── Unknown / unmapped user role ─────────────────────────────────────────

  @Test
  void unmapped_user_role_returns_ambiguous() {
    // If the user enters a free-form role that doesn't map to any group, no filtering.
    assertThat(evaluate(List.of("스타트업 CTO"), "마케팅 인턴", null))
        .isEqualTo(JobRolePolicy.Verdict.AMBIGUOUS);
  }

  // ── NON_DEV keyword coverage ─────────────────────────────────────────────

  @Test
  void customer_service_posting_mismatches_backend_user() {
    assertThat(evaluate(List.of("백엔드 개발자"), "고객 상담 매니저", null))
        .isEqualTo(JobRolePolicy.Verdict.MISMATCH);
  }

  @Test
  void call_center_posting_mismatches_backend_user() {
    assertThat(evaluate(List.of("백엔드 개발자"), "콜센터 상담사", null))
        .isEqualTo(JobRolePolicy.Verdict.MISMATCH);
  }

  @Test
  void customer_success_posting_mismatches_backend_user() {
    assertThat(evaluate(List.of("백엔드 개발자"), "Customer Success Manager", null))
        .isEqualTo(JobRolePolicy.Verdict.MISMATCH);
  }

  @Test
  void customer_support_posting_mismatches_backend_user() {
    assertThat(evaluate(List.of("백엔드 개발자"), "Customer Support 담당", null))
        .isEqualTo(JobRolePolicy.Verdict.MISMATCH);
  }

  @Test
  void sales_posting_mismatches_backend_user() {
    assertThat(evaluate(List.of("백엔드 개발자"), "세일즈 매니저", null))
        .isEqualTo(JobRolePolicy.Verdict.MISMATCH);
  }

  // ── Helpers ──────────────────────────────────────────────────────────────

  private JobRolePolicy.Verdict evaluate(List<String> userRoles, String title, String rolesJson) {
    return JobRolePolicy.evaluate(userRoles, title, rolesJson);
  }
}
