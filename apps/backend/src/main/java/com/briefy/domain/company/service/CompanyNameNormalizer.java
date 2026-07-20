package com.briefy.domain.company.service;

import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Canonical company-name normalization.
 *
 * <p>Rule: strip leading/trailing Unicode whitespace, then fold to lower-case using {@link
 * Locale#ROOT} (locale-independent). This is the single source of truth for how raw company strings
 * are prepared before any DB look-up or storage.
 *
 * <p>Not included in this scope: special-character removal, Korean/English translation, fuzzy
 * matching, or legal-entity suffix stripping.
 */
@Component
public class CompanyNameNormalizer {

  /**
   * Normalize a company name.
   *
   * @param name raw company name — must not be null
   * @return normalized name (stripped, lower-cased with ROOT locale)
   * @throws IllegalArgumentException if {@code name} is null
   */
  public String normalize(String name) {
    if (name == null) {
      throw new IllegalArgumentException("Company name must not be null");
    }
    return name.strip().toLowerCase(Locale.ROOT);
  }
}
