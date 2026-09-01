package com.briefy.domain.candidatepool.service;

import com.briefy.config.ClassificationProperties;
import com.briefy.domain.candidatepool.dto.ClassificationWorkItem;
import com.briefy.domain.candidatepool.entity.JobPosting;
import com.briefy.domain.candidatepool.entity.JobPostingAnalysis;
import com.briefy.domain.candidatepool.entity.analysis.ClassificationMethod;
import com.briefy.domain.candidatepool.entity.analysis.ClassificationStatus;
import com.briefy.domain.candidatepool.entity.analysis.ExperienceRequirementType;
import com.briefy.domain.candidatepool.entity.analysis.JobDomain;
import com.briefy.domain.candidatepool.entity.analysis.PostingScope;
import com.briefy.domain.candidatepool.entity.analysis.PostingTrack;
import com.briefy.domain.candidatepool.entity.analysis.RecruitmentType;
import com.briefy.domain.candidatepool.entity.analysis.RoleGroupTag;
import com.briefy.domain.candidatepool.repository.JobPostingAnalysisRepository;
import com.briefy.domain.candidatepool.repository.JobPostingRepository;
import com.briefy.infra.agent.dto.AgentClassificationResult;
import com.briefy.infra.agent.dto.AgentClassifySourceRef;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 분류 작업자의 모든 DB 쓰기를 짧은 독립 트랜잭션으로 실행한다.
 *
 * <p>Agent HTTP 호출은 이 클래스 밖(호출자)에서 수행한다. 트랜잭션이 열린 채로 HTTP를 호출하면 DB 커넥션을 불필요하게 점유하므로 금지한다.
 */
@Service
public class ClassificationWorkerPersistenceService {

  private static final Logger log =
      LoggerFactory.getLogger(ClassificationWorkerPersistenceService.class);

  private final JobPostingAnalysisRepository analysisRepository;
  private final JobPostingRepository postingRepository;
  private final ClassificationProperties properties;

  public ClassificationWorkerPersistenceService(
      JobPostingAnalysisRepository analysisRepository,
      JobPostingRepository postingRepository,
      ClassificationProperties properties) {
    this.analysisRepository = analysisRepository;
    this.postingRepository = postingRepository;
    this.properties = properties;
  }

  /**
   * PENDING/재시도 가능한 FAILED 항목을 batch로 claim하여 작업 스냅샷 목록을 반환한다.
   *
   * <p>트랜잭션이 커밋되면 클레임 토큰과 lease 시각이 DB에 저장된다. 반환된 {@link ClassificationWorkItem} 목록은 트랜잭션 밖에서 사용한다.
   *
   * @param maxItems 한 번에 claim할 최대 항목 수
   * @return claim된 항목 스냅샷 목록 (트랜잭션 밖에서 사용 가능)
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public List<ClassificationWorkItem> claimBatch(int maxItems) {
    LocalDateTime now = LocalDateTime.now();
    LocalDateTime leaseUntil = now.plusSeconds(properties.worker().leaseSeconds());
    int maxAttempts = properties.worker().maxAttempts();

    List<JobPostingAnalysis> candidates =
        analysisRepository.findRetryableWithLimit(now, maxAttempts, PageRequest.of(0, maxItems));

    if (candidates.isEmpty()) {
      return Collections.emptyList();
    }

    List<Long> postingIds = candidates.stream().map(JobPostingAnalysis::getJobPostingId).toList();

    Map<Long, JobPosting> postingMap =
        postingRepository.findAllById(postingIds).stream()
            .collect(Collectors.toMap(JobPosting::getId, Function.identity()));

    List<ClassificationWorkItem> workItems = new ArrayList<>();
    for (JobPostingAnalysis analysis : candidates) {
      JobPosting posting = postingMap.get(analysis.getJobPostingId());
      if (posting == null) {
        log.warn(
            "JobPosting not found for analysisId={} postingId={}, skipping",
            analysis.getId(),
            analysis.getJobPostingId());
        continue;
      }

      String token = UUID.randomUUID().toString();
      analysis.claim(token, leaseUntil);

      List<AgentClassifySourceRef> sourceRefs = buildSourceRefs(posting);
      workItems.add(
          new ClassificationWorkItem(
              analysis.getId(),
              posting.getId(),
              token,
              analysis.getAnalysisInputHash(),
              analysis.getClassifierVersion(),
              posting.getTitle(),
              posting.getCompany(),
              posting.getDescription(),
              posting.getRoles(),
              posting.getExperienceLevel(),
              posting.getEmploymentType(),
              analysis.getDescriptionTruncated(),
              sourceRefs));
    }

    log.info(
        "Claimed {} work items (requested={} maxAttempts={})",
        workItems.size(),
        maxItems,
        maxAttempts);
    return workItems;
  }

  /**
   * Agent 분류 결과를 검증하고 저장한다.
   *
   * <p>검증 실패 시 CONFLICT 상태로 저장하고 {@code false}를 반환한다. claim token 불일치 시 오래된 응답으로 간주하여 저장을 건너뛴다.
   *
   * @param workItem claim 시점 스냅샷
   * @param result Agent 분류 결과
   * @param classifiedAt 분류 완료 시각
   * @return 정상 저장 여부
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean applyResult(
      ClassificationWorkItem workItem,
      AgentClassificationResult result,
      LocalDateTime classifiedAt) {

    Optional<JobPostingAnalysis> opt = analysisRepository.findById(workItem.analysisId());
    if (opt.isEmpty()) {
      log.warn("Analysis row not found for analysisId={}, skipping", workItem.analysisId());
      return false;
    }

    JobPostingAnalysis analysis = opt.get();

    // Stale claim check: 다른 워커가 재클레임했거나 입력이 변경된 경우
    if (!workItem.claimToken().equals(analysis.getClaimToken())) {
      log.warn(
          "Stale claim for analysisId={}: expected={} current={}",
          workItem.analysisId(),
          workItem.claimToken(),
          analysis.getClaimToken());
      return false;
    }

    // Hash 불일치 → CONFLICT
    if (!workItem.analysisInputHash().equals(result.analysisInputHash())) {
      log.warn(
          "Hash conflict for analysisId={} postingId={}: sent={} echoed={}",
          workItem.analysisId(),
          workItem.postingId(),
          workItem.analysisInputHash(),
          result.analysisInputHash());
      analysis.applyResult(
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          toMethod(result.method()),
          ClassificationStatus.CONFLICT,
          result.evidence(),
          joinList(result.uncertaintyReasons()),
          result.confidence(),
          result.inputCompleteness(),
          result.descriptionTruncated(),
          null,
          classifiedAt);
      return false;
    }

    ClassificationStatus status = toStatus(result.status());
    analysis.applyResult(
        toEnum(JobDomain.class, result.jobDomain()),
        toEnum(PostingScope.class, result.postingScope()),
        toRoleGroups(result.roleGroups()),
        toEnum(RecruitmentType.class, result.recruitmentType()),
        toTracks(result.tracks()),
        result.acceptsNewGrad(),
        result.minRequiredYears(),
        result.maxRequiredYears(),
        toEnum(ExperienceRequirementType.class, result.experienceRequirementType()),
        result.preferredExperience(),
        toMethod(result.method()),
        status,
        result.evidence(),
        joinList(result.uncertaintyReasons()),
        result.confidence(),
        result.inputCompleteness(),
        result.descriptionTruncated(),
        null,
        classifiedAt);

    log.info(
        "Applied result for analysisId={} postingId={} status={}",
        workItem.analysisId(),
        workItem.postingId(),
        status);
    return true;
  }

  /**
   * 분류 실패를 기록하고 지수 백오프로 재시도 일정을 설정한다.
   *
   * <p>claim token이 이미 변경된 경우(stale) 저장을 건너뛴다.
   *
   * @param workItem claim 시점 스냅샷
   * @param errorCode 오류 식별자 (50자 이내 권고)
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void markFailed(ClassificationWorkItem workItem, String errorCode) {
    Optional<JobPostingAnalysis> opt = analysisRepository.findById(workItem.analysisId());
    if (opt.isEmpty()) {
      log.warn(
          "Analysis row not found for analysisId={}, cannot mark failed", workItem.analysisId());
      return;
    }

    JobPostingAnalysis analysis = opt.get();
    if (!workItem.claimToken().equals(analysis.getClaimToken())) {
      log.warn("Stale claim on markFailed for analysisId={}, skipping", workItem.analysisId());
      return;
    }

    // 지수 백오프: backoffBase * 2^(attemptCount-1), 최대 1시간
    int attempt = analysis.getAttemptCount();
    long backoffSeconds =
        (long) properties.worker().backoffBaseSeconds() * (1L << Math.min(attempt, 6));
    LocalDateTime nextRetryAt = LocalDateTime.now().plusSeconds(backoffSeconds);

    analysis.markFailed(errorCode, nextRetryAt);
    log.warn(
        "Marked failed for analysisId={} errorCode={} nextRetryAt={}",
        workItem.analysisId(),
        errorCode,
        nextRetryAt);
  }

  /**
   * 만료된 PROCESSING 행을 PENDING으로 일괄 복구한다.
   *
   * @return 복구된 행 수
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public int recoverExpiredLeases() {
    int recovered = analysisRepository.resetExpiredLeases(LocalDateTime.now());
    if (recovered > 0) {
      log.info("Recovered {} expired classification leases → PENDING", recovered);
    }
    return recovered;
  }

  // ── 변환 헬퍼 ──────────────────────────────────────────────────────────────────

  private List<AgentClassifySourceRef> buildSourceRefs(JobPosting posting) {
    if (posting.getUrl() == null || posting.getUrl().isBlank()) {
      return Collections.emptyList();
    }
    return List.of(new AgentClassifySourceRef(posting.getSource(), posting.getUrl(), null, null));
  }

  private <E extends Enum<E>> E toEnum(Class<E> type, String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return Enum.valueOf(type, value.toUpperCase());
    } catch (IllegalArgumentException e) {
      log.warn("Unknown enum value '{}' for {}", value, type.getSimpleName());
      return null;
    }
  }

  private ClassificationStatus toStatus(String value) {
    if (value == null) return ClassificationStatus.FAILED;
    return switch (value.toUpperCase()) {
      case "SUCCEEDED" -> ClassificationStatus.SUCCEEDED;
      case "FALLBACK" -> ClassificationStatus.FALLBACK;
      default -> ClassificationStatus.FAILED;
    };
  }

  private ClassificationMethod toMethod(String value) {
    if (value == null) return null;
    return switch (value.toUpperCase()) {
      case "LLM" -> ClassificationMethod.LLM;
      case "RULE_BASED" -> ClassificationMethod.RULE_BASED;
      default -> null;
    };
  }

  private List<RoleGroupTag> toRoleGroups(List<String> values) {
    if (values == null || values.isEmpty()) return Collections.emptyList();
    List<RoleGroupTag> result = new ArrayList<>();
    for (String v : values) {
      RoleGroupTag tag = toEnum(RoleGroupTag.class, v);
      if (tag != null) result.add(tag);
    }
    return result;
  }

  private List<PostingTrack> toTracks(List<com.briefy.infra.agent.dto.AgentClassifyTrack> tracks) {
    if (tracks == null || tracks.isEmpty()) return Collections.emptyList();
    List<PostingTrack> result = new ArrayList<>();
    for (var t : tracks) {
      PostingTrack track =
          new PostingTrack(
              t.trackLabel(),
              toEnum(JobDomain.class, t.jobDomain()),
              toRoleGroups(t.roleGroups()),
              t.acceptsNewGrad(),
              t.minRequiredYears(),
              t.maxRequiredYears(),
              toEnum(ExperienceRequirementType.class, t.experienceRequirementType()),
              toEnum(RecruitmentType.class, t.recruitmentType()),
              t.employmentType(),
              t.evidence(),
              t.unknown());
      result.add(track);
    }
    return result;
  }

  private String joinList(List<String> values) {
    if (values == null || values.isEmpty()) return null;
    return String.join(",", values);
  }
}
