package com.briefy.infra.agent.dto;

import java.util.List;

/**
 * Agent → Spring POST /collections/classify 응답 본문.
 *
 * <p>계약 규칙:
 *
 * <ul>
 *   <li>{@code requestId == request.requestId}
 *   <li>{@code classifierVersion == request.classifierVersion}
 *   <li>{@code results}의 {@code jobPostingId} 집합 == 요청 ID 집합 (누락·추가·중복 불허)
 *   <li>각 result의 {@code analysisInputHash == request} 동일 ID의 hash (불일치 → CONFLICT)
 * </ul>
 */
public record AgentClassifyResponse(
    String requestId,
    String classifierVersion,
    List<AgentClassificationResult> results,
    AgentClassifyTokenUsage tokenUsage,
    List<String> warnings) {}
