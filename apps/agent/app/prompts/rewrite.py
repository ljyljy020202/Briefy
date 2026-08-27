"""Prompt builders for the rewrite node.

The rewrite node receives a draft that failed validation and a list of
validation error codes.  It asks the LLM to fix *only* the flagged issues
without adding, removing, or reordering postings.

Expected LLM JSON output (same schema as synthesis):
{
  "markdownContent": "수정된 완성 Markdown 브리핑",
  "overallSummary": "브리핑 전체를 한 문장으로 요약",
  "referencedPostingIds": ["id1", "id2", ...]
}
"""

from __future__ import annotations

import json

from app.prompts.briefing_synthesis import EnrichedArticleInput
from app.schemas.briefing import JobPostingPreference
from app.schemas.llm import BriefingSynthesisResult

_SYSTEM_PROMPT = """\
당신은 한국 구직자를 위한 AI 일일 채용 브리핑 수정 전문가입니다.

기존 브리핑 초안과 검증 오류 목록을 입력받아,
오류만 수정한 브리핑을 아래 JSON 형식으로 반환하세요.

출력 형식 (반드시 JSON 객체만 반환하고 다른 텍스트는 포함하지 마세요):
{
  "markdownContent": "수정된 완성 Markdown 브리핑",
  "overallSummary": "브리핑 전체를 한 문장으로 요약",
  "referencedPostingIds": ["입력으로 받은 순서 그대로"]
}

수정 규칙 (엄격히 준수):
- referencedPostingIds는 입력의 [공고 ID 순서] 그대로 사용하세요. 변경 금지.
- 공고 추가·삭제·순서 변경 금지
- 새로운 회사명·기술명·마감일·URL 생성 금지
- 검증 오류에 명시된 항목만 수정하세요
- 나머지 내용은 가능한 한 그대로 유지하세요

아래 6개 섹션이 반드시 모두 존재해야 합니다:
  ## 오늘의 핵심 요약
  ## 🏆 추천 공고 TOP n
  ## ⏰ 신규/마감 임박 공고
  ## 💡 오늘의 지원 추천 액션
  ## 🔑 오늘의 키워드
  ## ✏️ 한 줄 정리

절대 만들어내지 말아야 할 내용:
- 합격 가능성, 경쟁률, 합격 보장
- 입력에 없는 URL, 회사명, 마감일, 기술 스택
- 매수/매도 등 투자 추천

유효한 JSON 객체만 반환하세요.\
"""


def get_system_prompt() -> str:
    return _SYSTEM_PROMPT


def build_user_prompt(
    draft_content: str,
    draft_summary: str,
    validation_errors: list[str],
    expected_posting_ids: list[str],
    briefing_date: str,
    preference: JobPostingPreference,
    enriched_inputs: list[EnrichedArticleInput],
) -> str:
    """Build the user-turn prompt for the rewrite call."""
    error_block = "\n".join(f"- {e}" for e in validation_errors) or "- (없음)"
    ids_block = json.dumps(expected_posting_ids, ensure_ascii=False)
    pref_block = _format_preference(preference)
    postings_block = json.dumps(
        [_article_to_dict(a) for a in enriched_inputs],
        ensure_ascii=False,
        indent=2,
    )
    return f"""\
[브리핑 날짜]
{briefing_date}

[사용자 선호도]
{pref_block}

[공고 ID 순서 (변경 금지)]
{ids_block}

[원본 채용 공고 데이터]
{postings_block}

[기존 브리핑 초안 overallSummary]
{draft_summary}

[기존 브리핑 초안 markdownContent]
{draft_content}

[검증 오류 목록 — 이 오류만 수정하세요]
{error_block}\
"""


def parse_response(raw: dict | list) -> BriefingSynthesisResult:
    """Parse LLM rewrite JSON into a BriefingSynthesisResult."""
    if isinstance(raw, list):
        raise ValueError("rewrite response must be a JSON object, not an array")
    return BriefingSynthesisResult.model_validate(raw)


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


def _article_to_dict(a: EnrichedArticleInput) -> dict:
    return {
        "id": a["id"],
        "title": a["title"],
        "companyName": a["company_name"],
        "url": a.get("url"),
        "matchingReason": a["matching_reason"],
        "matchedKeywords": a.get("matched_keywords", []),
        "deadline": a.get("deadline"),
        "displayOrder": a.get("display_order", 0),
    }
