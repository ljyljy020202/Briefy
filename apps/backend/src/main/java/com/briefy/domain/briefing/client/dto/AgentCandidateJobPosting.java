package com.briefy.domain.briefing.client.dto;

import java.util.List;

public record AgentCandidateJobPosting(
    Long id,
    String source,
    String sourceUrl,
    String companyName,
    String title,
    String position,
    String employmentType,
    String experienceLevel,
    String location,
    String deadline,
    List<String> skills,
    List<String> roles,
    String description,
    String postedAt,
    String collectedDate,
    String contentHash,
    int preScore,
    // Always true when Backend builds this DTO — even if preScore is 0.
    // Agent uses this flag to distinguish "Backend scored 0" from "score not provided".
    boolean preScoreComputed) {}
