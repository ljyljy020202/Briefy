package com.briefy.global.init;

import com.briefy.domain.topic.entity.Topic;
import com.briefy.domain.topic.entity.TopicCategory;
import com.briefy.domain.topic.repository.TopicRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("local")
public class TopicSeeder implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(TopicSeeder.class);

  private static final List<TopicSeedEntry> SEED_DATA =
      List.of(
          new TopicSeedEntry(
              "Target Role",
              "target-role",
              TopicCategory.JOB_PREFERENCE,
              "목표 직무 (예: 백엔드 개발자, 풀스택 개발자)",
              1),
          new TopicSeedEntry(
              "Target Companies",
              "target-companies",
              TopicCategory.JOB_PREFERENCE,
              "관심 회사 (예: 네이버, 카카오, 라인)",
              2),
          new TopicSeedEntry(
              "Skills / Competencies",
              "skills",
              TopicCategory.JOB_PREFERENCE,
              "핵심 스킬 (예: Spring Boot, Java, Kotlin)",
              3),
          new TopicSeedEntry(
              "Location", "location", TopicCategory.JOB_PREFERENCE, "희망 근무지 (예: 서울, 판교)", 4),
          new TopicSeedEntry(
              "Experience Level",
              "experience-level",
              TopicCategory.JOB_PREFERENCE,
              "경력 수준 (예: 신입, 3년 이상)",
              5),
          new TopicSeedEntry(
              "Employment Type",
              "employment-type",
              TopicCategory.JOB_PREFERENCE,
              "고용 형태 (예: 정규직, 계약직)",
              6));

  private final TopicRepository topicRepository;

  public TopicSeeder(TopicRepository topicRepository) {
    this.topicRepository = topicRepository;
  }

  @Override
  public void run(ApplicationArguments args) {
    seed();
  }

  @Transactional
  void seed() {
    int inserted = 0;
    for (TopicSeedEntry entry : SEED_DATA) {
      if (topicRepository.findBySlug(entry.slug()).isEmpty()) {
        topicRepository.save(
            new Topic(
                entry.name(),
                entry.slug(),
                entry.category(),
                entry.description(),
                entry.displayOrder()));
        inserted++;
      } else {
        log.debug("Topic seed skipped (slug already exists): {}", entry.slug());
      }
    }
    if (inserted > 0) {
      log.info("Topic seed: {} topic(s) inserted", inserted);
    }
  }

  private record TopicSeedEntry(
      String name, String slug, TopicCategory category, String description, int displayOrder) {}
}
