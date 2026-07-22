package com.briefy.domain.company;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Validates the static structure of V6 company_sources seed migration without a running database.
 *
 * <p>Checks: - Exactly 18 INSERT rows - All status = 'PENDING' (no ACTIVE) - All 18 verified URLs
 * present and no duplicates - All CUSTOM rows have parser_key in config_json - All 16 expected
 * parser_keys present
 */
class CompanySourceSeedV6MigrationTest {

  private static String sql;

  private static final List<String> EXPECTED_URLS =
      List.of(
          "https://recruit.navercorp.com/rcrt/list.do",
          "https://careers.kakao.com/jobs",
          "https://careers.linecorp.com/ko/jobs/",
          "https://toss.im/career/jobs",
          "https://careers.daangn.com/jobs/",
          "https://www.coupang.jobs/kr/jobs/",
          "https://career.woowahan.com/",
          "https://www.musinsacareers.com/ko/home",
          "https://ably.team/recruit",
          "https://www.dunamu.com/careers/jobs",
          "https://www.socarcorp.kr/careers/jobs",
          "https://career.hyundai-autoever.com/ko/home",
          "https://career.oliveyoung.com/ko/home",
          "https://www.bucketplace.com/careers/",
          "https://job-boards.greenhouse.io/krafton",
          "https://ridi.recruit.roundhr.com/home",
          "https://yanolja.wd102.myworkdayjobs.com/ko-KR/External_Yanolja",
          "https://sendbird.com/ko/careers");

  private static final Set<String> EXPECTED_PARSER_KEYS =
      Set.of(
          "NAVER_CAREERS",
          "KAKAO_CAREERS",
          "LINE_CAREERS",
          "TOSS_CAREERS",
          "DAANGN_CAREERS",
          "COUPANG_CAREERS",
          "WOOWA_CAREERS",
          "GREETING",
          "ABLY_CAREERS",
          "DUNAMU_CAREERS",
          "SOCAR_CAREERS",
          "BUCKETPLACE_CAREERS",
          "GREENHOUSE",
          "ROUNDHR",
          "WORKDAY",
          "SENDBIRD_CAREERS");

  @BeforeAll
  static void loadMigrationFile() throws IOException {
    URL resource =
        CompanySourceSeedV6MigrationTest.class
            .getClassLoader()
            .getResource("db/migration/V6__seed_initial_company_sources.sql");
    assertThat(resource).as("V6 migration file must exist on classpath").isNotNull();
    sql = Files.readString(Path.of(resource.getPath()));
  }

  @Test
  void v6Migration_has18InsertStatements() {
    long count = countPattern(Pattern.compile("INSERT INTO company_sources"), sql);
    assertThat(count).isEqualTo(18);
  }

  @Test
  void v6Migration_allStatusValuesPending() {
    String noComments = sql.replaceAll("--[^\n]*", "");
    long pendingCount = countPattern(Pattern.compile("'PENDING'"), noComments);
    assertThat(pendingCount).isEqualTo(18);
  }

  @Test
  void v6Migration_noActiveStatusInInserts() {
    // Strip -- comments before checking so comment text doesn't interfere
    String noComments = sql.replaceAll("--[^\n]*", "");
    assertThat(noComments).doesNotContain("'ACTIVE'");
  }

  @Test
  void v6Migration_allExpectedUrlsPresent() {
    for (String expectedUrl : EXPECTED_URLS) {
      assertThat(sql)
          .as("Expected URL not found in V6 migration: %s", expectedUrl)
          .contains("'" + expectedUrl + "'");
    }
  }

  @Test
  void v6Migration_eachUrlAppearsExactlyTwice() {
    // Each URL appears once in the INSERT SELECT and once in the NOT EXISTS guard.
    Pattern urlPattern = Pattern.compile("'(https?://[^']+)'");
    Matcher m = urlPattern.matcher(sql);
    List<String> allUrls = new ArrayList<>();
    while (m.find()) {
      allUrls.add(m.group(1));
    }
    Set<String> uniqueUrls = new HashSet<>(allUrls);
    assertThat(uniqueUrls).hasSize(18);
    assertThat(allUrls).hasSize(uniqueUrls.size() * 2);
  }

  @Test
  void v6Migration_allExpectedParserKeysPresent() {
    Pattern pkPattern = Pattern.compile("\"parser_key\":\\s*\"([A-Z_]+)\"");
    Set<String> foundKeys = new HashSet<>();
    Matcher m = pkPattern.matcher(sql);
    while (m.find()) {
      foundKeys.add(m.group(1));
    }
    assertThat(foundKeys).containsExactlyInAnyOrderElementsOf(EXPECTED_PARSER_KEYS);
  }

  @Test
  void v6Migration_greetingParserKeyAppearsThreeTimes() {
    // 무신사, 현대오토에버, CJ올리브영 — three companies use the implemented GREETING parser
    long count = countPattern(Pattern.compile("\"parser_key\":\\s*\"GREETING\""), sql);
    assertThat(count).isEqualTo(3);
  }

  @Test
  void v6Migration_allSourceTypeIsOfficialCareer() {
    // 'OFFICIAL_CAREER' appears in INSERT SELECT (×1) and NOT EXISTS WHERE (×1) per row = ×2
    String noComments = sql.replaceAll("--[^\n]*", "");
    long count = countPattern(Pattern.compile("'OFFICIAL_CAREER'"), noComments);
    assertThat(count).isEqualTo(36);
  }

  @Test
  void v6Migration_allAdapterTypeIsCustom() {
    // 'CUSTOM' appears only in the INSERT SELECT, not in the NOT EXISTS clause
    String noComments = sql.replaceAll("--[^\n]*", "");
    long count = countPattern(Pattern.compile("'CUSTOM'"), noComments);
    assertThat(count).isEqualTo(18);
  }

  @Test
  void v6Migration_allRowsHaveNotExistsGuard() {
    String noComments = sql.replaceAll("--[^\n]*", "");
    long count = countPattern(Pattern.compile("NOT EXISTS"), noComments);
    assertThat(count).isEqualTo(18);
  }

  private static long countPattern(Pattern pattern, String text) {
    Matcher m = pattern.matcher(text);
    long count = 0;
    while (m.find()) {
      count++;
    }
    return count;
  }
}
