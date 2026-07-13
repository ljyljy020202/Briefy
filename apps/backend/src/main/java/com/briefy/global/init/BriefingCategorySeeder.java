package com.briefy.global.init;

import com.briefy.domain.briefingpreference.entity.BriefingCategory;
import com.briefy.domain.briefingpreference.entity.BriefingCategoryCode;
import com.briefy.domain.briefingpreference.repository.BriefingCategoryRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class BriefingCategorySeeder implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(BriefingCategorySeeder.class);

  private static final List<SeedEntry> SEED_DATA =
      List.of(
          new SeedEntry(BriefingCategoryCode.JOB_POSTING, "채용 브리핑", "1st", true),
          new SeedEntry(BriefingCategoryCode.COMPANY_NEWS, "관심 기업 브리핑", "1.5", false),
          new SeedEntry(BriefingCategoryCode.INDUSTRY_TREND, "산업/시장 브리핑", "2nd", false));

  private final BriefingCategoryRepository briefingCategoryRepository;

  public BriefingCategorySeeder(BriefingCategoryRepository briefingCategoryRepository) {
    this.briefingCategoryRepository = briefingCategoryRepository;
  }

  @Override
  public void run(ApplicationArguments args) {
    seed();
  }

  @Transactional
  void seed() {
    int inserted = 0;
    for (SeedEntry entry : SEED_DATA) {
      if (briefingCategoryRepository.findByCode(entry.code()).isEmpty()) {
        briefingCategoryRepository.save(
            BriefingCategory.create(
                entry.code(), entry.displayName(), entry.phase(), entry.active()));
        inserted++;
      } else {
        log.debug("BriefingCategory seed skipped (code already exists): {}", entry.code());
      }
    }
    if (inserted > 0) {
      log.info("BriefingCategory seed: {} category(ies) inserted", inserted);
    }
  }

  private record SeedEntry(
      BriefingCategoryCode code, String displayName, String phase, boolean active) {}
}
