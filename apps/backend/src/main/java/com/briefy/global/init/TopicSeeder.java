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
              "AI/LLM", "ai-llm", TopicCategory.TECH, "AI, LLM, Agent, generative AI news", 1),
          new TopicSeedEntry(
              "Backend/Spring",
              "backend-spring",
              TopicCategory.TECH,
              "Backend development, Spring Boot, Java, API, database, and server architecture",
              2),
          new TopicSeedEntry(
              "Cloud/AWS",
              "cloud-aws",
              TopicCategory.TECH,
              "Cloud infrastructure, AWS, deployment, DevOps, and scalability",
              3),
          new TopicSeedEntry(
              "Startup/Developer Trend",
              "startup-developer-trend",
              TopicCategory.BUSINESS,
              "Startup, developer productivity, engineering culture, and tech industry trends",
              4),
          new TopicSeedEntry(
              "Stock/Economy",
              "stock-economy",
              TopicCategory.FINANCE,
              "Stock market, economy, macro trends, and major business issues",
              5),
          new TopicSeedEntry(
              "Company/Industry",
              "company-industry",
              TopicCategory.BUSINESS,
              "Company updates, industry trends, product launches, and market movements",
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
