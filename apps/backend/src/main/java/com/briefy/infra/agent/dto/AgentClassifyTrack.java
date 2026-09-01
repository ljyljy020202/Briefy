package com.briefy.infra.agent.dto;

import java.util.List;

/** 다직무·공개채용 공고의 모집 트랙 분류 결과. */
public record AgentClassifyTrack(
    String trackLabel,
    String jobDomain,
    List<String> roleGroups,
    Boolean acceptsNewGrad,
    Integer minRequiredYears,
    Integer maxRequiredYears,
    String experienceRequirementType,
    String recruitmentType,
    String employmentType,
    String evidence,
    boolean unknown) {}
