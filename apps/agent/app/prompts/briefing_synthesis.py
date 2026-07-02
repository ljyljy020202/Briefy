"""Prompt builders for full briefing report synthesis.

Synthesis takes the enriched articles + user preferences + date and returns a
JSON object with a polished Korean Markdown briefing and a one-sentence summary.

Expected LLM JSON output:
{
  "markdownContent": "## 오늘의 핵심 요약\\n...",
  "overallSummary": "오늘은 네이버 등 3개 기업의 백엔드 공고 5건을 선별했습니다."
}
"""

from __future__ import annotations

import json
from typing import TypedDict

from app.schemas.briefing import JobPostingPreference
from app.schemas.llm import BriefingSynthesisResult

_SYSTEM_PROMPT = """\
당신은 한국 구직자를 위한 AI 일일 채용 브리핑 작성자입니다.

enriched 채용 공고 목록과 사용자 선호도를 입력받아, 아래 JSON 형식으로만 반환하세요.

출력 형식 (반드시 JSON 객체만 반환하고 다른 텍스트는 포함하지 마세요):
{
  "markdownContent": "완성된 Markdown 브리핑 전문",
  "overallSummary": "브리핑 전체를 한 문장으로 요약"
}

Markdown 브리핑 필수 섹션 (아래 순서 준수):
1. ## 오늘의 핵심 요약
   - 날짜와 선별된 공고 수, 주요 기업명 포함
2. ## 🏆 추천 공고 TOP N
   - 각 공고: 순위, 제목, 기업, 포지션, 추천 이유, 출처 링크
   - 반드시 [공고 보기](url) 형식의 링크 포함
3. ## ⏰ 신규/마감 임박 공고
   - 마감 7일 이내 공고 강조 (없으면 섹션 유지 후 없음 명시)
4. ## 💡 오늘의 지원 추천 액션
   - 구체적인 액션 아이템 3-4개 (번호 목록)
5. ## 🔑 오늘의 키워드
   - 공고에 등장한 핵심 스킬/기술 키워드 (·로 구분)
6. ## ✏️ 한 줄 정리
   - 오늘 브리핑의 핵심 메시지 한 문장

작성 규칙:
- 입력 데이터에 존재하지 않는 사실을 절대 만들어내지 마세요.
- 출처 URL이 있으면 반드시 [공고 보기](url) 링크를 포함하세요.
- 투자 조언, 매수/매도 추천 등 금융 관련 의견을 절대 포함하지 마세요.
- 공고가 없으면 graceful 빈 상태 메시지를 반환하세요 (섹션 구조 유지).
- 한국어로 작성하세요.
- overallSummary는 한 문장으로 간결하게 작성하세요.
- 유효한 JSON 객체만 반환하세요.\
"""


class EnrichedArticleInput(TypedDict):
    """Combined posting metadata + LLM enrichment, passed to synthesis prompt."""

    id: str
    title: str
    company_name: str
    url: str | None
    source: str | None
    summary: str
    matching_reason: str
    matched_keywords: list[str]
    deadline: str | None
    days_until_deadline: int | None
    display_order: int


def build_user_prompt(
    briefing_date: str,
    preference: JobPostingPreference,
    articles: list[EnrichedArticleInput],
) -> str:
    """Format the user-turn prompt for briefing synthesis."""
    pref_block = _format_preference(preference)
    articles_block = json.dumps(
        [_article_to_dict(a) for a in articles],
        ensure_ascii=False,
        indent=2,
    )
    return f"""\
[브리핑 날짜]
{briefing_date}

[사용자 선호도]
{pref_block}

[선택된 채용 공고 (추천 순위 순)]
{articles_block}\
"""


def get_system_prompt() -> str:
    return _SYSTEM_PROMPT


def parse_response(raw: dict) -> BriefingSynthesisResult:
    """Validate and parse the LLM JSON response into a BriefingSynthesisResult."""
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
        "source": a.get("source"),
        "summary": a["summary"],
        "matchingReason": a["matching_reason"],
        "matchedKeywords": a.get("matched_keywords", []),
        "deadline": a.get("deadline"),
        "daysUntilDeadline": a.get("days_until_deadline"),
        "displayOrder": a.get("display_order", 0),
    }
