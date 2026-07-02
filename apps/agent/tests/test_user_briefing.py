"""Tests for the candidatePool-based user briefing workflow.

Covers:
- Basic pipeline correctness (filter, rank, select, report)
- LLM integration: success path, fallback on failure, token usage accumulation
- LLM isolation: only selected top items sent to LLM, not the full pool
- Quality guardrails: past deadlines filtered, empty pool handled gracefully
- Schema compatibility with backend
"""

import re
from unittest.mock import AsyncMock, patch

from app.services.llm_client import LLMTokenUsage
from tests.conftest import FULL_REQUEST

_RANKING_REQUEST = {
    "userId": 1,
    "category": "JOB_POSTING",
    "preference": {
        "roles": ["백엔드 개발자"],
        "companies": ["네이버"],
        "skills": ["Spring Boot"],
        "locations": ["서울"],
        "experienceLevels": ["신입"],
        "employmentTypes": ["정규직"],
    },
    "briefingDate": "2026-07-01",
    "tone": "easy",
    "candidatePool": {
        "jobPostings": [
            {
                "id": 10,
                "source": "원티드",
                "sourceUrl": "https://www.wanted.co.kr/wd/00010",
                "companyName": "알 수 없는 회사",
                "title": "프론트엔드 개발자",
                "position": "프론트엔드 개발자",
                "employmentType": "계약직",
                "experienceLevel": "5년 이상",
                "location": "부산",
                "deadline": "2026-07-20",
                "skills": ["Vue.js", "TypeScript"],
                "roles": ["프론트엔드 개발자"],
                "description": "프론트엔드 개발자 채용",
                "preScore": 10,
            },
            {
                "id": 11,
                "source": "잡코리아",
                "sourceUrl": "https://www.jobkorea.co.kr/Recruit/GI_Read/00011",
                "companyName": "네이버",
                "title": "네이버 백엔드 개발자",
                "position": "백엔드 개발자",
                "employmentType": "정규직",
                "experienceLevel": "신입",
                "location": "서울",
                "deadline": "2026-07-25",
                "skills": ["Spring Boot", "Java"],
                "roles": ["백엔드 개발자"],
                "description": "네이버 백엔드 개발자 채용",
                "preScore": 70,
            },
        ],
        "companyIssues": [],
        "industryIssues": [],
    },
}

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

_VALID_SYNTHESIS_RESPONSE = {
    "markdownContent": (
        "## 오늘의 핵심 요약\n내용\n"
        "## 🏆 추천 공고 TOP 3\n공고\n"
        "## ⏰ 신규/마감 임박 공고\n없음\n"
        "## 💡 오늘의 지원 추천 액션\n1. 지원하세요\n"
        "## 🔑 오늘의 키워드\nSpring Boot\n"
        "## ✏️ 한 줄 정리\n요약."
    ),
    "overallSummary": "LLM이 생성한 한 줄 요약입니다.",
}

_VALID_ENRICHMENT_RESPONSE = {
    "enrichments": [
        {
            "id": "1",
            "summary": "LLM 요약 — 네이버",
            "matchingReason": "관심 기업 네이버 매칭",
            "matchedKeywords": ["Spring Boot"],
        },
        {
            "id": "2",
            "summary": "LLM 요약 — 카카오",
            "matchingReason": "백엔드 개발자 포지션 매칭",
            "matchedKeywords": ["Spring Boot", "Kotlin"],
        },
        {
            "id": "3",
            "summary": "LLM 요약 — 라인",
            "matchingReason": "스킬 매칭: Java, Kotlin",
            "matchedKeywords": ["Java", "Kotlin"],
        },
    ]
}


# ---------------------------------------------------------------------------
# Basic pipeline
# ---------------------------------------------------------------------------


async def test_candidatepool_request_returns_personalized_report(client):
    response = await client.post("/briefings/generate", json=FULL_REQUEST)
    assert response.status_code == 200
    body = response.json()
    assert body["title"]
    assert "백엔드 개발자" in body["title"]
    assert len(body["articles"]) > 0


async def test_ranking_prefers_matching_roles_and_companies(client):
    response = await client.post("/briefings/generate", json=_RANKING_REQUEST)
    assert response.status_code == 200
    articles = response.json()["articles"]
    assert len(articles) == 2
    assert "네이버" in articles[0]["title"]
    assert articles[0]["title"] != articles[1]["title"]


async def test_empty_candidatepool_returns_graceful_report(client):
    request = {
        **FULL_REQUEST,
        "candidatePool": {
            "jobPostings": [],
            "companyIssues": [],
            "industryIssues": [],
        },
    }
    response = await client.post("/briefings/generate", json=request)
    assert response.status_code == 200
    body = response.json()
    assert body["title"]
    assert isinstance(body["articles"], list)
    assert len(body["articles"]) == 0
    assert "없습니다" in body["content"]


async def test_response_schema_compatible_with_backend(client):
    response = await client.post("/briefings/generate", json=FULL_REQUEST)
    assert response.status_code == 200
    body = response.json()
    for field in ("title", "summary", "content", "articles", "tokenUsage"):
        assert field in body, f"missing top-level field: {field}"
    for article in body["articles"]:
        for field in ("title", "url", "summary", "whyItMatters", "publishedAt"):
            assert field in article, f"missing article field: {field}"


async def test_past_deadline_postings_are_filtered(client):
    request = {
        **FULL_REQUEST,
        "briefingDate": "2026-07-01",
        "candidatePool": {
            "jobPostings": [
                {
                    "id": 20,
                    "source": "원티드",
                    "sourceUrl": "https://www.wanted.co.kr/wd/00020",
                    "companyName": "테스트회사",
                    "title": "백엔드 개발자",
                    "position": "백엔드 개발자",
                    "deadline": "2026-06-30",
                    "skills": [],
                    "roles": [],
                    "preScore": 50,
                },
                {
                    "id": 21,
                    "source": "잡코리아",
                    "sourceUrl": "https://www.jobkorea.co.kr/Recruit/GI_Read/00021",
                    "companyName": "테스트회사B",
                    "title": "프론트엔드 개발자",
                    "position": "프론트엔드 개발자",
                    "deadline": "2026-07-15",
                    "skills": [],
                    "roles": [],
                    "preScore": 30,
                },
            ],
            "companyIssues": [],
            "industryIssues": [],
        },
    }
    response = await client.post("/briefings/generate", json=request)
    assert response.status_code == 200
    articles = response.json()["articles"]
    assert len(articles) == 1
    assert "프론트엔드" in articles[0]["title"]


# ---------------------------------------------------------------------------
# Fallback mode (no API key in test env)
# ---------------------------------------------------------------------------


async def test_no_llm_calls_made(client):
    """Without OPENAI_API_KEY, LLM is skipped and token usage stays zero."""
    response = await client.post("/briefings/generate", json=FULL_REQUEST)
    token_usage = response.json()["tokenUsage"]
    assert token_usage["inputTokens"] == 0
    assert token_usage["outputTokens"] == 0


# ---------------------------------------------------------------------------
# LLM success path
# ---------------------------------------------------------------------------


async def test_llm_success_returns_polished_markdown_and_articles(client):
    """Mocked LLM returns enrichment + synthesis; response should use LLM output."""
    with patch("app.graph.user_briefing_graph.llm_client") as mock_llm:
        mock_llm.enabled = True
        mock_llm.call_json = AsyncMock(
            side_effect=[
                (
                    _VALID_ENRICHMENT_RESPONSE,
                    LLMTokenUsage(
                        input_tokens=100, output_tokens=200, total_tokens=300
                    ),
                ),
                (
                    _VALID_SYNTHESIS_RESPONSE,
                    LLMTokenUsage(input_tokens=50, output_tokens=100, total_tokens=150),
                ),
            ]
        )
        response = await client.post("/briefings/generate", json=FULL_REQUEST)

    assert response.status_code == 200
    body = response.json()
    assert body["summary"] == "LLM이 생성한 한 줄 요약입니다."
    assert "오늘의 핵심 요약" in body["content"]
    assert len(body["articles"]) > 0


async def test_llm_success_accumulates_token_usage(client):
    """Token usage from enrichment + synthesis calls is summed in the response."""
    with patch("app.graph.user_briefing_graph.llm_client") as mock_llm:
        mock_llm.enabled = True
        mock_llm.call_json = AsyncMock(
            side_effect=[
                (
                    _VALID_ENRICHMENT_RESPONSE,
                    LLMTokenUsage(
                        input_tokens=100, output_tokens=200, total_tokens=300
                    ),
                ),
                (
                    _VALID_SYNTHESIS_RESPONSE,
                    LLMTokenUsage(input_tokens=50, output_tokens=100, total_tokens=150),
                ),
            ]
        )
        response = await client.post("/briefings/generate", json=FULL_REQUEST)

    assert response.status_code == 200
    token_usage = response.json()["tokenUsage"]
    assert token_usage["inputTokens"] == 150   # 100 + 50
    assert token_usage["outputTokens"] == 300  # 200 + 100


# ---------------------------------------------------------------------------
# LLM isolation: only selected top items sent to enrichment
# ---------------------------------------------------------------------------


async def test_llm_enrichment_receives_only_selected_postings_not_full_pool(client):
    """When pool has more than _TOP_N candidates, LLM enrichment only gets top N."""
    # 15 postings, all backend, all 서울 — deterministic scores will vary by preScore
    many_postings = [
        {
            "id": i,
            "source": "원티드",
            "sourceUrl": f"https://www.wanted.co.kr/wd/{i:05d}",
            "companyName": f"회사{i}",
            "title": "백엔드 개발자",
            "position": "백엔드 개발자",
            "employmentType": "정규직",
            "experienceLevel": "신입",
            "location": "서울",
            "deadline": "2026-07-30",
            "skills": ["Spring Boot"],
            "roles": ["백엔드 개발자"],
            "description": f"회사{i} 백엔드 채용",
            "preScore": 10 + i,
        }
        for i in range(1, 16)  # 15 postings
    ]
    request = {
        **FULL_REQUEST,
        "candidatePool": {
            "jobPostings": many_postings,
            "companyIssues": [],
            "industryIssues": [],
        },
    }

    captured: list[str] = []

    async def _mock_call_json(system_prompt: str, user_prompt: str):
        captured.append(user_prompt)
        if len(captured) == 1:
            return {"enrichments": []}, LLMTokenUsage()
        return _VALID_SYNTHESIS_RESPONSE, LLMTokenUsage()

    with patch("app.graph.user_briefing_graph.llm_client") as mock_llm:
        mock_llm.enabled = True
        mock_llm.call_json = _mock_call_json
        response = await client.post("/briefings/generate", json=request)

    assert response.status_code == 200
    # Enrichment call must have happened
    assert len(captured) >= 1
    # Count how many postings were serialised in the enrichment user prompt.
    # Each posting has exactly one "id" field in the JSON block.
    posting_count = len(re.findall(r'"id":\s*"\d+"', captured[0]))
    assert posting_count <= 7   # _TOP_N
    assert posting_count < 15  # definitely not the full pool


# ---------------------------------------------------------------------------
# LLM fallback on failure
# ---------------------------------------------------------------------------


async def test_llm_enrichment_failure_falls_back_to_deterministic(client):
    """When enrichment LLM call fails, pipeline continues with deterministic output."""
    from app.services.llm_client import LLMClientError

    with patch("app.graph.user_briefing_graph.llm_client") as mock_llm:
        mock_llm.enabled = True
        # Both calls fail
        mock_llm.call_json = AsyncMock(
            side_effect=LLMClientError("mock enrichment error")
        )
        response = await client.post("/briefings/generate", json=FULL_REQUEST)

    assert response.status_code == 200
    body = response.json()
    assert body["title"]
    assert len(body["articles"]) > 0
    assert body["tokenUsage"]["inputTokens"] == 0


async def test_llm_synthesis_failure_falls_back_to_deterministic(client):
    """When synthesis LLM call fails, pipeline returns a deterministic template."""
    from app.services.llm_client import LLMClientError

    with patch("app.graph.user_briefing_graph.llm_client") as mock_llm:
        mock_llm.enabled = True
        mock_llm.call_json = AsyncMock(
            side_effect=[
                # Enrichment succeeds
                (
                    _VALID_ENRICHMENT_RESPONSE,
                    LLMTokenUsage(input_tokens=50, output_tokens=20, total_tokens=70),
                ),
                # Synthesis fails
                LLMClientError("mock synthesis error"),
            ]
        )
        response = await client.post("/briefings/generate", json=FULL_REQUEST)

    assert response.status_code == 200
    body = response.json()
    # Deterministic report still has required structure
    assert "##" in body["content"]
    assert len(body["articles"]) > 0


async def test_llm_invalid_json_falls_back_safely(client):
    """Non-parseable LLM response triggers fallback without crashing."""
    from app.services.llm_client import LLMClientError

    with patch("app.graph.user_briefing_graph.llm_client") as mock_llm:
        mock_llm.enabled = True
        mock_llm.call_json = AsyncMock(
            side_effect=LLMClientError("invalid JSON from LLM")
        )
        response = await client.post("/briefings/generate", json=FULL_REQUEST)

    assert response.status_code == 200
    body = response.json()
    assert body["title"]
    assert isinstance(body["articles"], list)
    assert len(body["articles"]) > 0
