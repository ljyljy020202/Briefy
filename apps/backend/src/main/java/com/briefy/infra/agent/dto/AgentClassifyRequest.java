package com.briefy.infra.agent.dto;

import java.util.List;

/**
 * Spring → Agent POST /collections/classify 요청 본문.
 *
 * <p>계약 규칙:
 *
 * <ul>
 *   <li>{@code postings} 내 {@code jobPostingId} 중복 금지
 *   <li>{@code analysisInputHash}는 Spring이 계산해서 전송
 *   <li>사용자 선호도, 회사 가산점, 추천 점수, rank 미포함
 * </ul>
 */
public record AgentClassifyRequest(
    String requestId, String classifierVersion, List<AgentClassifyPostingInput> postings) {}
