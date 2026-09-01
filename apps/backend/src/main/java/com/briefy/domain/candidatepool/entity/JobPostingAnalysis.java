package com.briefy.domain.candidatepool.entity;

import com.briefy.domain.candidatepool.entity.analysis.ClassificationMethod;
import com.briefy.domain.candidatepool.entity.analysis.ClassificationStatus;
import com.briefy.domain.candidatepool.entity.analysis.ExperienceRequirementType;
import com.briefy.domain.candidatepool.entity.analysis.JobDomain;
import com.briefy.domain.candidatepool.entity.analysis.PostingScope;
import com.briefy.domain.candidatepool.entity.analysis.PostingTrack;
import com.briefy.domain.candidatepool.entity.analysis.PostingTrackListConverter;
import com.briefy.domain.candidatepool.entity.analysis.RecruitmentType;
import com.briefy.domain.candidatepool.entity.analysis.RoleGroupTag;
import com.briefy.global.converter.JsonTextConverter;
import com.briefy.global.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Converter;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.util.List;

/**
 * {@link JobPosting}에 대한 optional 1:1 분류 분석 결과 테이블.
 *
 * <p>기존 공고(마이그레이션 V23 이전 수집분)에는 행이 없으며, 이는 정상이다. 분류 행이 없으면 Spring 추천 로직은 기존 키워드 기반으로 폴백한다.
 *
 * <p>설계 결정:
 *
 * <ul>
 *   <li>{@code jobPostingId}는 JPA 관계 대신 단순 컬럼으로 저장한다 (N+1 방지, 일괄 조회는 {@code
 *       findAllByJobPostingIdIn}으로 처리).
 *   <li>{@code JobPosting}에 양방향 {@code @OneToOne}을 추가하지 않는다.
 *   <li>FK 삭제 정책은 기존 {@code fk_jps_job_posting}과 동일하게 RESTRICT (기본값).
 *   <li>{@code acceptsNewGrad}는 {@code Boolean} (null 허용). null → false 강제 변환 금지.
 * </ul>
 */
@Entity
@Table(
    name = "job_posting_analyses",
    uniqueConstraints = {
      @UniqueConstraint(name = "uq_jpa_job_posting_id", columnNames = "job_posting_id")
    },
    indexes = {
      @Index(name = "idx_jpa_status", columnList = "classification_status"),
      @Index(name = "idx_jpa_next_retry_at", columnList = "next_retry_at"),
      @Index(name = "idx_jpa_classifier_version", columnList = "classifier_version")
    })
public class JobPostingAnalysis extends BaseTimeEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  /** {@link JobPosting#getId()}와 대응하는 UNIQUE FK 컬럼. JPA 관계를 사용하지 않아 N+1을 방지한다. */
  @Column(name = "job_posting_id", nullable = false, unique = true)
  private Long jobPostingId;

  // ── 분류 결과 ────────────────────────────────────────────────────────────────

  @Enumerated(EnumType.STRING)
  @Column(name = "job_domain", length = 20)
  private JobDomain jobDomain;

  @Enumerated(EnumType.STRING)
  @Column(name = "posting_scope", length = 30)
  private PostingScope postingScope;

  /** 역할 그룹 태그 배열. JSON 배열 TEXT로 저장. 예: {@code ["BACKEND","FULLSTACK"]}. */
  @Convert(converter = RoleGroupTagListConverter.class)
  @Column(name = "role_groups", columnDefinition = "TEXT")
  private List<RoleGroupTag> roleGroups;

  @Enumerated(EnumType.STRING)
  @Column(name = "recruitment_type", length = 30)
  private RecruitmentType recruitmentType;

  /** 다직무·공개채용 트랙 배열. JSON 배열 TEXT로 저장. 단일 직무 공고는 빈 배열 또는 null. */
  @Convert(converter = PostingTrackListConverter.class)
  @Column(name = "tracks", columnDefinition = "TEXT")
  private List<PostingTrack> tracks;

  /**
   * 신입 지원 가능 여부. null = 판별 불가.
   *
   * <p>이 값을 읽을 때 {@code false}로 강제 변환하지 않는다. nullable Boolean 사용.
   */
  @Column(name = "accepts_new_grad")
  private Boolean acceptsNewGrad;

  /** 필수 경력 최소 연수. null = 정보 없음. 0 = 경력 무관. 우대 경력은 저장하지 않는다. */
  @Column(name = "min_required_years")
  private Integer minRequiredYears;

  /** 필수 경력 최대 연수. null = 상한 없음. */
  @Column(name = "max_required_years")
  private Integer maxRequiredYears;

  @Enumerated(EnumType.STRING)
  @Column(name = "experience_requirement_type", length = 20)
  private ExperienceRequirementType experienceRequirementType;

  /** 우대 경력 원문. 필수 경력과 구분하여 저장. */
  @Column(name = "preferred_experience", columnDefinition = "TEXT")
  private String preferredExperience;

  // ── 분석 메타데이터 ───────────────────────────────────────────────────────────

  /** 분류 입력의 SHA-256 해시 (hex 64자). 동일 hash + classifierVersion이면 재분류하지 않는다. */
  @Column(name = "analysis_input_hash", length = 64)
  private String analysisInputHash;

  /** 프롬프트·규칙의 논리 버전. SemVer 권장. */
  @Column(name = "classifier_version", length = 20)
  private String classifierVersion;

  /** LLM 모델 식별자. */
  @Column(name = "model_name", length = 50)
  private String modelName;

  @Enumerated(EnumType.STRING)
  @Column(name = "classification_method", length = 20)
  private ClassificationMethod classificationMethod;

  @Enumerated(EnumType.STRING)
  @Column(name = "classification_status", nullable = false, length = 20)
  private ClassificationStatus classificationStatus;

  /** 분류 근거 원문. 진단용. */
  @Column(name = "evidence", columnDefinition = "TEXT")
  private String evidence;

  /** 불확실성 이유 목록. JSON 배열 TEXT로 저장. */
  @Column(name = "uncertainty_reasons", columnDefinition = "TEXT")
  private String uncertaintyReasons;

  /** LLM 자기보고 confidence (0.0~1.0). 진단용 전용. 추천 허용·제외 판단에 사용하지 않는다. */
  @Column(name = "confidence")
  private Double confidence;

  /** 입력 품질 점수 (0.0~1.0). 진단용. */
  @Column(name = "input_completeness")
  private Double inputCompleteness;

  /** description이 너무 길어서 잘린 경우 true. null = 알 수 없음. */
  @Column(name = "description_truncated")
  private Boolean descriptionTruncated;

  /** 분류가 완료된 시각. */
  @Column(name = "classified_at")
  private LocalDateTime classifiedAt;

  // ── 운영 필드 ─────────────────────────────────────────────────────────────────

  /** 분류 시도 횟수. */
  @Column(name = "attempt_count", nullable = false)
  private int attemptCount = 0;

  /** 다음 재시도 가능 시각. 지수 백오프. */
  @Column(name = "next_retry_at")
  private LocalDateTime nextRetryAt;

  /** 분산 처리 충돌 방지용 UUID. 클레임 시 발급, 완료 시 초기화. */
  @Column(name = "claim_token", length = 36)
  private String claimToken;

  /** 클레임 만료 시각. leaseUntil 이전에 완료되지 않으면 재클레임 가능. */
  @Column(name = "lease_until")
  private LocalDateTime leaseUntil;

  /** 마지막 오류 코드. 간결한 식별자만 저장 (민감 정보 제외). */
  @Column(name = "last_error_code", length = 50)
  private String lastErrorCode;

  protected JobPostingAnalysis() {}

  /** 신규 분석 행을 PENDING 상태로 생성한다. */
  public static JobPostingAnalysis pending(
      Long jobPostingId, String analysisInputHash, String classifierVersion) {
    JobPostingAnalysis a = new JobPostingAnalysis();
    a.jobPostingId = jobPostingId;
    a.analysisInputHash = analysisInputHash;
    a.classifierVersion = classifierVersion;
    a.classificationStatus = ClassificationStatus.PENDING;
    a.attemptCount = 0;
    return a;
  }

  /** 분류 결과로 필드를 갱신한다. 상태를 SUCCEEDED 또는 FALLBACK으로 전환한다. */
  public void applyResult(
      JobDomain jobDomain,
      PostingScope postingScope,
      List<RoleGroupTag> roleGroups,
      RecruitmentType recruitmentType,
      List<PostingTrack> tracks,
      Boolean acceptsNewGrad,
      Integer minRequiredYears,
      Integer maxRequiredYears,
      ExperienceRequirementType experienceRequirementType,
      String preferredExperience,
      ClassificationMethod method,
      ClassificationStatus status,
      String evidence,
      String uncertaintyReasons,
      Double confidence,
      Double inputCompleteness,
      Boolean descriptionTruncated,
      String modelName,
      LocalDateTime classifiedAt) {
    this.jobDomain = jobDomain;
    this.postingScope = postingScope;
    this.roleGroups = roleGroups;
    this.recruitmentType = recruitmentType;
    this.tracks = tracks;
    this.acceptsNewGrad = acceptsNewGrad;
    this.minRequiredYears = minRequiredYears;
    this.maxRequiredYears = maxRequiredYears;
    this.experienceRequirementType = experienceRequirementType;
    this.preferredExperience = preferredExperience;
    this.classificationMethod = method;
    this.classificationStatus = status;
    this.evidence = evidence;
    this.uncertaintyReasons = uncertaintyReasons;
    this.confidence = confidence;
    this.inputCompleteness = inputCompleteness;
    this.descriptionTruncated = descriptionTruncated;
    this.modelName = modelName;
    this.classifiedAt = classifiedAt;
    this.claimToken = null;
    this.leaseUntil = null;
    this.lastErrorCode = null;
  }

  /** 분류 실패 처리. 재시도 일정을 설정한다. */
  public void markFailed(String errorCode, LocalDateTime nextRetryAt) {
    this.classificationStatus = ClassificationStatus.FAILED;
    this.lastErrorCode = errorCode;
    this.nextRetryAt = nextRetryAt;
    this.claimToken = null;
    this.leaseUntil = null;
    this.attemptCount++;
  }

  /**
   * 분류 입력이 변경되어 재분류가 필요할 때 PENDING으로 초기화한다.
   *
   * <p>이전에 PROCESSING 중이던 claim도 무효화하여 오래된 응답이 덮어쓰지 못하게 한다. 이전 분류 결과 필드도 모두 지운다.
   */
  public void resetForNewInput(String newAnalysisInputHash, String newClassifierVersion) {
    this.analysisInputHash = newAnalysisInputHash;
    this.classifierVersion = newClassifierVersion;
    this.classificationStatus = ClassificationStatus.PENDING;
    this.claimToken = null;
    this.leaseUntil = null;
    this.nextRetryAt = null;
    this.attemptCount = 0;
    this.jobDomain = null;
    this.postingScope = null;
    this.roleGroups = null;
    this.recruitmentType = null;
    this.tracks = null;
    this.acceptsNewGrad = null;
    this.minRequiredYears = null;
    this.maxRequiredYears = null;
    this.experienceRequirementType = null;
    this.preferredExperience = null;
    this.classificationMethod = null;
    this.classificationStatus = ClassificationStatus.PENDING;
    this.evidence = null;
    this.uncertaintyReasons = null;
    this.confidence = null;
    this.inputCompleteness = null;
    this.descriptionTruncated = null;
    this.classifiedAt = null;
    this.lastErrorCode = null;
  }

  /** 클레임 획득. 작업자가 처리를 시작할 때 호출한다. */
  public void claim(String claimToken, LocalDateTime leaseUntil) {
    this.classificationStatus = ClassificationStatus.PROCESSING;
    this.claimToken = claimToken;
    this.leaseUntil = leaseUntil;
    this.attemptCount++;
  }

  // ── Getters ──────────────────────────────────────────────────────────────────

  public Long getId() {
    return id;
  }

  public Long getJobPostingId() {
    return jobPostingId;
  }

  public JobDomain getJobDomain() {
    return jobDomain;
  }

  public PostingScope getPostingScope() {
    return postingScope;
  }

  public List<RoleGroupTag> getRoleGroups() {
    return roleGroups;
  }

  public RecruitmentType getRecruitmentType() {
    return recruitmentType;
  }

  public List<PostingTrack> getTracks() {
    return tracks;
  }

  public Boolean getAcceptsNewGrad() {
    return acceptsNewGrad;
  }

  public Integer getMinRequiredYears() {
    return minRequiredYears;
  }

  public Integer getMaxRequiredYears() {
    return maxRequiredYears;
  }

  public ExperienceRequirementType getExperienceRequirementType() {
    return experienceRequirementType;
  }

  public String getPreferredExperience() {
    return preferredExperience;
  }

  public String getAnalysisInputHash() {
    return analysisInputHash;
  }

  public String getClassifierVersion() {
    return classifierVersion;
  }

  public String getModelName() {
    return modelName;
  }

  public ClassificationMethod getClassificationMethod() {
    return classificationMethod;
  }

  public ClassificationStatus getClassificationStatus() {
    return classificationStatus;
  }

  public String getEvidence() {
    return evidence;
  }

  public String getUncertaintyReasons() {
    return uncertaintyReasons;
  }

  public Double getConfidence() {
    return confidence;
  }

  public Double getInputCompleteness() {
    return inputCompleteness;
  }

  public Boolean getDescriptionTruncated() {
    return descriptionTruncated;
  }

  public LocalDateTime getClassifiedAt() {
    return classifiedAt;
  }

  public int getAttemptCount() {
    return attemptCount;
  }

  public LocalDateTime getNextRetryAt() {
    return nextRetryAt;
  }

  public String getClaimToken() {
    return claimToken;
  }

  public LocalDateTime getLeaseUntil() {
    return leaseUntil;
  }

  public String getLastErrorCode() {
    return lastErrorCode;
  }

  // ── 내부 전용 컨버터 ──────────────────────────────────────────────────────────

  @Converter
  static class RoleGroupTagListConverter extends JsonTextConverter<List<RoleGroupTag>> {
    RoleGroupTagListConverter() {
      super(MAPPER.getTypeFactory().constructCollectionType(List.class, RoleGroupTag.class));
    }
  }
}
