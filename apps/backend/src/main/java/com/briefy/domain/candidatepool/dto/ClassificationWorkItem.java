package com.briefy.domain.candidatepool.dto;

import com.briefy.infra.agent.dto.AgentClassifySourceRef;
import java.util.List;

/**
 * 분류 작업자가 Agent에 보낼 스냅샷 데이터를 담는 불변 값 객체.
 *
 * <p>클레임 트랜잭션이 커밋된 뒤 트랜잭션 밖에서 HTTP 호출 시 사용한다.
 *
 * <p>설계 원칙: DB 트랜잭션이 닫힌 후에도 필요한 모든 데이터를 포함한다. 늦게 로딩하지 않는다.
 */
public record ClassificationWorkItem(
    Long analysisId,
    Long postingId,
    String claimToken,
    String analysisInputHash,
    String classifierVersion,

    // Job posting snapshot (claim 시점 기준)
    String title,
    String company,
    String description,
    String rolesJson,
    String experienceLevel,
    String employmentType,
    Boolean descriptionTruncated,
    List<AgentClassifySourceRef> sourceRefs) {}
