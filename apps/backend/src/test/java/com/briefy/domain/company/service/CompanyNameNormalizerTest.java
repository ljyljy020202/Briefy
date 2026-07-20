package com.briefy.domain.company.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CompanyNameNormalizerTest {

  private final CompanyNameNormalizer normalizer = new CompanyNameNormalizer();

  @Test
  void normalize_null_throws() {
    assertThatThrownBy(() -> normalizer.normalize(null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void normalize_stripsLeadingAndTrailingUnicodeWhitespace() {
    assertThat(normalizer.normalize("  토스  ")).isEqualTo("토스");
  }

  @Test
  void normalize_lowercasesAsciiWithRootLocale() {
    assertThat(normalizer.normalize("KAKAO")).isEqualTo("kakao");
  }

  @Test
  void normalize_mixedCaseAndSpaces() {
    assertThat(normalizer.normalize("  Kakao Pay  ")).isEqualTo("kakao pay");
  }

  @Test
  void normalize_koreanCharactersUnchanged() {
    assertThat(normalizer.normalize("카카오")).isEqualTo("카카오");
    assertThat(normalizer.normalize("네이버")).isEqualTo("네이버");
  }

  @Test
  void normalize_alreadyNormalizedString_returnsItself() {
    assertThat(normalizer.normalize("toss")).isEqualTo("toss");
  }

  @Test
  void normalize_emptyString_returnsEmpty() {
    assertThat(normalizer.normalize("")).isEqualTo("");
  }

  @Test
  void normalize_onlyWhitespace_returnsEmpty() {
    assertThat(normalizer.normalize("   ")).isEqualTo("");
  }

  @Test
  void normalize_mixedKoreanEnglish() {
    assertThat(normalizer.normalize("  Kakao뱅크  ")).isEqualTo("kakao뱅크");
  }
}
