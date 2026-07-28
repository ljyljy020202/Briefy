package com.briefy.domain.briefing.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ExperiencePolicyTest {

  // ── ExperienceParser ─────────────────────────────────────────────────────

  @Test
  void parser_null_returns_unknown() {
    assertThat(ExperienceParser.parse(null).category()).isEqualTo(ExperienceCategory.UNKNOWN);
  }

  @Test
  void parser_blank_returns_unknown() {
    assertThat(ExperienceParser.parse("  ").category()).isEqualTo(ExperienceCategory.UNKNOWN);
  }

  @Test
  void parser_sinip_returns_new_grad() {
    assertThat(ExperienceParser.parse("신입").category()).isEqualTo(ExperienceCategory.NEW_GRAD);
  }

  @Test
  void parser_intern_returns_new_grad() {
    assertThat(ExperienceParser.parse("인턴").category()).isEqualTo(ExperienceCategory.NEW_GRAD);
  }

  @Test
  void parser_gyeongnyeok_mukan_returns_entry() {
    assertThat(ExperienceParser.parse("경력 무관").category()).isEqualTo(ExperienceCategory.ENTRY);
    assertThat(ExperienceParser.parse("경력무관").category()).isEqualTo(ExperienceCategory.ENTRY);
    assertThat(ExperienceParser.parse("무관").category()).isEqualTo(ExperienceCategory.ENTRY);
  }

  @Test
  void parser_mixed_returns_mixed() {
    assertThat(ExperienceParser.parse("신입/경력").category()).isEqualTo(ExperienceCategory.MIXED);
    assertThat(ExperienceParser.parse("신입·경력").category()).isEqualTo(ExperienceCategory.MIXED);
    assertThat(ExperienceParser.parse("경력/신입").category()).isEqualTo(ExperienceCategory.MIXED);
  }

  @Test
  void parser_1to2_returns_junior() {
    ParsedExperience p = ExperienceParser.parse("1~2년");
    assertThat(p.category()).isEqualTo(ExperienceCategory.JUNIOR);
    assertThat(p.minYears()).isEqualTo(1);
    assertThat(p.maxYears()).isEqualTo(2);
  }

  @Test
  void parser_1to3_returns_junior() {
    ParsedExperience p = ExperienceParser.parse("1~3년");
    assertThat(p.category()).isEqualTo(ExperienceCategory.JUNIOR);
    assertThat(p.minYears()).isEqualTo(1);
  }

  @Test
  void parser_2yrs_or_more_returns_junior_with_explicit_min() {
    ParsedExperience p = ExperienceParser.parse("2년 이상");
    assertThat(p.category()).isEqualTo(ExperienceCategory.JUNIOR);
    assertThat(p.minYears()).isEqualTo(2);
    assertThat(p.explicitMinimumRequired()).isTrue();
  }

  @Test
  void parser_3yrs_or_more_returns_experienced_with_explicit_min() {
    ParsedExperience p = ExperienceParser.parse("3년 이상");
    assertThat(p.category()).isEqualTo(ExperienceCategory.EXPERIENCED);
    assertThat(p.minYears()).isEqualTo(3);
    assertThat(p.explicitMinimumRequired()).isTrue();
  }

  @Test
  void parser_5yrs_or_more_returns_experienced() {
    ParsedExperience p = ExperienceParser.parse("5년 이상");
    assertThat(p.category()).isEqualTo(ExperienceCategory.EXPERIENCED);
    assertThat(p.minYears()).isEqualTo(5);
    assertThat(p.explicitMinimumRequired()).isTrue();
  }

  @Test
  void parser_3to5_returns_experienced() {
    ParsedExperience p = ExperienceParser.parse("3~5년");
    assertThat(p.category()).isEqualTo(ExperienceCategory.EXPERIENCED);
    assertThat(p.minYears()).isEqualTo(3);
  }

  @Test
  void parser_gyeongnyeok_alone_returns_experienced_with_lower_confidence() {
    ParsedExperience p = ExperienceParser.parse("경력");
    assertThat(p.category()).isEqualTo(ExperienceCategory.EXPERIENCED);
    assertThat(p.confidence()).isLessThan(1.0);
  }

  // ── ExperiencePolicy — new grad user ─────────────────────────────────────

  @Test
  void newGrad_new_grad_posting_passes_fully() {
    var result = ExperiencePolicy.evaluate(List.of("신입"), parse("신입"));
    assertThat(result).isEqualTo(ExperiencePolicy.Verdict.PASS_FULL);
  }

  @Test
  void newGrad_entry_posting_passes_fully() {
    var result = ExperiencePolicy.evaluate(List.of("신입"), parse("경력 무관"));
    assertThat(result).isEqualTo(ExperiencePolicy.Verdict.PASS_FULL);
  }

  @Test
  void newGrad_mixed_posting_passes_fully() {
    var result = ExperiencePolicy.evaluate(List.of("신입"), parse("신입/경력"));
    assertThat(result).isEqualTo(ExperiencePolicy.Verdict.PASS_FULL);
  }

  @Test
  void newGrad_1to2yr_posting_passes_partially() {
    var result = ExperiencePolicy.evaluate(List.of("신입"), parse("1~2년"));
    assertThat(result).isEqualTo(ExperiencePolicy.Verdict.PASS_PARTIAL);
  }

  @Test
  void newGrad_junior_1to3yr_passes_partially() {
    var result = ExperiencePolicy.evaluate(List.of("신입"), parse("1~3년"));
    assertThat(result).isEqualTo(ExperiencePolicy.Verdict.PASS_PARTIAL);
  }

  @Test
  void newGrad_3yrs_required_excludes() {
    var result = ExperiencePolicy.evaluate(List.of("신입"), parse("3년 이상"));
    assertThat(result).isEqualTo(ExperiencePolicy.Verdict.EXCLUDE);
  }

  @Test
  void newGrad_5yrs_required_excludes() {
    var result = ExperiencePolicy.evaluate(List.of("신입"), parse("5년 이상"));
    assertThat(result).isEqualTo(ExperiencePolicy.Verdict.EXCLUDE);
  }

  @Test
  void newGrad_experienced_posting_excludes() {
    var result = ExperiencePolicy.evaluate(List.of("신입"), parse("경력"));
    assertThat(result).isEqualTo(ExperiencePolicy.Verdict.EXCLUDE);
  }

  @Test
  void newGrad_unknown_exp_posting_passes_partially() {
    var result = ExperiencePolicy.evaluate(List.of("신입"), ParsedExperience.unknown());
    assertThat(result).isEqualTo(ExperiencePolicy.Verdict.PASS_PARTIAL);
  }

  // ── Legacy "인턴" in experienceLevels treated as new grad ──────────────────

  @Test
  void legacy_intern_in_expLevels_treated_as_new_grad() {
    // "인턴" in experienceLevels means the user wants new-grad-level experience.
    var result = ExperiencePolicy.evaluate(List.of("인턴"), parse("3년 이상"));
    assertThat(result).isEqualTo(ExperiencePolicy.Verdict.EXCLUDE);
  }

  @Test
  void legacy_intern_entry_posting_passes() {
    var result = ExperiencePolicy.evaluate(List.of("인턴"), parse("경력 무관"));
    assertThat(result).isEqualTo(ExperiencePolicy.Verdict.PASS_FULL);
  }

  // ── Always-pass cases (ENTRY and MIXED) ───────────────────────────────────

  @Test
  void entry_posting_passes_for_any_user_preference() {
    assertThat(ExperiencePolicy.evaluate(List.of("3~5년"), parse("경력 무관")))
        .isEqualTo(ExperiencePolicy.Verdict.PASS_FULL);
    assertThat(ExperiencePolicy.evaluate(List.of("5년 이상"), parse("경력무관")))
        .isEqualTo(ExperiencePolicy.Verdict.PASS_FULL);
  }

  @Test
  void mixed_posting_passes_for_experienced_user() {
    assertThat(ExperiencePolicy.evaluate(List.of("3~5년"), parse("신입/경력")))
        .isEqualTo(ExperiencePolicy.Verdict.PASS_FULL);
  }

  // ── No preference ────────────────────────────────────────────────────────

  @Test
  void no_preference_passes_all() {
    assertThat(ExperiencePolicy.evaluate(List.of(), parse("신입")))
        .isEqualTo(ExperiencePolicy.Verdict.PASS_FULL);
    assertThat(ExperiencePolicy.evaluate(List.of(), parse("5년 이상")))
        .isEqualTo(ExperiencePolicy.Verdict.PASS_FULL);
    assertThat(ExperiencePolicy.evaluate(List.of(), ParsedExperience.unknown()))
        .isEqualTo(ExperiencePolicy.Verdict.PASS_PARTIAL);
  }

  // ── Helpers ──────────────────────────────────────────────────────────────

  private ParsedExperience parse(String raw) {
    return ExperienceParser.parse(raw);
  }
}
