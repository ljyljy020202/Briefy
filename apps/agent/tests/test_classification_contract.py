"""Java-Python 분류 계약 테스트.

camelCase 직렬화, nullable boolean 보존, 계약 규칙(ID 일치, hash 에코 등)을 검증한다.
"""

from __future__ import annotations

from app.schemas.classification import (
    ClassificationResult,
    ClassifyInputQuality,
    ClassifyPostingInput,
    ClassifyRequest,
    ClassifyResponse,
    ClassifySourceRef,
    ClassifyTokenUsage,
    ClassifyTrack,
)

# ── camelCase 직렬화 ─────────────────────────────────────────────────────────


def test_classify_request_serializes_to_camel_case():
    req = ClassifyRequest(
        request_id="req-001",
        classifier_version="1.0.0",
        postings=[
            ClassifyPostingInput(
                job_posting_id=123,
                analysis_input_hash="abc" * 21 + "a",
                title="백엔드 개발자",
                company="네이버",
                description="Spring Boot 서버 개발",
                parsed_roles=["백엔드"],
                parsed_experience_level="3년 이상",
                parsed_employment_type="정규직",
                source_refs=[
                    ClassifySourceRef(
                        source="saramin",
                        source_url="https://saramin.co.kr/job/123",
                        source_external_id="123",
                    )
                ],
                input_quality=ClassifyInputQuality(
                    has_description=True,
                    has_roles=True,
                    has_experience_level=True,
                    description_truncated=False,
                    description_length=500,
                ),
            )
        ],
    )

    data = req.model_dump(by_alias=True)
    assert data["requestId"] == "req-001"
    assert data["classifierVersion"] == "1.0.0"
    assert "request_id" not in data

    posting = data["postings"][0]
    assert posting["jobPostingId"] == 123
    assert posting["analysisInputHash"] == "abc" * 21 + "a"
    assert posting["parsedRoles"] == ["백엔드"]
    assert posting["parsedExperienceLevel"] == "3년 이상"
    assert "parsed_roles" not in posting

    quality = posting["inputQuality"]
    assert quality["hasDescription"] is True
    assert quality["descriptionTruncated"] is False
    assert quality["descriptionLength"] == 500
    assert "has_description" not in quality

    ref = posting["sourceRefs"][0]
    assert ref["sourceUrl"] == "https://saramin.co.kr/job/123"
    assert "source_url" not in ref


def test_classify_response_serializes_to_camel_case():
    resp = ClassifyResponse(
        request_id="req-001",
        classifier_version="1.0.0",
        results=[
            ClassificationResult(
                job_posting_id=123,
                analysis_input_hash="abc" * 21 + "a",
                job_domain="IT",
                posting_scope="ROLE_SPECIFIC",
                role_groups=["BACKEND"],
                recruitment_type="EXPERIENCED_HIRE",
                tracks=[],
                accepts_new_grad=False,
                min_required_years=3,
                max_required_years=None,
                experience_requirement_type="REQUIRED",
                preferred_experience=None,
                method="LLM",
                status="SUCCEEDED",
                evidence="경력 3년 이상 필수",
                uncertainty_reasons=[],
                confidence=0.92,
                input_completeness=0.9,
                description_truncated=False,
            )
        ],
        token_usage=ClassifyTokenUsage(
            prompt_tokens=1200, completion_tokens=300, total_tokens=1500
        ),
        warnings=[],
    )

    data = resp.model_dump(by_alias=True)
    assert data["requestId"] == "req-001"
    assert "request_id" not in data

    result = data["results"][0]
    assert result["jobPostingId"] == 123
    assert result["jobDomain"] == "IT"
    assert result["postingScope"] == "ROLE_SPECIFIC"
    assert result["roleGroups"] == ["BACKEND"]
    assert result["recruitmentType"] == "EXPERIENCED_HIRE"
    assert result["acceptsNewGrad"] is False
    assert result["minRequiredYears"] == 3
    assert result["maxRequiredYears"] is None
    assert result["experienceRequirementType"] == "REQUIRED"
    assert result["uncertaintyReasons"] == []
    assert "job_domain" not in result

    usage = data["tokenUsage"]
    assert usage["promptTokens"] == 1200
    assert usage["totalTokens"] == 1500
    assert "prompt_tokens" not in usage


# ── nullable boolean 보존 ───────────────────────────────────────────────────


def test_accepts_new_grad_null_is_preserved_not_coerced():
    """null acceptsNewGrad는 false로 강제 변환되지 않아야 한다."""
    result = ClassificationResult(
        job_posting_id=1,
        analysis_input_hash="h" * 64,
        job_domain="UNKNOWN",
        posting_scope="UNKNOWN",
        role_groups=[],
        recruitment_type="UNKNOWN",
        tracks=[],
        accepts_new_grad=None,  # 판별 불가
        min_required_years=None,
        max_required_years=None,
        experience_requirement_type="UNKNOWN",
        preferred_experience=None,
        method="RULE_BASED",
        status="FALLBACK",
    )
    assert result.accepts_new_grad is None

    data = result.model_dump(by_alias=True)
    assert data["acceptsNewGrad"] is None


def test_accepts_new_grad_true_is_preserved():
    result = ClassificationResult(
        job_posting_id=1,
        analysis_input_hash="h" * 64,
        job_domain="IT",
        posting_scope="ROLE_SPECIFIC",
        role_groups=["BACKEND"],
        recruitment_type="OPEN_HIRE",
        tracks=[],
        accepts_new_grad=True,
        min_required_years=None,
        max_required_years=None,
        experience_requirement_type="NONE",
        preferred_experience=None,
        method="LLM",
        status="SUCCEEDED",
    )
    assert result.accepts_new_grad is True


def test_description_truncated_null_is_preserved():
    result = ClassificationResult(
        job_posting_id=1,
        analysis_input_hash="h" * 64,
        job_domain="IT",
        posting_scope="UNKNOWN",
        role_groups=[],
        recruitment_type="UNKNOWN",
        tracks=[],
        accepts_new_grad=None,
        min_required_years=None,
        max_required_years=None,
        experience_requirement_type="UNKNOWN",
        preferred_experience=None,
        method="LLM",
        status="SUCCEEDED",
        description_truncated=None,
    )
    assert result.description_truncated is None

    data = result.model_dump(by_alias=True)
    assert data["descriptionTruncated"] is None


# ── 역방향 호환 역직렬화 ─────────────────────────────────────────────────────


def test_classify_response_deserializes_from_camel_case():
    """Spring이 보내는 camelCase JSON에서 Python 모델로 역직렬화."""
    raw = {
        "requestId": "req-001",
        "classifierVersion": "1.0.0",
        "results": [
            {
                "jobPostingId": 42,
                "analysisInputHash": "d" * 64,
                "jobDomain": "NON_IT",
                "postingScope": "ROLE_SPECIFIC",
                "roleGroups": ["NON_DEV"],
                "recruitmentType": "EXPERIENCED_HIRE",
                "tracks": [],
                "acceptsNewGrad": False,
                "minRequiredYears": None,
                "maxRequiredYears": None,
                "experienceRequirementType": "UNKNOWN",
                "preferredExperience": None,
                "method": "LLM",
                "status": "SUCCEEDED",
                "evidence": "마케팅 공고",
                "uncertaintyReasons": [],
                "confidence": 0.95,
                "inputCompleteness": 0.8,
                "descriptionTruncated": False,
            }
        ],
        "tokenUsage": {
            "promptTokens": 500,
            "completionTokens": 100,
            "totalTokens": 600,
        },
        "warnings": [],
    }

    resp = ClassifyResponse.model_validate(raw)
    assert resp.request_id == "req-001"
    assert resp.classifier_version == "1.0.0"
    assert len(resp.results) == 1

    r = resp.results[0]
    assert r.job_posting_id == 42
    assert r.job_domain == "NON_IT"
    assert r.role_groups == ["NON_DEV"]
    assert r.accepts_new_grad is False
    assert r.evidence == "마케팅 공고"
    assert resp.token_usage.prompt_tokens == 500


def test_classify_request_deserializes_from_camel_case():
    """Agent가 Spring으로부터 받을 camelCase JSON 역직렬화."""
    raw = {
        "requestId": "req-002",
        "classifierVersion": "1.0.0",
        "postings": [
            {
                "jobPostingId": 99,
                "analysisInputHash": "e" * 64,
                "title": "AML Manager",
                "company": "토스",
                "description": None,
                "parsedRoles": [],
                "parsedExperienceLevel": None,
                "parsedEmploymentType": None,
                "sourceRefs": [],
                "inputQuality": {
                    "hasDescription": False,
                    "hasRoles": False,
                    "hasExperienceLevel": False,
                    "descriptionTruncated": False,
                    "descriptionLength": 0,
                },
            }
        ],
    }

    req = ClassifyRequest.model_validate(raw)
    assert req.request_id == "req-002"
    posting = req.postings[0]
    assert posting.job_posting_id == 99
    assert posting.title == "AML Manager"
    assert posting.description is None
    assert posting.parsed_roles == []
    assert not posting.input_quality.has_description


# ── 계약: 누락 필드 기본값 ───────────────────────────────────────────────────


def test_classify_response_missing_optional_fields_default_correctly():
    """선택 필드 누락 시 기본값 적용 (역방향 호환)."""
    raw = {
        "requestId": "r",
        "classifierVersion": "1.0.0",
        "results": [
            {
                "jobPostingId": 1,
                "analysisInputHash": "f" * 64,
                "method": "LLM",
                "status": "SUCCEEDED",
            }
        ],
    }
    resp = ClassifyResponse.model_validate(raw)
    r = resp.results[0]
    assert r.role_groups == []
    assert r.tracks == []
    assert r.uncertainty_reasons == []
    assert r.accepts_new_grad is None
    assert r.description_truncated is None
    assert resp.token_usage.total_tokens == 0
    assert resp.warnings == []


# ── ClassifyTrack camelCase ─────────────────────────────────────────────────


def test_classify_track_camel_case():
    track = ClassifyTrack(
        track_label="서버/백엔드",
        job_domain="IT",
        role_groups=["BACKEND"],
        accepts_new_grad=True,
        min_required_years=None,
        max_required_years=None,
        experience_requirement_type="NONE",
        recruitment_type="OPEN_HIRE",
        employment_type="정규직",
        evidence="백엔드 개발자 트랙",
        unknown=False,
    )
    data = track.model_dump(by_alias=True)
    assert data["trackLabel"] == "서버/백엔드"
    assert data["jobDomain"] == "IT"
    assert data["roleGroups"] == ["BACKEND"]
    assert data["acceptsNewGrad"] is True
    assert data["minRequiredYears"] is None
    assert data["experienceRequirementType"] == "NONE"
    assert data["recruitmentType"] == "OPEN_HIRE"
    assert "track_label" not in data


def test_classify_track_unknown_true_preserves_nulls():
    """unknown=True 트랙에서 acceptsNewGrad 등이 null로 유지되어야 한다."""
    track = ClassifyTrack(
        job_domain="UNKNOWN",
        role_groups=[],
        accepts_new_grad=None,
        min_required_years=None,
        max_required_years=None,
        experience_requirement_type="UNKNOWN",
        recruitment_type="UNKNOWN",
        unknown=True,
    )
    assert track.accepts_new_grad is None
    data = track.model_dump(by_alias=True)
    assert data["acceptsNewGrad"] is None
    assert data["unknown"] is True
