"""Prompt builders for full briefing report synthesis.

Synthesis takes the enriched articles + user preferences + date and returns a
JSON object with a polished Korean Markdown briefing and a one-sentence summary.

Expected LLM JSON output:
{
  "markdownContent": "# 오늘의 채용 브리핑\\n\\n## 오늘의 핵심 요약\\n...",
  "overallSummary": "오늘은 네이버 등 3개 기업의 백엔드 공고 3건을 선별했습니다."
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
  "overallSummary": "브리핑 전체를 한 문장으로 요약",
  "referencedPostingIds": ["공고 id를 displayOrder 순서 그대로 나열"]
}

referencedPostingIds 규칙:
- [선택된 채용 공고] 목록의 id를 displayOrder 순서 그대로 배열에 포함하세요.
- 추가하거나 제거하거나 순서를 바꾸면 안 됩니다.

Markdown 브리핑 필수 구조 (아래 순서와 헤더 명칭 엄수):

# 오늘의 채용 브리핑

## 오늘의 핵심 요약
- 날짜, 선별된 공고 수, 주요 기업명을 포함한 불릿
- 가장 중요한 매칭 조건 또는 주목 포인트 불릿
- 오늘 할 수 있는 구체적 행동 제안 불릿
(정확히 3개의 불릿으로 작성)

## 🏆 추천 공고 TOP {n}
[선택된 채용 공고] 목록의 **모든 공고를 빠짐없이** 포함하세요. 일부 생략 금지.
각 공고에 다음 항목을 포함:
- **기업**: 기업명
- **공고**: 공고 제목 및 [공고 보기](url) 링크 (url이 있는 경우)
- **추천 이유**: 사용자의 구체적 선호도(역할명·스킬명·기업명·위치 등)와 매칭되는 이유
- **마감일**: 마감일이 공고에 명시된 경우에만 포함, 없으면 이 항목 전체 생략

## ⏰ 신규/마감 임박 공고
- 마감 7일 이내 공고를 강조
- 해당 공고가 없으면: "선별된 공고 중 7일 이내 마감 임박 공고가 없습니다."

## 💡 오늘의 지원 추천 액션
2~4개의 구체적 액션 아이템:
- 실제 매칭된 스킬, 역할, 기업명과 연결하여 작성
- 이력서, 포트폴리오, 지원 타이밍 등 실행 가능한 내용 포함

## 🔑 오늘의 키워드
선별된 공고에 등장한 역할명, 스킬, 기업명, 고용 형태 키워드 (·로 구분)

## ✏️ 한 줄 정리
오늘 브리핑의 핵심 메시지를 한 문장으로

작성 규칙:
- 추천 이유는 반드시 구체적인 매칭 항목(역할명, 스킬명, 기업명, 위치 등)을 포함
- 좋은 예시: "백엔드 개발자 역할과 Spring Boot 스킬이 사용자 조건과 겹칩니다."
- 다음 모호한 표현 금지: "좋은 기회", "적합한 공고", "기대해볼 만한", "추천드립니다"
- 절대 만들어내지 말아야 할 내용:
  급여/연봉, 채용 프로세스, 면접 일정, 기업 규모, 복리후생,
  합격 가능성, 경쟁률, 합격 보장, 사용자 커리어 전망,
  공고에 없는 마감일, 공고에 없는 요구 스킬
- 마감일이 명시되지 않은 공고는 마감일 항목을 생략
- url이 있으면 반드시 [공고 보기](url) 형식의 링크 포함
- 투자 조언, 매수/매도 추천 등 금융 관련 의견 절대 금지
- 공고가 없으면 각 섹션을 유지하되 내용이 없음을 명시
- 한국어로 작성
- 유효한 JSON 객체만 반환\
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
    is_new: bool
    is_urgent: bool


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


def get_system_prompt(n: int) -> str:
    return _SYSTEM_PROMPT.replace("{n}", str(n))


def parse_response(raw: dict) -> BriefingSynthesisResult:
    """Validate and parse the LLM JSON response into a BriefingSynthesisResult."""
    return BriefingSynthesisResult.model_validate(raw)


def _format_preference(pref: JobPostingPreference) -> str:
    lines = [
        f"역할: {', '.join(pref.roles) or '미지정'}",
        f"관심 기업: {', '.join(pref.companies) or '미지정'}",
        f"기업 규모: {', '.join(pref.company_sizes) or '미지정'}",
        f"관심 산업: {', '.join(pref.industries) or '미지정'}",
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
