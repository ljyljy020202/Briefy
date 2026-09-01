package com.briefy.domain.briefing.recommendation;

import com.briefy.config.ClassificationMode;
import com.briefy.domain.briefing.policy.ExperiencePolicy;
import com.briefy.domain.briefing.policy.RoleGroup;
import com.briefy.domain.candidatepool.entity.JobPostingAnalysis;
import com.briefy.domain.candidatepool.entity.analysis.ClassificationStatus;
import com.briefy.domain.candidatepool.entity.analysis.ExperienceRequirementType;
import com.briefy.domain.candidatepool.entity.analysis.JobDomain;
import com.briefy.domain.candidatepool.entity.analysis.PostingScope;
import com.briefy.domain.candidatepool.entity.analysis.PostingTrack;
import com.briefy.domain.candidatepool.entity.analysis.RecruitmentType;
import com.briefy.domain.candidatepool.entity.analysis.RoleGroupTag;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 분류 분석 결과와 사용자 선호도로부터 {@link AnalysisEligibility}를 도출하는 stateless 유틸리티.
 *
 * <p>규칙:
 *
 * <ul>
 *   <li>동일 공고의 직무(role)와 경력(experience)은 반드시 같은 {@link PostingTrack}에서 읽어야 한다.
 *   <li>{@code GENERAL_IT}·{@code OTHER_IT}는 특정 개발 직군의 wildcard가 아니다.
 *   <li>BROAD_IT_MATCH는 {@code OPEN_RECRUITMENT} 또는 {@code MULTI_ROLE} 공고에만 부여되며, IT 트랙 근거와 경력 적합
 *       근거가 모두 있어야 한다.
 * </ul>
 */
public final class ClassificationAnalyzer {

  private static final Set<RoleGroupTag> IT_TAGS =
      Set.of(
          RoleGroupTag.BACKEND,
          RoleGroupTag.FRONTEND,
          RoleGroupTag.FULLSTACK,
          RoleGroupTag.DATA,
          RoleGroupTag.AI_ML,
          RoleGroupTag.MOBILE,
          RoleGroupTag.DEVOPS_INFRA,
          RoleGroupTag.GENERAL_IT,
          RoleGroupTag.OTHER_IT);

  private ClassificationAnalyzer() {}

  /**
   * 분류 분석 결과와 사용자 선호도로부터 적합성 판정 객체를 도출한다.
   *
   * @param analysis nullable — 분석 행이 없으면 deferred 반환
   * @param preference 사용자 선호도 맵
   * @param mode 분류 모드 (OFF/SHADOW/ENFORCE)
   */
  public static AnalysisEligibility derive(
      JobPostingAnalysis analysis, Map<String, Object> preference, ClassificationMode mode) {

    if (mode == ClassificationMode.OFF) {
      return AnalysisEligibility.deferred("MODE=OFF");
    }
    if (analysis == null) {
      return AnalysisEligibility.deferred("분석 행 없음");
    }

    ClassificationStatus status = analysis.getClassificationStatus();
    if (status != ClassificationStatus.SUCCEEDED && status != ClassificationStatus.FALLBACK) {
      return AnalysisEligibility.deferred("분류 미완료: " + status.name());
    }

    // NON_IT 직군: 경력 판정 없이 바로 제외
    if (analysis.getJobDomain() == JobDomain.NON_IT) {
      return AnalysisEligibility.nonIt();
    }

    List<String> prefRoles = extractList(preference, "roles");
    List<String> prefExpLevels = extractList(preference, "experienceLevels");
    boolean isNewGrad = ExperiencePolicy.isNewGradUser(prefExpLevels);
    Set<RoleGroupTag> acceptableTags = resolveAcceptableTags(prefRoles);
    boolean noRolePreference = acceptableTags.isEmpty();

    PostingScope scope =
        analysis.getPostingScope() != null ? analysis.getPostingScope() : PostingScope.UNKNOWN;
    List<PostingTrack> tracks = analysis.getTracks();
    boolean hasUsableTracks = tracks != null && !tracks.isEmpty();

    if (!hasUsableTracks || scope == PostingScope.ROLE_SPECIFIC) {
      return evaluateSingleRole(analysis, acceptableTags, noRolePreference, isNewGrad);
    }

    return evaluateMultiRole(analysis, scope, tracks, acceptableTags, noRolePreference, isNewGrad);
  }

  // ── Single-role ───────────────────────────────────────────────────────────────

  private static AnalysisEligibility evaluateSingleRole(
      JobPostingAnalysis analysis,
      Set<RoleGroupTag> acceptableTags,
      boolean noRolePreference,
      boolean isNewGrad) {

    String hash = analysis.getAnalysisInputHash();
    String version = analysis.getClassifierVersion();
    String evidence = analysis.getEvidence();

    if (noRolePreference) {
      ExperienceMatchType expMatch = evaluateExperienceFromPosting(analysis, isNewGrad);
      boolean eligible = expMatch != ExperienceMatchType.EXCLUDED;
      return new AnalysisEligibility(
          eligible,
          RoleMatchType.DIRECT_MATCH,
          expMatch,
          null,
          List.of("선호 직무 없음"),
          evidence,
          hash,
          version);
    }

    List<RoleGroupTag> roleGroups = analysis.getRoleGroups();
    if (roleGroups == null || roleGroups.isEmpty()) {
      return AnalysisEligibility.deferred("역할 그룹 미확인");
    }

    boolean hasRoleMatch = roleGroups.stream().anyMatch(acceptableTags::contains);
    if (hasRoleMatch) {
      ExperienceMatchType expMatch = evaluateExperienceFromPosting(analysis, isNewGrad);
      boolean eligible = expMatch != ExperienceMatchType.EXCLUDED;
      List<String> reasons = eligible ? List.of("직무 직접 일치") : List.of("직무 일치, 경력 조건 제외");
      return new AnalysisEligibility(
          eligible, RoleMatchType.DIRECT_MATCH, expMatch, null, reasons, evidence, hash, version);
    }

    return new AnalysisEligibility(
        false,
        RoleMatchType.MISMATCH,
        ExperienceMatchType.UNKNOWN,
        null,
        List.of("직무 불일치"),
        evidence,
        hash,
        version);
  }

  // ── Multi-role / open recruitment ─────────────────────────────────────────────

  private static AnalysisEligibility evaluateMultiRole(
      JobPostingAnalysis analysis,
      PostingScope scope,
      List<PostingTrack> tracks,
      Set<RoleGroupTag> acceptableTags,
      boolean noRolePreference,
      boolean isNewGrad) {

    String hash = analysis.getAnalysisInputHash();
    String version = analysis.getClassifierVersion();

    // 선호 직무 없음: IT 트랙 중 경력 적합한 첫 번째 트랙으로 DIRECT_MATCH
    if (noRolePreference) {
      for (PostingTrack track : tracks) {
        if (track.unknown()) continue;
        if (!isItTrack(track)) continue;
        ExperienceMatchType expMatch = evaluateExperienceFromTrack(track, isNewGrad);
        if (expMatch != ExperienceMatchType.EXCLUDED) {
          return new AnalysisEligibility(
              true,
              RoleMatchType.DIRECT_MATCH,
              expMatch,
              track,
              List.of("선호 직무 없음, IT 트랙 있음"),
              track.evidence(),
              hash,
              version);
        }
      }
      return new AnalysisEligibility(
          false,
          RoleMatchType.DIRECT_MATCH,
          ExperienceMatchType.EXCLUDED,
          null,
          List.of("선호 직무 없음, IT 트랙 경력 불일치"),
          analysis.getEvidence(),
          hash,
          version);
    }

    // DIRECT_MATCH 시도: 직무와 경력이 모두 같은 트랙에서 일치하는 경우
    boolean hasAnyRoleMatchingTrack = false;
    PostingTrack firstRoleMatchTrack = null;
    ExperienceMatchType firstRoleMatchExp = null;

    for (PostingTrack track : tracks) {
      if (track.unknown()) continue;
      List<RoleGroupTag> trackRoles = track.roleGroups();
      if (trackRoles == null) continue;
      boolean roleMatches = trackRoles.stream().anyMatch(acceptableTags::contains);
      if (!roleMatches) continue;

      hasAnyRoleMatchingTrack = true;
      ExperienceMatchType expMatch = evaluateExperienceFromTrack(track, isNewGrad);

      if (firstRoleMatchTrack == null) {
        firstRoleMatchTrack = track;
        firstRoleMatchExp = expMatch;
      }

      if (expMatch != ExperienceMatchType.EXCLUDED) {
        return new AnalysisEligibility(
            true,
            RoleMatchType.DIRECT_MATCH,
            expMatch,
            track,
            List.of("트랙 직접 일치: " + label(track)),
            track.evidence(),
            hash,
            version);
      }
    }

    // 직무 일치 트랙은 있으나 경력 조건이 모두 제외 → 트랙 간 직무·경력 혼합 금지
    if (hasAnyRoleMatchingTrack) {
      return new AnalysisEligibility(
          false,
          RoleMatchType.DIRECT_MATCH,
          firstRoleMatchExp,
          firstRoleMatchTrack,
          List.of("트랙 직접 일치, 경력 조건 제외"),
          firstRoleMatchTrack != null ? firstRoleMatchTrack.evidence() : null,
          hash,
          version);
    }

    // BROAD_IT_MATCH 시도: 공개채용/다직무 + IT 트랙 근거 + 경력 적합
    if (scope == PostingScope.OPEN_RECRUITMENT || scope == PostingScope.MULTI_ROLE) {
      for (PostingTrack track : tracks) {
        if (track.unknown()) continue;
        if (!isItTrack(track)) continue;
        if (track.evidence() == null || track.evidence().isBlank()) continue;
        ExperienceMatchType expMatch = evaluateExperienceFromTrack(track, isNewGrad);
        if (expMatch == ExperienceMatchType.EXCLUDED) continue;
        return new AnalysisEligibility(
            true,
            RoleMatchType.BROAD_IT_MATCH,
            expMatch,
            track,
            List.of("공채/다직무 IT 적합 트랙"),
            track.evidence(),
            hash,
            version);
      }
    }

    return new AnalysisEligibility(
        false,
        RoleMatchType.MISMATCH,
        ExperienceMatchType.UNKNOWN,
        null,
        List.of("매칭 트랙 없음"),
        analysis.getEvidence(),
        hash,
        version);
  }

  // ── Experience evaluation ─────────────────────────────────────────────────────

  private static ExperienceMatchType evaluateExperienceFromPosting(
      JobPostingAnalysis analysis, boolean isNewGrad) {
    return evaluateExperience(
        analysis.getAcceptsNewGrad(),
        analysis.getMinRequiredYears(),
        analysis.getExperienceRequirementType(),
        analysis.getRecruitmentType(),
        isNewGrad);
  }

  static ExperienceMatchType evaluateExperienceFromTrack(PostingTrack track, boolean isNewGrad) {
    return evaluateExperience(
        track.acceptsNewGrad(),
        track.minRequiredYears(),
        track.experienceRequirementType(),
        track.recruitmentType(),
        isNewGrad);
  }

  static ExperienceMatchType evaluateExperience(
      Boolean acceptsNewGrad,
      Integer minRequiredYears,
      ExperienceRequirementType reqType,
      RecruitmentType recruitmentType,
      boolean isNewGrad) {

    if (!isNewGrad) {
      if (reqType == ExperienceRequirementType.NONE) return ExperienceMatchType.FULL;
      if (reqType == ExperienceRequirementType.REQUIRED) return ExperienceMatchType.FULL;
      if (reqType == ExperienceRequirementType.PREFERRED) return ExperienceMatchType.PARTIAL;
      return ExperienceMatchType.PARTIAL; // UNKNOWN
    }

    // 신입 사용자
    if (Boolean.FALSE.equals(acceptsNewGrad)) return ExperienceMatchType.EXCLUDED;
    if (reqType == ExperienceRequirementType.REQUIRED
        && minRequiredYears != null
        && minRequiredYears >= 1) {
      return ExperienceMatchType.EXCLUDED;
    }
    // 우대 경력은 PARTIAL — OPEN_HIRE보다 먼저 평가 (우대조건이 있으면 완전 일치가 아님)
    if (reqType == ExperienceRequirementType.PREFERRED) return ExperienceMatchType.PARTIAL;
    if (Boolean.TRUE.equals(acceptsNewGrad)) return ExperienceMatchType.FULL;
    if (reqType == ExperienceRequirementType.NONE) return ExperienceMatchType.FULL;
    if (recruitmentType == RecruitmentType.OPEN_HIRE
        || recruitmentType == RecruitmentType.NEW_GRAD_HIRE) {
      return ExperienceMatchType.FULL;
    }
    if (minRequiredYears != null && minRequiredYears == 0) return ExperienceMatchType.FULL;
    return ExperienceMatchType.PARTIAL; // UNKNOWN
  }

  // ── Helpers ───────────────────────────────────────────────────────────────────

  /**
   * 사용자 선호 직무 문자열 목록으로부터 분류 분석 결과와 비교 가능한 {@link RoleGroupTag} 집합을 도출한다.
   *
   * <p>BACKEND 선호 사용자는 BACKEND + FULLSTACK 트랙과 일치한다 ({@link RoleGroup#compatiblePostingGroups()}).
   */
  static Set<RoleGroupTag> resolveAcceptableTags(List<String> prefRoles) {
    Set<RoleGroupTag> result = EnumSet.noneOf(RoleGroupTag.class);
    for (String role : prefRoles) {
      String lowerRole = role.toLowerCase();
      for (RoleGroup g : RoleGroup.values()) {
        if (g == RoleGroup.NON_DEV) continue;
        if (g.matches(lowerRole)) {
          for (RoleGroup compatible : g.compatiblePostingGroups()) {
            RoleGroupTag tag = toTag(compatible);
            if (tag != null) result.add(tag);
          }
        }
      }
    }
    return result;
  }

  private static RoleGroupTag toTag(RoleGroup group) {
    return switch (group) {
      case BACKEND -> RoleGroupTag.BACKEND;
      case FRONTEND -> RoleGroupTag.FRONTEND;
      case FULLSTACK -> RoleGroupTag.FULLSTACK;
      case DATA -> RoleGroupTag.DATA;
      case AI_ML -> RoleGroupTag.AI_ML;
      case MOBILE -> RoleGroupTag.MOBILE;
      case DEVOPS_INFRA -> RoleGroupTag.DEVOPS_INFRA;
      case NON_DEV -> null;
    };
  }

  private static boolean isItTrack(PostingTrack track) {
    List<RoleGroupTag> groups = track.roleGroups();
    if (groups == null || groups.isEmpty()) return false;
    return groups.stream().anyMatch(IT_TAGS::contains);
  }

  private static String label(PostingTrack track) {
    return track.trackLabel() != null ? track.trackLabel() : "?";
  }

  @SuppressWarnings("unchecked")
  private static List<String> extractList(Map<String, Object> pref, String key) {
    Object val = pref.get(key);
    if (val instanceof List<?> list) {
      return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }
    return List.of();
  }
}
