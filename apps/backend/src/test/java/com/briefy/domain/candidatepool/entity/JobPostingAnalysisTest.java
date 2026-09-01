package com.briefy.domain.candidatepool.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.briefy.domain.candidatepool.entity.analysis.ClassificationMethod;
import com.briefy.domain.candidatepool.entity.analysis.ClassificationStatus;
import com.briefy.domain.candidatepool.entity.analysis.ExperienceRequirementType;
import com.briefy.domain.candidatepool.entity.analysis.JobDomain;
import com.briefy.domain.candidatepool.entity.analysis.PostingScope;
import com.briefy.domain.candidatepool.entity.analysis.PostingTrack;
import com.briefy.domain.candidatepool.entity.analysis.PostingTrackListConverter;
import com.briefy.domain.candidatepool.entity.analysis.RecruitmentType;
import com.briefy.domain.candidatepool.entity.analysis.RoleGroupTag;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class JobPostingAnalysisTest {

  // ── pending 팩토리 ────────────────────────────────────────────────────────────

  @Test
  void pending_setsRequiredFieldsAndStatus() {
    JobPostingAnalysis analysis = JobPostingAnalysis.pending(42L, "abc123hash", "1.0.0");

    assertThat(analysis.getJobPostingId()).isEqualTo(42L);
    assertThat(analysis.getAnalysisInputHash()).isEqualTo("abc123hash");
    assertThat(analysis.getClassifierVersion()).isEqualTo("1.0.0");
    assertThat(analysis.getClassificationStatus()).isEqualTo(ClassificationStatus.PENDING);
    assertThat(analysis.getAttemptCount()).isZero();
    // 아직 분류되지 않은 필드는 null
    assertThat(analysis.getJobDomain()).isNull();
    assertThat(analysis.getAcceptsNewGrad()).isNull();
    assertThat(analysis.getClassifiedAt()).isNull();
  }

  // ── acceptsNewGrad nullable boolean 보존 ─────────────────────────────────────

  @Test
  void acceptsNewGrad_null_isPreserved_not_coerced_to_false() {
    // null은 판별 불가를 의미한다. false로 강제 변환하면 안 된다.
    JobPostingAnalysis analysis = JobPostingAnalysis.pending(1L, "h", "1.0.0");
    analysis.applyResult(
        JobDomain.IT,
        PostingScope.ROLE_SPECIFIC,
        List.of(RoleGroupTag.BACKEND),
        RecruitmentType.EXPERIENCED_HIRE,
        List.of(),
        null, // acceptsNewGrad = null
        3,
        null,
        ExperienceRequirementType.REQUIRED,
        null,
        ClassificationMethod.LLM,
        ClassificationStatus.SUCCEEDED,
        null,
        null,
        null,
        null,
        null,
        null,
        LocalDateTime.now());

    assertThat(analysis.getAcceptsNewGrad()).isNull();
  }

  @Test
  void acceptsNewGrad_true_isPreserved() {
    JobPostingAnalysis analysis = JobPostingAnalysis.pending(1L, "h", "1.0.0");
    analysis.applyResult(
        JobDomain.IT,
        PostingScope.ROLE_SPECIFIC,
        List.of(RoleGroupTag.BACKEND),
        RecruitmentType.OPEN_HIRE,
        List.of(),
        Boolean.TRUE, // acceptsNewGrad = true
        null,
        null,
        ExperienceRequirementType.NONE,
        null,
        ClassificationMethod.LLM,
        ClassificationStatus.SUCCEEDED,
        null,
        null,
        null,
        null,
        null,
        null,
        LocalDateTime.now());

    assertThat(analysis.getAcceptsNewGrad()).isTrue();
  }

  @Test
  void acceptsNewGrad_false_isPreserved() {
    JobPostingAnalysis analysis = JobPostingAnalysis.pending(1L, "h", "1.0.0");
    analysis.applyResult(
        JobDomain.IT,
        PostingScope.ROLE_SPECIFIC,
        List.of(RoleGroupTag.BACKEND),
        RecruitmentType.EXPERIENCED_HIRE,
        List.of(),
        Boolean.FALSE,
        3,
        null,
        ExperienceRequirementType.REQUIRED,
        null,
        ClassificationMethod.LLM,
        ClassificationStatus.SUCCEEDED,
        null,
        null,
        null,
        null,
        null,
        null,
        LocalDateTime.now());

    assertThat(analysis.getAcceptsNewGrad()).isFalse();
  }

  // ── descriptionTruncated nullable boolean 보존 ───────────────────────────────

  @Test
  void descriptionTruncated_null_isPreserved() {
    JobPostingAnalysis analysis = JobPostingAnalysis.pending(1L, "h", "1.0.0");
    analysis.applyResult(
        JobDomain.UNKNOWN,
        PostingScope.UNKNOWN,
        List.of(),
        RecruitmentType.UNKNOWN,
        List.of(),
        null,
        null,
        null,
        ExperienceRequirementType.UNKNOWN,
        null,
        ClassificationMethod.RULE_BASED,
        ClassificationStatus.FALLBACK,
        null,
        null,
        null,
        null,
        null, // descriptionTruncated = null
        null,
        LocalDateTime.now());

    assertThat(analysis.getDescriptionTruncated()).isNull();
  }

  // ── PostingTrack JSON 컨버터 round-trip ──────────────────────────────────────

  @Test
  void postingTrackListConverter_roundtrip() {
    PostingTrackListConverter converter = new PostingTrackListConverter();

    List<PostingTrack> tracks =
        List.of(
            new PostingTrack(
                "서버/백엔드",
                JobDomain.IT,
                List.of(RoleGroupTag.BACKEND),
                true,
                null,
                null,
                ExperienceRequirementType.NONE,
                RecruitmentType.OPEN_HIRE,
                "정규직",
                "공고 발췌 근거",
                false),
            new PostingTrack(
                "비개발 직군",
                JobDomain.NON_IT,
                List.of(RoleGroupTag.NON_DEV),
                null, // null boolean 보존
                null,
                null,
                ExperienceRequirementType.UNKNOWN,
                RecruitmentType.UNKNOWN,
                null,
                null,
                true));

    String json = converter.convertToDatabaseColumn(tracks);
    assertThat(json).isNotBlank().contains("BACKEND").contains("NON_DEV");

    List<PostingTrack> restored = converter.convertToEntityAttribute(json);
    assertThat(restored).hasSize(2);

    PostingTrack first = restored.get(0);
    assertThat(first.trackLabel()).isEqualTo("서버/백엔드");
    assertThat(first.jobDomain()).isEqualTo(JobDomain.IT);
    assertThat(first.roleGroups()).containsExactly(RoleGroupTag.BACKEND);
    assertThat(first.acceptsNewGrad()).isTrue();
    assertThat(first.evidence()).isEqualTo("공고 발췌 근거");
    assertThat(first.unknown()).isFalse();

    PostingTrack second = restored.get(1);
    assertThat(second.acceptsNewGrad()).isNull(); // null 보존 확인
    assertThat(second.unknown()).isTrue();
  }

  @Test
  void postingTrackListConverter_null_returnsNull() {
    PostingTrackListConverter converter = new PostingTrackListConverter();
    assertThat(converter.convertToDatabaseColumn(null)).isNull();
    assertThat(converter.convertToEntityAttribute(null)).isNull();
    assertThat(converter.convertToEntityAttribute("")).isNull();
  }

  @Test
  void postingTrackListConverter_emptyList_roundtrips() {
    PostingTrackListConverter converter = new PostingTrackListConverter();
    String json = converter.convertToDatabaseColumn(List.of());
    assertThat(json).isEqualTo("[]");
    List<PostingTrack> restored = converter.convertToEntityAttribute(json);
    assertThat(restored).isEmpty();
  }

  // ── applyResult 상태 전환 ────────────────────────────────────────────────────

  @Test
  void applyResult_clearsClaimFields() {
    JobPostingAnalysis analysis = JobPostingAnalysis.pending(1L, "h", "1.0.0");
    analysis.claim("token-uuid", LocalDateTime.now().plusMinutes(10));
    assertThat(analysis.getClaimToken()).isEqualTo("token-uuid");

    analysis.applyResult(
        JobDomain.IT,
        PostingScope.ROLE_SPECIFIC,
        List.of(RoleGroupTag.AI_ML),
        RecruitmentType.EXPERIENCED_HIRE,
        List.of(),
        false,
        2,
        null,
        ExperienceRequirementType.REQUIRED,
        null,
        ClassificationMethod.LLM,
        ClassificationStatus.SUCCEEDED,
        "AI Research Engineer 경력직",
        null,
        0.9,
        0.85,
        false,
        "gpt-4o",
        LocalDateTime.now());

    assertThat(analysis.getClaimToken()).isNull();
    assertThat(analysis.getLeaseUntil()).isNull();
    assertThat(analysis.getLastErrorCode()).isNull();
    assertThat(analysis.getClassificationStatus()).isEqualTo(ClassificationStatus.SUCCEEDED);
    assertThat(analysis.getModelName()).isEqualTo("gpt-4o");
  }

  @Test
  void markFailed_incrementsAttemptCount() {
    JobPostingAnalysis analysis = JobPostingAnalysis.pending(1L, "h", "1.0.0");
    analysis.claim("token", LocalDateTime.now().plusMinutes(5));

    analysis.markFailed("LLM_TIMEOUT", LocalDateTime.now().plusMinutes(30));

    assertThat(analysis.getClassificationStatus()).isEqualTo(ClassificationStatus.FAILED);
    assertThat(analysis.getLastErrorCode()).isEqualTo("LLM_TIMEOUT");
    assertThat(analysis.getNextRetryAt()).isNotNull();
    assertThat(analysis.getClaimToken()).isNull();
    assertThat(analysis.getAttemptCount()).isEqualTo(2); // claim+markFailed 각각 1씩
  }

  @Test
  void claim_setsProcessingStatus_andIncrementsAttemptCount() {
    JobPostingAnalysis analysis = JobPostingAnalysis.pending(1L, "h", "1.0.0");
    LocalDateTime leaseExpiry = LocalDateTime.now().plusMinutes(10);

    analysis.claim("uuid-token", leaseExpiry);

    assertThat(analysis.getClassificationStatus()).isEqualTo(ClassificationStatus.PROCESSING);
    assertThat(analysis.getClaimToken()).isEqualTo("uuid-token");
    assertThat(analysis.getLeaseUntil()).isEqualTo(leaseExpiry);
    assertThat(analysis.getAttemptCount()).isEqualTo(1);
  }

  // ── 기존 공고에 분석 행이 없는 경우 ────────────────────────────────────────────

  @Test
  void pending_factory_does_not_set_classifiedAt() {
    // 분석 행이 PENDING 상태일 때 classifiedAt은 null이어야 한다.
    JobPostingAnalysis analysis = JobPostingAnalysis.pending(99L, "hash", "1.0.0");
    assertThat(analysis.getClassifiedAt()).isNull();
    assertThat(analysis.getJobDomain()).isNull();
    assertThat(analysis.getPostingScope()).isNull();
    assertThat(analysis.getRoleGroups()).isNull();
  }
}
