package com.briefy.domain.candidatepool.entity.analysis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * 다직무·공개채용 공고의 모집 분야(트랙) 단위 분석 결과.
 *
 * <p>단일 직무 공고에는 빈 배열로 두고 공고 수준 필드를 사용한다.
 *
 * <p>직렬화·역직렬화 시 알 수 없는 필드는 무시한다 (하위 호환).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PostingTrack(

    /** 트랙 레이블 (예: "서버/백엔드", "데이터 분야"). null 허용. */
    String trackLabel,

    /** IT 도메인 여부. */
    JobDomain jobDomain,

    /** 이 트랙의 역할 그룹 태그. */
    List<RoleGroupTag> roleGroups,

    /**
     * 신입 지원 가능 여부. null = 판별 불가. false 강제 변환 금지.
     *
     * <p>JSON 역직렬화 시 누락 필드는 null로 유지된다.
     */
    Boolean acceptsNewGrad,

    /** 필수 경력 최소 연수. null = 정보 없음. 0 = 경력 무관. */
    Integer minRequiredYears,

    /** 필수 경력 최대 연수. null = 상한 없음. */
    Integer maxRequiredYears,

    /** 경력 조건 성격 (필수/우대/무관/불명확). */
    ExperienceRequirementType experienceRequirementType,

    /** 채용 유형. employmentType(고용 형태)과 혼합하지 않는다. */
    RecruitmentType recruitmentType,

    /** 고용 형태 원문 (정규직, 계약직 등). null 허용. */
    String employmentType,

    /** 분류 근거 텍스트. null 허용. */
    String evidence,

    /** true이면 이 트랙의 정보가 불명확하여 신뢰하기 어렵다. */
    boolean unknown) {}
