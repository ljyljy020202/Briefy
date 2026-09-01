package com.briefy.infra.agent.dto;

import java.util.List;

/**
 * 분류 요청 내 개별 공고 입력.
 *
 * <p>사용자 선호도, 회사 가산점, 추천 점수, rank는 포함하지 않는다.
 */
public record AgentClassifyPostingInput(
    Long jobPostingId,

    /** 분류 입력의 SHA-256 해시 (hex 64자). Agent는 이 값을 결과에 그대로 에코해야 한다. 불일치 시 CONFLICT로 처리한다. */
    String analysisInputHash,
    String title,
    String company,
    String description,
    List<String> parsedRoles,
    String parsedExperienceLevel,
    String parsedEmploymentType,
    List<AgentClassifySourceRef> sourceRefs,
    AgentClassifyInputQuality inputQuality) {}
