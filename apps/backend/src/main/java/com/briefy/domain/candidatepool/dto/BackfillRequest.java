package com.briefy.domain.candidatepool.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 관리자 backfill 요청.
 *
 * <p>처리 대상은 {@code postingIds}(명시적 ID) 또는 날짜 범위({@code fromDate}/{@code toDate})로 지정한다. 둘 다 없으면 전체
 * 활성 공고를 대상으로 한다.
 *
 * <p>off 모드에서 요청해도 PENDING으로 등록되지만 자동 처리되지 않는다. 모드가 shadow 또는 enforce로 변경될 때 작업자가 처리한다.
 */
public record BackfillRequest(
    /** true이면 상태 변경 없이 대상 수와 사유만 반환. LLM 호출 없음. */
    boolean dryRun,

    /** 처리할 특정 공고 ID 목록. 비어 있으면 날짜 범위 또는 전체 활성 공고를 사용. */
    List<Long> postingIds,

    /** 수집 날짜 범위 시작. null이면 하한 없음. */
    LocalDate fromDate,

    /** 수집 날짜 범위 종료. null이면 상한 없음. */
    LocalDate toDate,

    /**
     * 최대 처리 건수 (1–1000). postingIds를 명시적으로 지정한 경우에도 적용된다.
     *
     * <p>값이 1000을 초과하면 1000으로 제한한다.
     */
    int limit,

    /** 커서 기반 페이지 처리. 이전 응답의 마지막 공고 ID를 다음 요청의 {@code afterId}로 전달한다. null이면 처음부터 시작. */
    Long afterId,

    /**
     * true이면 이미 SUCCEEDED/FALLBACK인 항목도 재분류 대기(PENDING)로 초기화. false이면 동일 hash+version의 완료 결과는 건너뜀.
     */
    boolean forceReclassify,

    /** 이 backfill에서 사용할 분류 버전. null이면 현재 설정({@code briefy.classifier.version}) 사용. */
    String classifierVersion) {}
