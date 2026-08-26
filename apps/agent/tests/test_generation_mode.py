"""Tests for generation_mode metadata in BriefingGenerateResponse.

Covers:
- mode=LLM when first-pass LLM synthesis passes validation
- mode=REWRITTEN when rewrite is triggered (rewrite_count=1)
- mode=FALLBACK with fallback_reason=enrichment_failed
- mode=FALLBACK with fallback_reason=synthesis_failed
- mode=FALLBACK with fallback_reason=rewrite_failed
- mode=FALLBACK when validation exhausted (rewrite_count>=1 + retryable)
- mode=FALLBACK with fallback_reason=llm_disabled (LLM off)
- mode=EMPTY when pool is empty
- Backend rank preserved in all modes (articles order)
"""

from unittest.mock import AsyncMock, patch

from app.core.llm_client import LLMClientError, LLMTokenUsage
from tests.conftest import FULL_REQUEST

# ---------------------------------------------------------------------------
# Shared constants
# ---------------------------------------------------------------------------

_VALID_ENRICHMENT = {
    "enrichments": [
        {
            "id": "1",
            "summary": "네이버 요약",
            "matchingReason": "관심 기업 네이버",
            "matchedKeywords": ["Spring Boot"],
        },
        {
            "id": "2",
            "summary": "카카오 요약",
            "matchingReason": "백엔드 개발자 역할",
            "matchedKeywords": ["Kotlin"],
        },
        {
            "id": "3",
            "summary": "라인 요약",
            "matchingReason": "스킬 매칭",
            "matchedKeywords": ["Java"],
        },
    ]
}

_VALID_SYNTHESIS = {
    "markdownContent": (
        "# 오늘의 채용 브리핑\n\n"
        "## 오늘의 핵심 요약\n\n"
        "- 2026-06-26 기준 3건 선별.\n\n"
        "## 🏆 추천 공고 TOP 3\n\n공고 내용\n\n"
        "## ⏰ 신규/마감 임박 공고\n\n없습니다.\n\n"
        "## 💡 오늘의 지원 추천 액션\n\n1. 지원하세요\n\n"
        "## 🔑 오늘의 키워드\n\nSpring Boot · Java\n\n"
        "## ✏️ 한 줄 정리\n\n핵심 요약."
    ),
    "overallSummary": "3건 선별.",
    "referencedPostingIds": ["1", "2", "3"],
}

_VALID_REWRITE = {
    "markdownContent": (
        "# 오늘의 채용 브리핑\n\n"
        "## 오늘의 핵심 요약\n\n"
        "- 2026-06-26 기준 재작성 3건 선별.\n\n"
        "## 🏆 추천 공고 TOP 3\n\n재작성 내용\n\n"
        "## ⏰ 신규/마감 임박 공고\n\n없습니다.\n\n"
        "## 💡 오늘의 지원 추천 액션\n\n1. 지원\n\n"
        "## 🔑 오늘의 키워드\n\nJava\n\n"
        "## ✏️ 한 줄 정리\n\n재작성 요약."
    ),
    "overallSummary": "재작성 3건 요약.",
    "referencedPostingIds": ["1", "2", "3"],
}

# Synthesis with a retryable error (missing section)
_SYNTHESIS_WITH_RETRYABLE_ERROR = {
    "markdownContent": (
        "# 오늘의 채용 브리핑\n\n"
        # Missing 오늘의 핵심 요약 → RETRYABLE
        "## 🏆 추천 공고 TOP 3\n\n공고 내용\n\n"
        "## ⏰ 신규/마감 임박 공고\n\n없습니다.\n\n"
        "## 💡 오늘의 지원 추천 액션\n\n1. 지원\n\n"
        "## 🔑 오늘의 키워드\n\nJava\n\n"
        "## ✏️ 한 줄 정리\n\n요약."
    ),
    "overallSummary": "요약.",
    "referencedPostingIds": ["1", "2", "3"],
}


# ---------------------------------------------------------------------------
# mode=LLM
# ---------------------------------------------------------------------------


async def test_generation_mode_llm_first_pass(client):
    """Enrichment + synthesis pass on first try → mode=LLM, rewrite_count=0."""
    with patch("app.graph.user_briefing_graph.llm_client") as mock_llm:
        mock_llm.enabled = True
        tok_e = LLMTokenUsage(input_tokens=100, output_tokens=50, total_tokens=150)
        tok_s = LLMTokenUsage(input_tokens=80, output_tokens=40, total_tokens=120)
        mock_llm.call_json = AsyncMock(
            side_effect=[
                (_VALID_ENRICHMENT, tok_e),
                (_VALID_SYNTHESIS, tok_s),
            ]
        )
        response = await client.post("/briefings/generate", json=FULL_REQUEST)

    assert response.status_code == 200
    body = response.json()
    assert body["generationMode"] == "LLM"
    assert body["usedFallback"] is False
    assert body["fallbackReason"] is None
    assert body["rewriteCount"] == 0


# ---------------------------------------------------------------------------
# mode=REWRITTEN
# ---------------------------------------------------------------------------


async def test_generation_mode_rewritten(client):
    """Synthesis retryable → rewrite passes → mode=REWRITTEN, rewrite_count=1."""
    with patch("app.graph.user_briefing_graph.llm_client") as mock_llm:
        mock_llm.enabled = True
        tok_e = LLMTokenUsage(input_tokens=100, output_tokens=50, total_tokens=150)
        tok_s = LLMTokenUsage(input_tokens=80, output_tokens=40, total_tokens=120)
        tok_r = LLMTokenUsage(input_tokens=60, output_tokens=30, total_tokens=90)
        mock_llm.call_json = AsyncMock(
            side_effect=[
                # enrichment
                (_VALID_ENRICHMENT, tok_e),
                # synthesis — retryable (missing 오늘의 핵심 요약)
                (_SYNTHESIS_WITH_RETRYABLE_ERROR, tok_s),
                # rewrite — passes
                (_VALID_REWRITE, tok_r),
            ]
        )
        response = await client.post("/briefings/generate", json=FULL_REQUEST)

    assert response.status_code == 200
    body = response.json()
    assert body["generationMode"] == "REWRITTEN"
    assert body["usedFallback"] is False
    assert body["fallbackReason"] is None
    assert body["rewriteCount"] == 1


# ---------------------------------------------------------------------------
# mode=FALLBACK — enrichment_failed
# ---------------------------------------------------------------------------


async def test_generation_mode_fallback_enrichment_fail(client):
    """Enrichment LLM fails → fallback → mode=FALLBACK, reason=enrichment_failed."""
    with patch("app.graph.user_briefing_graph.llm_client") as mock_llm:
        mock_llm.enabled = True
        mock_llm.call_json = AsyncMock(
            side_effect=LLMClientError("enrichment timeout")
        )
        response = await client.post("/briefings/generate", json=FULL_REQUEST)

    assert response.status_code == 200
    body = response.json()
    assert body["generationMode"] == "FALLBACK"
    assert body["usedFallback"] is True
    assert body["fallbackReason"] == "enrichment_failed"


# ---------------------------------------------------------------------------
# mode=FALLBACK — synthesis_failed
# ---------------------------------------------------------------------------


async def test_generation_mode_fallback_synthesis_fail(client):
    """Synthesis LLM fails → fallback → mode=FALLBACK, reason=synthesis_failed."""
    with patch("app.graph.user_briefing_graph.llm_client") as mock_llm:
        mock_llm.enabled = True
        tok_e = LLMTokenUsage(input_tokens=100, output_tokens=50, total_tokens=150)
        mock_llm.call_json = AsyncMock(
            side_effect=[
                # enrichment ok
                (_VALID_ENRICHMENT, tok_e),
                # synthesis fails
                LLMClientError("synthesis timeout"),
            ]
        )
        response = await client.post("/briefings/generate", json=FULL_REQUEST)

    assert response.status_code == 200
    body = response.json()
    assert body["generationMode"] == "FALLBACK"
    assert body["usedFallback"] is True
    assert body["fallbackReason"] == "synthesis_failed"


# ---------------------------------------------------------------------------
# mode=FALLBACK — rewrite_failed
# ---------------------------------------------------------------------------


async def test_generation_mode_fallback_rewrite_fail(client):
    """Synthesis retryable → rewrite fails → mode=FALLBACK, reason=rewrite_failed."""
    with patch("app.graph.user_briefing_graph.llm_client") as mock_llm:
        mock_llm.enabled = True
        tok_e = LLMTokenUsage(input_tokens=100, output_tokens=50, total_tokens=150)
        tok_s = LLMTokenUsage(input_tokens=80, output_tokens=40, total_tokens=120)
        mock_llm.call_json = AsyncMock(
            side_effect=[
                # enrichment ok
                (_VALID_ENRICHMENT, tok_e),
                # synthesis — retryable
                (_SYNTHESIS_WITH_RETRYABLE_ERROR, tok_s),
                # rewrite fails
                LLMClientError("rewrite timeout"),
            ]
        )
        response = await client.post("/briefings/generate", json=FULL_REQUEST)

    assert response.status_code == 200
    body = response.json()
    assert body["generationMode"] == "FALLBACK"
    assert body["usedFallback"] is True
    assert body["fallbackReason"] == "rewrite_failed"


# ---------------------------------------------------------------------------
# mode=FALLBACK — validation exhausted (rewrite_count>=1)
# ---------------------------------------------------------------------------


async def test_generation_mode_fallback_validation_exhausted(client):
    """Synthesis retryable → rewrite succeeds but still retryable → fallback.
    This exercises the _route_after_validate path where rewrite_count >= 1."""
    with patch("app.graph.user_briefing_graph.llm_client") as mock_llm:
        mock_llm.enabled = True
        tok_e = LLMTokenUsage(input_tokens=100, output_tokens=50, total_tokens=150)
        tok_s = LLMTokenUsage(input_tokens=80, output_tokens=40, total_tokens=120)
        tok_r = LLMTokenUsage(input_tokens=60, output_tokens=30, total_tokens=90)
        mock_llm.call_json = AsyncMock(
            side_effect=[
                # enrichment ok
                (_VALID_ENRICHMENT, tok_e),
                # synthesis — retryable (missing 핵심 요약)
                (_SYNTHESIS_WITH_RETRYABLE_ERROR, tok_s),
                # rewrite also retryable (same missing section)
                (_SYNTHESIS_WITH_RETRYABLE_ERROR, tok_r),
            ]
        )
        response = await client.post("/briefings/generate", json=FULL_REQUEST)

    assert response.status_code == 200
    body = response.json()
    assert body["generationMode"] == "FALLBACK"
    assert body["usedFallback"] is True
    # rewrite_count==1 after rewrite, then validation sends to fallback
    assert body["rewriteCount"] == 1


# ---------------------------------------------------------------------------
# mode=FALLBACK — llm_disabled
# ---------------------------------------------------------------------------


async def test_generation_mode_fallback_llm_disabled(client):
    """LLM disabled → deterministic path → mode=FALLBACK, reason=llm_disabled."""
    # client fixture already monkeypatches llm_client.enabled=False via conftest
    response = await client.post("/briefings/generate", json=FULL_REQUEST)

    assert response.status_code == 200
    body = response.json()
    assert body["generationMode"] == "FALLBACK"
    assert body["usedFallback"] is True
    assert body["fallbackReason"] == "llm_disabled"


# ---------------------------------------------------------------------------
# mode=EMPTY — empty candidate pool
# ---------------------------------------------------------------------------


async def test_generation_mode_empty(client):
    """Empty pool → mode=EMPTY, usedFallback=True, articles=[]."""
    request = {
        **FULL_REQUEST,
        "candidatePool": {"jobPostings": [], "companyIssues": [], "industryIssues": []},
    }
    response = await client.post("/briefings/generate", json=request)

    assert response.status_code == 200
    body = response.json()
    assert body["generationMode"] == "EMPTY"
    assert body["usedFallback"] is True
    assert body["articles"] == []


# ---------------------------------------------------------------------------
# Backend rank preserved in all modes
# ---------------------------------------------------------------------------


async def test_backend_rank_preserved_in_fallback_mode(client):
    """FALLBACK mode (enrichment fail) preserves Backend rank order 1→2→3."""
    with patch("app.graph.user_briefing_graph.llm_client") as mock_llm:
        mock_llm.enabled = True
        mock_llm.call_json = AsyncMock(side_effect=LLMClientError("forced fail"))
        response = await client.post("/briefings/generate", json=FULL_REQUEST)

    assert response.status_code == 200
    body = response.json()
    assert body["generationMode"] == "FALLBACK"
    articles = body["articles"]
    assert len(articles) == 3
    assert "네이버" in articles[0]["title"]   # rank 1
    assert "카카오" in articles[1]["title"]   # rank 2
    assert "라인" in articles[2]["title"]     # rank 3


async def test_backend_rank_preserved_in_llm_mode(client):
    """LLM mode preserves Backend rank order (articles built from selected list)."""
    with patch("app.graph.user_briefing_graph.llm_client") as mock_llm:
        mock_llm.enabled = True
        tok_e = LLMTokenUsage(input_tokens=100, output_tokens=50, total_tokens=150)
        tok_s = LLMTokenUsage(input_tokens=80, output_tokens=40, total_tokens=120)
        mock_llm.call_json = AsyncMock(
            side_effect=[
                (_VALID_ENRICHMENT, tok_e),
                (_VALID_SYNTHESIS, tok_s),
            ]
        )
        response = await client.post("/briefings/generate", json=FULL_REQUEST)

    assert response.status_code == 200
    body = response.json()
    assert body["generationMode"] == "LLM"
    articles = body["articles"]
    assert len(articles) == 3
    assert "네이버" in articles[0]["title"]
    assert "카카오" in articles[1]["title"]
    assert "라인" in articles[2]["title"]


async def test_backend_rank_preserved_in_empty_mode(client):
    """EMPTY mode returns no articles — rank not applicable."""
    request = {
        **FULL_REQUEST,
        "candidatePool": {"jobPostings": [], "companyIssues": [], "industryIssues": []},
    }
    response = await client.post("/briefings/generate", json=request)

    assert response.status_code == 200
    body = response.json()
    assert body["generationMode"] == "EMPTY"
    assert body["articles"] == []


# ---------------------------------------------------------------------------
# Response schema — new fields present
# ---------------------------------------------------------------------------


async def test_response_schema_includes_generation_mode_fields(client):
    """All new generation_mode fields are present in the response schema."""
    response = await client.post("/briefings/generate", json=FULL_REQUEST)
    assert response.status_code == 200
    body = response.json()
    assert "generationMode" in body, "missing generationMode field"
    assert "usedFallback" in body, "missing usedFallback field"
    assert "rewriteCount" in body, "missing rewriteCount field"
    # fallbackReason may be null for LLM/EMPTY(null) — key must still exist
    assert "fallbackReason" in body, "missing fallbackReason field"
