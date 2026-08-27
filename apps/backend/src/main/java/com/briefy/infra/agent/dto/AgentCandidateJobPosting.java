package com.briefy.infra.agent.dto;

import java.util.List;

/**
 * Represents one of the Backend-selected Top-7 job postings sent to the Agent.
 *
 * <p>The Agent must preserve the Backend's {@code rank} order and must not re-filter, re-score, or
 * re-select postings. {@code scoreBreakdown} and {@code matchEvidence} are provided so the LLM can
 * generate matching reasons grounded in Backend signals.
 */
public record AgentCandidateJobPosting(
    Long id,
    int rank,
    String source,
    String sourceUrl,
    String companyName,
    String title,
    String employmentType,
    String experienceLevel,
    String location,
    String deadline,
    List<String> skills,
    List<String> roles,
    String description,
    String publishedAt,
    String collectedDate,
    boolean isNew,
    boolean isUrgent,
    AgentScoreBreakdown scoreBreakdown,
    AgentMatchEvidence matchEvidence) {}
