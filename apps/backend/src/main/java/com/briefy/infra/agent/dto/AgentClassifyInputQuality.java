package com.briefy.infra.agent.dto;

/** 분류 요청에 포함되는 입력 품질 메타데이터. */
public record AgentClassifyInputQuality(
    boolean hasDescription,
    boolean hasRoles,
    boolean hasExperienceLevel,
    boolean descriptionTruncated,
    int descriptionLength) {}
