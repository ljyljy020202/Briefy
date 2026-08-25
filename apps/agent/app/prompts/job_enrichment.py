"""Prompt builders for per-posting LLM enrichment.

Enrichment takes selected job postings + user preferences and returns a JSON
object whose ``enrichments`` array provides a Korean summary and concrete
matching reason for each posting.

Expected LLM JSON output:
{
  "enrichments": [
    {
      "id": "<posting id>",
      "summary": "공고 핵심 내용 2-3문장",
      "matchingReason": "구체적 매칭 이유 (역할명·스킬명·기업명 등 실제 항목 명시)",
      "matchedKeywords": ["키워드1", "키워드2"]
    }
  ]
}
"""

from __future__ import annotations

import json

from app.schemas.briefing import CandidateJobPosting, JobPostingPreference
from app.schemas.llm import JobPostingEnrichment

_SYSTEM_PROMPT = """\
당신은 한국 구직자를 위한 채용 공고 분석 어시스턴트입니다.

사용자의 선호도와 채용 공고 목록을 입력받아, 각 공고에 대한 핵심 요약과 매칭 이유를 \
아래 JSON 형식으로 반환하세요.

출력 형식 (반드시 JSON 객체만 반환하고 다른 텍스트는 포함하지 마세요):
{
  "enrichments": [
    {
      "id": "<공고 ID>",
      "summary": "공고 핵심 내용 2-3문장 (한국어)",
      "matchingReason": "이 공고가 사용자 선호도와 매칭되는 구체적 이유 (한국어)",
      "matchedKeywords": ["매칭된 구체적 키워드 목록"]
    }
  ]
}

matchingReason 작성 규칙:
- 반드시 아래 항목 중 실제로 매칭된 구체적 내용을 명시하세요:
  역할(roles), 관심 기업(companies), 스킬(skills), 위치(locations),
  경력 수준(experienceLevels), 고용 형태(employmentTypes), 마감 임박 여부
- 좋은 예시:
  "백엔드 개발자 역할과 Spring Boot, JPA 스킬이 사용자 관심 조건과 겹칩니다."
  "관심 기업 네이버의 공고이며 서울 근무, 신입 조건이 선호도와 맞습니다."
  "Kotlin, Java 스킬 매칭 · 판교 근무 · 3년 이상 경력 조건 부합"
- 다음 모호한 표현을 절대 사용하지 마세요:
  "좋은 기회입니다", "적합한 공고입니다", "사용자 관심사와 잘 맞습니다",
  "추천드립니다", "기대해볼 만한 공고입니다", "성장 가능성이 있습니다"

summary 작성 규칙:
- 공고에 명시된 정보만 2-3문장으로 요약하세요.
- 공고 제목, 기업명, 주요 업무 또는 기술 스택을 포함하세요.

절대 만들어내지 말아야 할 내용:
- 급여 또는 연봉 정보 (공고에 없는 경우)
- 채용 프로세스 또는 면접 일정 (공고에 없는 경우)
- 기업 규모, 복리후생 (공고에 없는 경우)
- 합격 가능성, 경쟁률, 합격 보장
- 사용자의 커리어 전망 또는 성장 가능성
- 공고에 없는 마감일, 공고에 없는 요구 스킬

matchedKeywords 작성 규칙:
- 사용자 선호도와 공고 양쪽에 실제로 존재하는 키워드만 포함하세요.
- 투자 조언, 매수/매도 추천 등 금융 관련 의견을 절대 포함하지 마세요.
- 한국어로 작성하세요.
- 유효한 JSON 객체만 반환하세요.\
"""


def build_user_prompt(
    preference: JobPostingPreference,
    postings: list[CandidateJobPosting],
) -> str:
    """Format the user-turn prompt for job enrichment."""
    pref_block = _format_preference(preference)
    postings_block = json.dumps(
        [_posting_to_dict(p) for p in postings],
        ensure_ascii=False,
        indent=2,
    )
    return f"""\
[사용자 선호도]
{pref_block}

[채용 공고 목록]
{postings_block}\
"""


def get_system_prompt() -> str:
    return _SYSTEM_PROMPT


def parse_response(raw: dict | list) -> list[JobPostingEnrichment]:
    """Parse and validate the LLM JSON response into enrichment objects.

    Accepts both ``{"enrichments": [...]}`` (JSON-mode wrapper) and a bare
    list in case the model returns the array directly.
    """
    if isinstance(raw, list):
        items = raw
    elif isinstance(raw, dict):
        items = raw.get("enrichments", [])
        if not items:
            for key in ("items", "data", "results"):
                candidate = raw.get(key)
                if isinstance(candidate, list):
                    items = candidate
                    break
    else:
        items = []

    return [
        JobPostingEnrichment.model_validate(item)
        for item in items
        if isinstance(item, dict)
    ]


def _format_preference(pref: JobPostingPreference) -> str:
    lines = [
        f"역할: {', '.join(pref.roles) or '미지정'}",
        f"관심 기업: {', '.join(pref.companies) or '미지정'}",
        f"스킬: {', '.join(pref.skills) or '미지정'}",
        f"위치: {', '.join(pref.locations) or '미지정'}",
        f"경력 수준: {', '.join(pref.experience_levels) or '미지정'}",
        f"고용 형태: {', '.join(pref.employment_types) or '미지정'}",
    ]
    return "\n".join(lines)


def _posting_to_dict(p: CandidateJobPosting) -> dict:
    description = (p.description or "").strip()
    d: dict = {
        "id": str(p.id or ""),
        "rank": p.rank,
        "title": p.title or "",
        "companyName": p.company_name or "",
        "skills": p.skills,
        "roles": p.roles,
        "location": p.location or "",
        "employmentType": p.employment_type or "",
        "experienceLevel": p.experience_level or "",
        "deadline": p.deadline or "",
        "isNew": p.is_new,
        "isUrgent": p.is_urgent,
        "description": description[:500] if description else "",
    }
    # Include Backend match signals to ground LLM matching reasons
    ev = p.match_evidence
    match_evidence = {
        k: v for k, v in {
            "matchedRoles": ev.matched_roles,
            "matchedCompanies": ev.matched_companies,
            "matchedSkills": ev.matched_skills,
            "matchedLocations": ev.matched_locations,
            "matchedExperienceLevels": ev.matched_experience_levels,
            "matchedEmploymentTypes": ev.matched_employment_types,
        }.items() if v
    }
    if match_evidence:
        d["matchEvidence"] = match_evidence
    bd = p.score_breakdown
    if bd.adjusted_score != 0 or bd.relevance_score != 0:
        d["scoreBreakdown"] = {
            "adjustedScore": bd.adjusted_score,
            "relevanceScore": bd.relevance_score,
            "exposurePenalty": bd.exposure_penalty,
        }
    return d
