package com.briefy.domain.candidatepool.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.briefy.config.ClassificationMode;
import com.briefy.config.ClassificationProperties;
import com.briefy.config.JpaConfig;
import com.briefy.domain.candidatepool.dto.ClassificationWorkItem;
import com.briefy.domain.candidatepool.entity.JobPosting;
import com.briefy.domain.candidatepool.entity.JobPostingAnalysis;
import com.briefy.domain.candidatepool.entity.JobPostingSource;
import com.briefy.domain.candidatepool.entity.analysis.ClassificationStatus;
import com.briefy.domain.candidatepool.repository.JobPostingAnalysisRepository;
import com.briefy.domain.candidatepool.repository.JobPostingRepository;
import com.briefy.domain.candidatepool.repository.JobPostingSourceRepository;
import com.briefy.infra.agent.dto.AgentClassificationResult;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ClassificationWorkerPersistenceService} 통합 테스트.
 *
 * <p>{@code @Transactional(NOT_SUPPORTED)}로 선언하여 각 테스트가 트랜잭션 없이 실행된다. 서비스 메서드의 {@code REQUIRES_NEW}
 * 트랜잭션이 실제로 커밋되고, 다음 조회에서 커밋된 결과를 볼 수 있다. {@code @AfterEach}에서 직접 DB를 정리한다.
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(
    properties = {
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
      "briefy.classifier.version=1.0.0"
    })
@Import({
  ClassificationWorkerPersistenceServiceTest.Config.class,
  ClassificationWorkerPersistenceService.class,
  JpaConfig.class
})
class ClassificationWorkerPersistenceServiceTest {

  @TestConfiguration
  static class Config {
    @Bean
    ClassificationProperties classificationProperties() {
      return new ClassificationProperties(
          ClassificationMode.SHADOW,
          "1.0.0",
          new ClassificationProperties.Worker(60_000, 100, 600, 5, 2, 90, 700, 5, 60));
    }
  }

  @Autowired private ClassificationWorkerPersistenceService sut;
  @Autowired private JobPostingAnalysisRepository analysisRepository;
  @Autowired private JobPostingRepository postingRepository;
  @Autowired private JobPostingSourceRepository postingSourceRepository;

  @AfterEach
  void cleanUp() {
    analysisRepository.deleteAll();
    postingSourceRepository.deleteAll();
    postingRepository.deleteAll();
  }

  // ── 헬퍼 ──────────────────────────────────────────────────────────────────────

  private JobPosting savedPosting(String title) {
    String url = "https://saramin.co.kr/" + System.nanoTime();
    JobPosting posting =
        JobPosting.create(
            title,
            "테스트회사",
            "saramin",
            url,
            "서울",
            null,
            "Java Spring 개발자를 모집합니다.",
            "[\"백엔드\"]",
            null,
            "정규직",
            "신입/경력",
            url,
            LocalDate.now(),
            null);
    postingRepository.save(posting);
    JobPostingSource source =
        JobPostingSource.create(
            posting, "saramin", null, url, url, null, LocalDateTime.now(), LocalDate.now());
    postingSourceRepository.save(source);
    return posting;
  }

  private JobPostingAnalysis savedPendingAnalysis(Long postingId, String hash) {
    JobPostingAnalysis a = JobPostingAnalysis.pending(postingId, hash, "1.0.0");
    return analysisRepository.save(a);
  }

  // ── claimBatch ────────────────────────────────────────────────────────────────

  @Test
  void claimBatch_returnsPendingItemsAsClaimed() {
    JobPosting posting = savedPosting("백엔드 개발자");
    savedPendingAnalysis(posting.getId(), "hash-001");

    List<ClassificationWorkItem> items = sut.claimBatch(10);

    assertThat(items).hasSize(1);
    ClassificationWorkItem item = items.get(0);
    assertThat(item.postingId()).isEqualTo(posting.getId());
    assertThat(item.claimToken()).isNotBlank();
    assertThat(item.title()).isEqualTo("백엔드 개발자");

    Optional<JobPostingAnalysis> updated = analysisRepository.findById(item.analysisId());
    assertThat(updated).isPresent();
    assertThat(updated.get().getClassificationStatus()).isEqualTo(ClassificationStatus.PROCESSING);
    assertThat(updated.get().getClaimToken()).isEqualTo(item.claimToken());
  }

  @Test
  void claimBatch_skipsWhenPostingNotFound() {
    // 공고 없이 분석 행만 존재
    JobPostingAnalysis orphan = JobPostingAnalysis.pending(99999L, "hash-orphan", "1.0.0");
    analysisRepository.save(orphan);

    List<ClassificationWorkItem> items = sut.claimBatch(10);

    // postingId=99999 조회 시 없으므로 스냅샷 생성 제외
    assertThat(items).isEmpty();
  }

  @Test
  void claimBatch_respectsMaxAttemptsLimit() {
    JobPosting posting = savedPosting("경력 개발자");
    JobPostingAnalysis analysis = savedPendingAnalysis(posting.getId(), "hash-002");
    // attemptCount를 maxAttempts(5) 이상으로 만들기
    for (int i = 0; i < 5; i++) {
      analysis.claim("tok-" + i, LocalDateTime.now().plusSeconds(60));
      analysis.markFailed("ERR", LocalDateTime.now().minusSeconds(1));
    }
    analysisRepository.save(analysis);

    List<ClassificationWorkItem> items = sut.claimBatch(10);
    assertThat(items).isEmpty();
  }

  // ── applyResult ───────────────────────────────────────────────────────────────

  @Test
  void applyResult_savesSucceededStatus() {
    JobPosting posting = savedPosting("풀스택 개발자");
    savedPendingAnalysis(posting.getId(), "hash-003");

    List<ClassificationWorkItem> items = sut.claimBatch(1);
    assertThat(items).hasSize(1);
    ClassificationWorkItem item = items.get(0);

    AgentClassificationResult result =
        new AgentClassificationResult(
            posting.getId(),
            "hash-003",
            "IT",
            "SINGLE_ROLE",
            List.of("BACKEND"),
            "REGULAR",
            List.of(),
            null,
            null,
            null,
            "REQUIRED",
            null,
            "LLM",
            "SUCCEEDED",
            "evidence",
            List.of(),
            0.9,
            0.8,
            false);

    boolean applied = sut.applyResult(item, result, LocalDateTime.now());

    assertThat(applied).isTrue();
    Optional<JobPostingAnalysis> updated = analysisRepository.findById(item.analysisId());
    assertThat(updated).isPresent();
    assertThat(updated.get().getClassificationStatus()).isEqualTo(ClassificationStatus.SUCCEEDED);
    assertThat(updated.get().getClaimToken()).isNull();
  }

  @Test
  void applyResult_savesConflictStatus_whenHashMismatch() {
    JobPosting posting = savedPosting("데이터 엔지니어");
    savedPendingAnalysis(posting.getId(), "hash-004");

    List<ClassificationWorkItem> items = sut.claimBatch(1);
    ClassificationWorkItem item = items.get(0);

    AgentClassificationResult result =
        new AgentClassificationResult(
            posting.getId(),
            "WRONG_HASH",
            "IT",
            "SINGLE_ROLE",
            List.of("DATA"),
            "REGULAR",
            List.of(),
            null,
            null,
            null,
            null,
            null,
            "LLM",
            "SUCCEEDED",
            "evidence",
            List.of(),
            0.9,
            0.8,
            false);

    boolean applied = sut.applyResult(item, result, LocalDateTime.now());

    assertThat(applied).isFalse();
    Optional<JobPostingAnalysis> updated = analysisRepository.findById(item.analysisId());
    assertThat(updated.get().getClassificationStatus()).isEqualTo(ClassificationStatus.CONFLICT);
  }

  @Test
  void applyResult_returnsFalse_whenClaimTokenMismatch() {
    JobPosting posting = savedPosting("DevOps 엔지니어");
    savedPendingAnalysis(posting.getId(), "hash-005");

    List<ClassificationWorkItem> items = sut.claimBatch(1);
    ClassificationWorkItem item = items.get(0);

    // 다른 워커가 claim token을 변경
    JobPostingAnalysis analysis = analysisRepository.findById(item.analysisId()).get();
    analysis.claim("new-token-from-other-worker", LocalDateTime.now().plusSeconds(300));
    analysisRepository.save(analysis);

    AgentClassificationResult result =
        new AgentClassificationResult(
            posting.getId(),
            "hash-005",
            "IT",
            "SINGLE_ROLE",
            List.of("DEVOPS"),
            "REGULAR",
            List.of(),
            null,
            null,
            null,
            null,
            null,
            "LLM",
            "SUCCEEDED",
            null,
            List.of(),
            0.9,
            0.8,
            false);

    boolean applied = sut.applyResult(item, result, LocalDateTime.now());

    assertThat(applied).isFalse();
    Optional<JobPostingAnalysis> updated = analysisRepository.findById(item.analysisId());
    // 다른 워커의 claim이 활성 → PROCESSING 유지
    assertThat(updated.get().getClassificationStatus()).isEqualTo(ClassificationStatus.PROCESSING);
  }

  // ── markFailed ────────────────────────────────────────────────────────────────

  @Test
  void markFailed_setsFailedStatusWithNextRetryAt() {
    JobPosting posting = savedPosting("iOS 개발자");
    savedPendingAnalysis(posting.getId(), "hash-006");

    List<ClassificationWorkItem> items = sut.claimBatch(1);
    ClassificationWorkItem item = items.get(0);

    sut.markFailed(item, "AGENT_TIMEOUT");

    Optional<JobPostingAnalysis> updated = analysisRepository.findById(item.analysisId());
    assertThat(updated).isPresent();
    assertThat(updated.get().getClassificationStatus()).isEqualTo(ClassificationStatus.FAILED);
    assertThat(updated.get().getLastErrorCode()).isEqualTo("AGENT_TIMEOUT");
    assertThat(updated.get().getNextRetryAt()).isAfter(LocalDateTime.now());
    assertThat(updated.get().getClaimToken()).isNull();
  }

  @Test
  void markFailed_isNoOp_whenClaimTokenMismatch() {
    JobPosting posting = savedPosting("안드로이드 개발자");
    savedPendingAnalysis(posting.getId(), "hash-007");

    List<ClassificationWorkItem> items = sut.claimBatch(1);
    ClassificationWorkItem item = items.get(0);

    // 다른 워커가 재클레임
    JobPostingAnalysis analysis = analysisRepository.findById(item.analysisId()).get();
    analysis.claim("new-token-2", LocalDateTime.now().plusSeconds(300));
    analysisRepository.save(analysis);

    sut.markFailed(item, "STALE_MARK");

    Optional<JobPostingAnalysis> updated = analysisRepository.findById(item.analysisId());
    assertThat(updated.get().getClassificationStatus()).isEqualTo(ClassificationStatus.PROCESSING);
  }

  // ── recoverExpiredLeases ──────────────────────────────────────────────────────

  @Test
  void recoverExpiredLeases_resets_expiredProcessingToPending() {
    JobPosting posting = savedPosting("QA 엔지니어");
    JobPostingAnalysis analysis = savedPendingAnalysis(posting.getId(), "hash-008");
    analysis.claim("old-token", LocalDateTime.now().minusSeconds(1)); // 이미 만료
    analysisRepository.save(analysis);

    int recovered = sut.recoverExpiredLeases();

    assertThat(recovered).isEqualTo(1);
    Optional<JobPostingAnalysis> updated = analysisRepository.findById(analysis.getId());
    assertThat(updated.get().getClassificationStatus()).isEqualTo(ClassificationStatus.PENDING);
    assertThat(updated.get().getClaimToken()).isNull();
  }

  @Test
  void recoverExpiredLeases_doesNotReset_nonExpired() {
    JobPosting posting = savedPosting("보안 엔지니어");
    JobPostingAnalysis analysis = savedPendingAnalysis(posting.getId(), "hash-009");
    analysis.claim("valid-token", LocalDateTime.now().plusSeconds(300));
    analysisRepository.save(analysis);

    int recovered = sut.recoverExpiredLeases();

    assertThat(recovered).isEqualTo(0);
    Optional<JobPostingAnalysis> updated = analysisRepository.findById(analysis.getId());
    assertThat(updated.get().getClassificationStatus()).isEqualTo(ClassificationStatus.PROCESSING);
  }

  // ── 수집 성공 + 분류 실패: 원본 보존 확인 ────────────────────────────────────────

  @Test
  void originalPostingPreserved_whenClassificationFails() {
    JobPosting posting = savedPosting("ML 엔지니어");
    String originalTitle = posting.getTitle();
    savedPendingAnalysis(posting.getId(), "hash-010");

    List<ClassificationWorkItem> items = sut.claimBatch(1);
    sut.markFailed(items.get(0), "AGENT_ERROR");

    Optional<JobPosting> preserved = postingRepository.findById(posting.getId());
    assertThat(preserved).isPresent();
    assertThat(preserved.get().getTitle()).isEqualTo(originalTitle);
  }
}
