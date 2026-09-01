"""분류 요청·응답 Pydantic 스키마.

Spring POST /collections/classify 의 Java-Python 계약을 정의한다.

계약 규칙:
- 요청 내 jobPostingId 중복 금지
- 응답의 requestId == 요청의 requestId
- 응답의 classifierVersion == 요청의 classifierVersion
- 응답 results의 jobPostingId 집합 == 요청 jobPostingId 집합 (누락·추가·중복 불허)
- 각 result의 analysisInputHash == 요청 동일 ID의 hash (불일치 → CONFLICT)
- status=FAILED 이면 분류 필드 null 허용
- postingScope=MULTI_ROLE|OPEN_RECRUITMENT 이면 tracks 비어 있지 않아야 함
- 사용자 선호도, 회사 가산점, 추천 점수, rank 미포함
"""

from __future__ import annotations

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel


class ClassifySourceRef(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    source: str
    source_url: str
    source_external_id: str | None = None
    source_record_key: str | None = None


class ClassifyInputQuality(BaseModel):
    """분류 입력 품질 메타데이터."""

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    has_description: bool
    has_roles: bool
    has_experience_level: bool
    description_truncated: bool = False
    description_length: int = 0


class ClassifyPostingInput(BaseModel):
    """개별 공고 분류 입력.

    사용자 선호도, 회사 가산점, 추천 점수, rank는 포함하지 않는다.
    """

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    job_posting_id: int
    # Agent는 이 값을 결과에 그대로 에코해야 한다. 불일치 → Spring이 CONFLICT 처리.
    analysis_input_hash: str
    title: str
    company: str
    description: str | None = None
    parsed_roles: list[str] = Field(default_factory=list)
    parsed_experience_level: str | None = None
    parsed_employment_type: str | None = None
    source_refs: list[ClassifySourceRef] = Field(default_factory=list)
    input_quality: ClassifyInputQuality


class ClassifyRequest(BaseModel):
    """Spring → Agent POST /collections/classify 요청."""

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    request_id: str
    classifier_version: str
    postings: list[ClassifyPostingInput]


# ── 응답 모델 ─────────────────────────────────────────────────────────────────


class ClassifyTrack(BaseModel):
    """다직무·공개채용 공고의 모집 트랙 분류 결과."""

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    track_label: str | None = None
    # 허용값: IT / NON_IT / MIXED / UNKNOWN
    job_domain: str
    # 허용값: BACKEND / FRONTEND / FULLSTACK / DATA / AI_ML / MOBILE /
    #         DEVOPS_INFRA / GENERAL_IT / OTHER_IT / NON_DEV
    role_groups: list[str] = Field(default_factory=list)
    # null = 판별 불가. false로 강제 변환 금지.
    accepts_new_grad: bool | None = None
    min_required_years: int | None = None
    max_required_years: int | None = None
    # 허용값: REQUIRED / PREFERRED / NONE / UNKNOWN
    experience_requirement_type: str
    # 허용값: EXPERIENCED_HIRE / NEW_GRAD_HIRE / OPEN_HIRE / INTERNSHIP / UNKNOWN
    recruitment_type: str
    employment_type: str | None = None
    evidence: str | None = None
    unknown: bool = False


class ClassificationResult(BaseModel):
    """개별 공고 분류 결과."""

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    job_posting_id: int
    # 요청의 analysisInputHash를 그대로 에코. 불일치 → Spring이 CONFLICT 처리.
    analysis_input_hash: str

    # 허용값: IT / NON_IT / MIXED / UNKNOWN
    job_domain: str | None = None
    # 허용값: ROLE_SPECIFIC / MULTI_ROLE / OPEN_RECRUITMENT / UNKNOWN
    posting_scope: str | None = None
    role_groups: list[str] = Field(default_factory=list)
    # 허용값: EXPERIENCED_HIRE / NEW_GRAD_HIRE / OPEN_HIRE / INTERNSHIP / UNKNOWN
    recruitment_type: str | None = None
    # MULTI_ROLE 또는 OPEN_RECRUITMENT이면 비어 있지 않아야 한다.
    tracks: list[ClassifyTrack] = Field(default_factory=list)

    # null = 판별 불가. false로 강제 변환 금지.
    accepts_new_grad: bool | None = None
    # 필수 경력만 저장. 우대 경력은 preferred_experience에 별도 저장.
    min_required_years: int | None = None
    max_required_years: int | None = None
    # 허용값: REQUIRED / PREFERRED / NONE / UNKNOWN
    experience_requirement_type: str | None = None
    # 우대 경력 원문 (필수와 구분)
    preferred_experience: str | None = None

    # 분류 방법: LLM / RULE_BASED
    method: str
    # 분류 상태: SUCCEEDED / FALLBACK / FAILED
    status: str

    evidence: str | None = None
    uncertainty_reasons: list[str] = Field(default_factory=list)
    # 진단용 전용. 추천 허용·제외 판단에 사용하지 않는다.
    confidence: float | None = None
    input_completeness: float | None = None
    # null = 알 수 없음
    description_truncated: bool | None = None


class ClassifyTokenUsage(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    prompt_tokens: int = 0
    completion_tokens: int = 0
    total_tokens: int = 0


class ClassifyResponse(BaseModel):
    """Agent → Spring POST /collections/classify 응답.

    계약 규칙:
    - requestId == 요청의 requestId
    - classifierVersion == 요청의 classifierVersion
    - results의 jobPostingId 집합 == 요청 jobPostingId 집합 (누락·추가·중복 불허)
    """

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    request_id: str
    classifier_version: str
    results: list[ClassificationResult]
    token_usage: ClassifyTokenUsage = Field(default_factory=ClassifyTokenUsage)
    warnings: list[str] = Field(default_factory=list)
