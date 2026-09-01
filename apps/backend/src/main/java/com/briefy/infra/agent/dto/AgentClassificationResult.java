package com.briefy.infra.agent.dto;

import java.util.List;

/**
 * Agent 분류 응답 내 개별 공고 분류 결과.
 *
 * <p>계약 규칙:
 *
 * <ul>
 *   <li>{@code analysisInputHash}는 요청과 동일해야 한다. 불일치 시 Spring이 CONFLICT 처리.
 *   <li>{@code status=FAILED}이면 분류 필드는 null 허용.
 *   <li>{@code postingScope=MULTI_ROLE|OPEN_RECRUITMENT}이면 {@code tracks}가 비어 있지 않아야 한다.
 * </ul>
 */
public record AgentClassificationResult(
    Long jobPostingId,

    /** 요청의 analysisInputHash를 그대로 에코. 불일치 → Spring이 CONFLICT 처리. */
    String analysisInputHash,
    String jobDomain,
    String postingScope,
    List<String> roleGroups,
    String recruitmentType,
    List<AgentClassifyTrack> tracks,

    /** null = 판별 불가. false로 강제 변환 금지. */
    Boolean acceptsNewGrad,
    Integer minRequiredYears,
    Integer maxRequiredYears,
    String experienceRequirementType,
    String preferredExperience,

    /** 분류 방법: LLM / RULE_BASED */
    String method,

    /** 분류 상태: SUCCEEDED / FALLBACK / FAILED */
    String status,
    String evidence,
    List<String> uncertaintyReasons,

    /** 진단용 전용. 추천 허용·제외 판단에 사용하지 않는다. */
    Double confidence,
    Double inputCompleteness,

    /** null = 알 수 없음. */
    Boolean descriptionTruncated) {}
