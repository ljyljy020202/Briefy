"""Prompt builders for per-posting LLM enrichment.

Enrichment takes selected job postings + user preferences and returns a JSON
object whose ``enrichments`` array provides a Korean summary and matching
reason for each posting.

Expected LLM JSON output:
{
  "enrichments": [
    {
      "id": "<posting id>",
      "summary": "공고 핵심 내용 2-3문장",
      "matchingReason": "이 공고가 선호도와 매칭되는 이유 1-2문장",
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
      "matchingReason": "이 공고가 사용자 선호도와 매칭되는 이유 1-2문장 (한국어)",
      "matchedKeywords": ["매칭된 구체적 키워드 목록"]
    }
  ]
}

작성 규칙:
- 입력 데이터에 존재하지 않는 사실을 절대 만들어내지 마세요.
- matchingReason은 반드시 사용자 선호도(역할, 기업, 스킬, \
위치, 경력 수준, 고용 형태)와 공고 필드를 근거로만 작성하세요.
- matchedKeywords는 실제로 선호도와 공고 양쪽에 존재하는 키워드만 포함하세요.
- 투자 조언, 매수/매도 추천 등 금융 관련 의견을 절대 포함하지 마세요.
- summary는 공고에 명시된 정보만 요약하세요.
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
    return {
        "id": str(p.id or ""),
        "title": p.title or "",
        "companyName": p.company_name or "",
        "position": p.position or p.title or "",
        "skills": p.skills,
        "roles": p.roles,
        "location": p.location or "",
        "employmentType": p.employment_type or "",
        "experienceLevel": p.experience_level or "",
        "deadline": p.deadline or "",
        "description": description[:500] if description else "",
    }
