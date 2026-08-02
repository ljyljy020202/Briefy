package com.briefy.domain.candidatepool.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class JobPostingTest {

  private static final LocalDate COLLECTED = LocalDate.of(2026, 6, 30);

  private JobPosting newPosting(String url) {
    return JobPosting.create(
        "백엔드 개발자",
        "네이버",
        "원티드",
        url,
        "서울",
        LocalDate.of(2026, 7, 15),
        "Spring Boot 백엔드 개발자를 채용합니다.",
        "[\"백엔드 개발자\"]",
        "[\"Spring Boot\", \"Java\"]",
        "정규직",
        "신입",
        "abc123",
        COLLECTED,
        LocalDateTime.of(2026, 6, 30, 9, 0));
  }

  @Test
  void create_setsAllFields() {
    JobPosting jp = newPosting("https://example.com/job/1");

    assertThat(jp.getTitle()).isEqualTo("백엔드 개발자");
    assertThat(jp.getCompany()).isEqualTo("네이버");
    assertThat(jp.getSource()).isEqualTo("원티드");
    assertThat(jp.getUrl()).isEqualTo("https://example.com/job/1");
    assertThat(jp.getLocation()).isEqualTo("서울");
    assertThat(jp.getDeadline()).isEqualTo(LocalDate.of(2026, 7, 15));
    assertThat(jp.getDescription()).isEqualTo("Spring Boot 백엔드 개발자를 채용합니다.");
    assertThat(jp.getRoles()).isEqualTo("[\"백엔드 개발자\"]");
    assertThat(jp.getSkills()).isEqualTo("[\"Spring Boot\", \"Java\"]");
    assertThat(jp.getEmploymentType()).isEqualTo("정규직");
    assertThat(jp.getExperienceLevel()).isEqualTo("신입");
    assertThat(jp.getContentHash()).isEqualTo("abc123");
    assertThat(jp.getCanonicalFingerprint()).isEqualTo("abc123");
    assertThat(jp.getLinkedCompany()).isNull();
    assertThat(jp.getCollectedDate()).isEqualTo(COLLECTED);
    assertThat(jp.getPublishedAt()).isEqualTo(LocalDateTime.of(2026, 6, 30, 9, 0));
    assertThat(jp.getId()).isNull();
  }

  @Test
  void create_withNullableFields_doesNotThrow() {
    JobPosting jp =
        JobPosting.create(
            "백엔드 개발자",
            "네이버",
            null,
            "https://example.com/job/2",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            COLLECTED,
            null);

    assertThat(jp.getTitle()).isEqualTo("백엔드 개발자");
    assertThat(jp.getSource()).isNull();
    assertThat(jp.getLocation()).isNull();
    assertThat(jp.getDeadline()).isNull();
    assertThat(jp.getRoles()).isNull();
    assertThat(jp.getPublishedAt()).isNull();
  }

  @Test
  void refreshFrom_updatesMutableFields() {
    JobPosting jp = newPosting("https://example.com/job/3");

    jp.refreshFrom(
        LocalDate.of(2026, 7, 20), "Updated description", "newhash", LocalDate.of(2026, 7, 1));

    assertThat(jp.getDeadline()).isEqualTo(LocalDate.of(2026, 7, 20));
    assertThat(jp.getDescription()).isEqualTo("Updated description");
    assertThat(jp.getContentHash()).isEqualTo("newhash");
    assertThat(jp.getCollectedDate()).isEqualTo(LocalDate.of(2026, 7, 1));
  }

  @Test
  void refreshFrom_withNullDeadline_keepsExistingDeadline() {
    JobPosting jp = newPosting("https://example.com/job/4");
    LocalDate originalDeadline = jp.getDeadline();

    jp.refreshFrom(null, "Updated description", "newhash", LocalDate.of(2026, 7, 1));

    assertThat(jp.getDeadline()).isEqualTo(originalDeadline);
  }

  @Test
  void refreshFrom_withBlankDescription_keepsExistingDescription() {
    JobPosting jp = newPosting("https://example.com/job/5");
    String originalDescription = jp.getDescription();

    jp.refreshFrom(LocalDate.of(2026, 7, 20), "  ", "newhash", LocalDate.of(2026, 7, 1));

    assertThat(jp.getDescription()).isEqualTo(originalDescription);
  }

  @Test
  void refreshFrom_withNullContentHash_keepsExistingHash() {
    JobPosting jp = newPosting("https://example.com/job/6");
    String originalHash = jp.getContentHash();

    jp.refreshFrom(
        LocalDate.of(2026, 7, 20), "Updated description", null, LocalDate.of(2026, 7, 1));

    assertThat(jp.getContentHash()).isEqualTo(originalHash);
  }

  // ── Metadata enrichment tests ──────────────────────────────────────────────

  private JobPosting postingWithNullMetadata(String url) {
    return JobPosting.create(
        "백엔드 개발자", "네이버", "원티드", url, null, // location null
        null, "기존 설명", null, // roles null
        null, // skills null
        null, // employmentType null
        null, // experienceLevel null
        "hash", COLLECTED, null);
  }

  @Test
  void refreshFrom_enrichesNullRolesWithNewValue() {
    JobPosting jp = postingWithNullMetadata("https://example.com/job/10");
    assertThat(jp.getRoles()).isNull();

    jp.refreshFrom(
        null,
        null,
        "newhash",
        COLLECTED,
        "[\"Backend Engineering\"]",
        null,
        null,
        null,
        null,
        null);

    assertThat(jp.getRoles()).isEqualTo("[\"Backend Engineering\"]");
  }

  @Test
  void refreshFrom_doesNotOverwriteExistingRolesWithNull() {
    JobPosting jp = newPosting("https://example.com/job/11");
    assertThat(jp.getRoles()).isEqualTo("[\"백엔드 개발자\"]");

    jp.refreshFrom(null, null, "newhash", COLLECTED, null, null, null, null, null, null);

    assertThat(jp.getRoles()).isEqualTo("[\"백엔드 개발자\"]");
  }

  @Test
  void refreshFrom_doesNotOverwriteExistingRolesWithEmptyArray() {
    JobPosting jp = newPosting("https://example.com/job/12");
    String originalRoles = jp.getRoles();

    jp.refreshFrom(null, null, "newhash", COLLECTED, "[]", null, null, null, null, null);

    assertThat(jp.getRoles()).isEqualTo(originalRoles);
  }

  @Test
  void refreshFrom_enrichesNullExperienceLevelWithNewValue() {
    JobPosting jp = postingWithNullMetadata("https://example.com/job/13");

    jp.refreshFrom(null, null, "newhash", COLLECTED, null, null, "3년 이상", null, null, null);

    assertThat(jp.getExperienceLevel()).isEqualTo("3년 이상");
  }

  @Test
  void refreshFrom_doesNotOverwriteExistingExperienceLevelWithNull() {
    JobPosting jp = newPosting("https://example.com/job/14");
    assertThat(jp.getExperienceLevel()).isEqualTo("신입");

    jp.refreshFrom(null, null, "newhash", COLLECTED, null, null, null, null, null, null);

    assertThat(jp.getExperienceLevel()).isEqualTo("신입");
  }

  @Test
  void refreshFrom_enrichesNullEmploymentTypeWithNewValue() {
    JobPosting jp = postingWithNullMetadata("https://example.com/job/15");

    jp.refreshFrom(null, null, "newhash", COLLECTED, null, null, null, "정규직", null, null);

    assertThat(jp.getEmploymentType()).isEqualTo("정규직");
  }

  @Test
  void refreshFrom_enrichesNullLocationWithNewValue() {
    JobPosting jp = postingWithNullMetadata("https://example.com/job/16");

    jp.refreshFrom(null, null, "newhash", COLLECTED, null, null, null, null, "서울", null);

    assertThat(jp.getLocation()).isEqualTo("서울");
  }

  @Test
  void refreshFrom_doesNotOverwriteExistingLocationWithNull() {
    JobPosting jp = newPosting("https://example.com/job/17");
    assertThat(jp.getLocation()).isEqualTo("서울");

    jp.refreshFrom(null, null, "newhash", COLLECTED, null, null, null, null, null, null);

    assertThat(jp.getLocation()).isEqualTo("서울");
  }

  @Test
  void refreshFrom_enrichesAllNullMetadataAtOnce() {
    JobPosting jp = postingWithNullMetadata("https://example.com/job/18");

    jp.refreshFrom(
        LocalDate.of(2026, 8, 31),
        "새 설명",
        "newhash",
        COLLECTED,
        "[\"Backend Engineering\"]",
        "[\"Java\"]",
        "3년 이상",
        "정규직",
        "서울",
        null);

    assertThat(jp.getRoles()).isEqualTo("[\"Backend Engineering\"]");
    assertThat(jp.getSkills()).isEqualTo("[\"Java\"]");
    assertThat(jp.getExperienceLevel()).isEqualTo("3년 이상");
    assertThat(jp.getEmploymentType()).isEqualTo("정규직");
    assertThat(jp.getLocation()).isEqualTo("서울");
  }

  @Test
  void refreshFrom_sameContentHash_stillEnrichesMetadata() {
    // contentHash same doesn't block metadata enrichment
    JobPosting jp = postingWithNullMetadata("https://example.com/job/19");

    jp.refreshFrom(
        null,
        null,
        "hash",
        COLLECTED, // same hash as created with
        "[\"Backend Engineering\"]",
        null,
        "신입",
        "정규직",
        "서울",
        null);

    assertThat(jp.getRoles()).isEqualTo("[\"Backend Engineering\"]");
    assertThat(jp.getExperienceLevel()).isEqualTo("신입");
  }
}
