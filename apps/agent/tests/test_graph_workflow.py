"""Tests for the LangGraph workflow branching logic.

These tests exercise the actual graph conditional edges by mocking llm_client
inside the user_briefing_graph module and calling run() or the HTTP endpoint.

Scenario coverage:
  1. Normal draft PASS — no rewrite
  2. First draft missing section → rewrite 1 → PASS
  3. First draft ID order wrong → rewrite 1 → PASS
  4. First draft fail + rewrite also fails → deterministic fallback
  5. Enrichment LLM exception → deterministic fallback
  6. Synthesis LLM exception → deterministic fallback
  7. Rewrite LLM exception → deterministic fallback
  8. rewriteCount never exceeds 1
  9. Fallback preserves Backend rank order
 10. isNew and isUrgent both shown in fallback
 11. Token usage accumulates enrichment + synthesis + rewrite
 12. Empty candidatePool → empty-state, no LLM calls
 13. Agent does not rerank (Backend rank preserved end-to-end)
"""

from unittest.mock import AsyncMock, patch

import pytest

from app.core.llm_client import LLMClientError, LLMTokenUsage
from app.graph.user_briefing_graph import (
    UserBriefingState,
    deterministic_fallback_node,
    validate_report_node,
)
from app.schemas.briefing import (
    BriefingGenerateRequest,
    CandidateJobPosting,
    TokenUsage,
)
from tests.conftest import _POOL_POSTINGS, FULL_REQUEST

# ---------------------------------------------------------------------------
# Shared fixtures / helpers
# ---------------------------------------------------------------------------

_EMPTY_ENRICHMENT = {"enrichments": []}

_VALID_MARKDOWN = (
    "# 오늘의 채용 브리핑\n\n"
    "## 오늘의 핵심 요약\n\n"
    "- 선별 요약 불릿 1\n\n"
    "## 🏆 추천 공고 TOP 3\n\n"
    "공고 내용\n\n"
    "## ⏰ 신규/마감 임박 공고\n\n"
    "선별된 공고 중 7일 이내 마감 임박 공고가 없습니다.\n\n"
    "## 💡 오늘의 지원 추천 액션\n\n"
    "1. 지원하세요\n\n"
    "## 🔑 오늘의 키워드\n\n"
    "Spring Boot\n\n"
    "## ✏️ 한 줄 정리\n\n"
    "오늘의 한 줄 정리."
)

# FULL_REQUEST has postings with IDs 1, 2, 3
_VALID_IDS = ["1", "2", "3"]


def _synthesis_ok(ids: list[str] | None = None) -> dict:
    return {
        "markdownContent": _VALID_MARKDOWN,
        "overallSummary": "LLM 한 줄 요약",
        "referencedPostingIds": ids if ids is not None else _VALID_IDS,
    }


def _synthesis_missing_section(ids: list[str] | None = None) -> dict:
    # Missing "오늘의 핵심 요약" section
    markdown = (
        "# 오늘의 채용 브리핑\n\n"
        "## 🏆 추천 공고 TOP 3\n\n"
        "공고 내용\n\n"
        "## ⏰ 신규/마감 임박 공고\n\n"
        "없습니다.\n\n"
        "## 💡 오늘의 지원 추천 액션\n\n"
        "1. 지원\n\n"
        "## 🔑 오늘의 키워드\n\n"
        "Java\n\n"
        "## ✏️ 한 줄 정리\n\n"
        "정리."
    )
    return {
        "markdownContent": markdown,
        "overallSummary": "한 줄 요약",
        "referencedPostingIds": ids if ids is not None else _VALID_IDS,
    }


def _synthesis_wrong_id_order() -> dict:
    # IDs in wrong order: 3 → 1 → 2 instead of 1 → 2 → 3
    return {
        "markdownContent": _VALID_MARKDOWN,
        "overallSummary": "한 줄 요약",
        "referencedPostingIds": ["3", "1", "2"],
    }


def _usage(inp: int = 100, out: int = 50) -> LLMTokenUsage:
    return LLMTokenUsage(input_tokens=inp, output_tokens=out, total_tokens=inp + out)


# ---------------------------------------------------------------------------
# Helpers for building minimal state objects (for unit tests of individual nodes)
# ---------------------------------------------------------------------------


def _make_request(**overrides) -> BriefingGenerateRequest:
    data = dict(FULL_REQUEST, **overrides)
    return BriefingGenerateRequest.model_validate(data)


def _pool_postings() -> list[CandidateJobPosting]:
    return [CandidateJobPosting.model_validate(p) for p in _POOL_POSTINGS]


# ---------------------------------------------------------------------------
# 1. Normal draft PASS — no rewrite
# ---------------------------------------------------------------------------


async def test_normal_draft_passes_without_rewrite(client):
    """When synthesis returns a valid draft, validate PASS → END, no rewrite."""
    call_log: list[str] = []

    async def _mock(sys_p: str, usr_p: str):
        call_log.append("call")
        if len(call_log) == 1:
            return _EMPTY_ENRICHMENT, _usage()
        return _synthesis_ok(), _usage()

    with patch("app.graph.user_briefing_graph.llm_client") as m:
        m.enabled = True
        m.call_json = _mock
        resp = await client.post("/briefings/generate", json=FULL_REQUEST)

    assert resp.status_code == 200
    body = resp.json()
    assert body["summary"] == "LLM 한 줄 요약"
    assert len(body["articles"]) == 3
    # Only 2 LLM calls: enrichment + synthesis (no rewrite)
    assert len(call_log) == 2


# ---------------------------------------------------------------------------
# 2. Missing section → rewrite 1 → PASS
# ---------------------------------------------------------------------------


async def test_missing_section_triggers_one_rewrite_then_pass(client):
    """validate_report flags MISSING_SECTION → rewrite → valid draft → PASS."""
    call_log: list[str] = []

    async def _mock(sys_p: str, usr_p: str):
        call_log.append("call")
        if len(call_log) == 1:
            return _EMPTY_ENRICHMENT, _usage()
        if len(call_log) == 2:
            return _synthesis_missing_section(), _usage()
        # 3rd call = rewrite → return valid draft
        return _synthesis_ok(), _usage()

    with patch("app.graph.user_briefing_graph.llm_client") as m:
        m.enabled = True
        m.call_json = _mock
        resp = await client.post("/briefings/generate", json=FULL_REQUEST)

    assert resp.status_code == 200
    body = resp.json()
    assert body["summary"] == "LLM 한 줄 요약"
    # Enrichment + synthesis + rewrite = 3 calls total
    assert len(call_log) == 3


# ---------------------------------------------------------------------------
# 3. ID order wrong → rewrite 1 → PASS
# ---------------------------------------------------------------------------


async def test_id_order_mismatch_triggers_one_rewrite_then_pass(client):
    """IDs returned in wrong order → RETRYABLE → rewrite → corrected order → PASS."""
    call_log: list[str] = []

    async def _mock(sys_p: str, usr_p: str):
        call_log.append("call")
        if len(call_log) == 1:
            return _EMPTY_ENRICHMENT, _usage()
        if len(call_log) == 2:
            # Wrong order: 3→1→2
            return _synthesis_wrong_id_order(), _usage()
        # Rewrite corrects the order
        return _synthesis_ok(), _usage()

    with patch("app.graph.user_briefing_graph.llm_client") as m:
        m.enabled = True
        m.call_json = _mock
        resp = await client.post("/briefings/generate", json=FULL_REQUEST)

    assert resp.status_code == 200
    assert resp.json()["summary"] == "LLM 한 줄 요약"
    assert len(call_log) == 3


# ---------------------------------------------------------------------------
# 4. First draft fail + rewrite also fails → deterministic fallback
# ---------------------------------------------------------------------------


async def test_draft_fail_and_rewrite_fail_triggers_fallback(client):
    """Synthesis RETRYABLE → rewrite also RETRYABLE (rewrite_count=1) → fallback."""
    call_log: list[str] = []

    async def _mock(sys_p: str, usr_p: str):
        call_log.append("call")
        if len(call_log) == 1:
            return _EMPTY_ENRICHMENT, _usage()
        # Both synthesis and rewrite return missing section (cannot pass)
        return _synthesis_missing_section(), _usage()

    with patch("app.graph.user_briefing_graph.llm_client") as m:
        m.enabled = True
        m.call_json = _mock
        resp = await client.post("/briefings/generate", json=FULL_REQUEST)

    assert resp.status_code == 200
    body = resp.json()
    # Fallback produces deterministic content with all required sections
    assert "## 오늘의 핵심 요약" in body["content"]
    assert len(body["articles"]) == 3
    # Enrichment + synthesis + rewrite = 3 LLM calls, then fallback (no 4th call)
    assert len(call_log) == 3


# ---------------------------------------------------------------------------
# 5. Enrichment LLM exception → deterministic fallback
# ---------------------------------------------------------------------------


async def test_enrichment_exception_routes_to_fallback(client):
    """Enrichment LLM exception → llm_error_category=enrichment_failed → fallback."""
    with patch("app.graph.user_briefing_graph.llm_client") as m:
        m.enabled = True
        m.call_json = AsyncMock(side_effect=LLMClientError("enrichment boom"))
        resp = await client.post("/briefings/generate", json=FULL_REQUEST)

    assert resp.status_code == 200
    body = resp.json()
    assert body["title"]
    assert len(body["articles"]) == 3
    # No synthesis was attempted, so token usage is 0
    assert body["tokenUsage"]["inputTokens"] == 0


# ---------------------------------------------------------------------------
# 6. Synthesis LLM exception → deterministic fallback
# ---------------------------------------------------------------------------


async def test_synthesis_exception_routes_to_fallback(client):
    """Synthesis LLM exception → llm_error_category=synthesis_failed → fallback."""
    with patch("app.graph.user_briefing_graph.llm_client") as m:
        m.enabled = True
        m.call_json = AsyncMock(side_effect=[
            (_EMPTY_ENRICHMENT, _usage(50, 20)),
            LLMClientError("synthesis boom"),
        ])
        resp = await client.post("/briefings/generate", json=FULL_REQUEST)

    assert resp.status_code == 200
    body = resp.json()
    assert body["title"]
    assert len(body["articles"]) == 3
    # Enrichment tokens consumed, synthesis token = 0
    assert body["tokenUsage"]["inputTokens"] == 50


# ---------------------------------------------------------------------------
# 7. Rewrite LLM exception → deterministic fallback
# ---------------------------------------------------------------------------


async def test_rewrite_exception_routes_to_fallback(client):
    """Rewrite LLM exception → llm_error_category=rewrite_failed → fallback."""
    with patch("app.graph.user_briefing_graph.llm_client") as m:
        m.enabled = True
        m.call_json = AsyncMock(side_effect=[
            (_EMPTY_ENRICHMENT, _usage(50, 20)),
            (_synthesis_missing_section(), _usage(100, 40)),
            LLMClientError("rewrite boom"),
        ])
        resp = await client.post("/briefings/generate", json=FULL_REQUEST)

    assert resp.status_code == 200
    body = resp.json()
    assert body["title"]
    assert len(body["articles"]) == 3
    # Enrichment + synthesis tokens accumulated (rewrite failed before returning tokens)
    assert body["tokenUsage"]["inputTokens"] == 50 + 100


# ---------------------------------------------------------------------------
# 8. rewriteCount never exceeds 1
# ---------------------------------------------------------------------------


async def test_rewrite_count_never_exceeds_1(client):
    """Graph routes to fallback after first rewrite (count=1), never retries again."""
    call_log: list[str] = []

    async def _mock(sys_p: str, usr_p: str):
        call_log.append("call")
        if len(call_log) == 1:
            return _EMPTY_ENRICHMENT, _usage()
        # Every subsequent call returns invalid draft (missing section, wrong IDs)
        return _synthesis_missing_section(), _usage()

    with patch("app.graph.user_briefing_graph.llm_client") as m:
        m.enabled = True
        m.call_json = _mock
        resp = await client.post("/briefings/generate", json=FULL_REQUEST)

    assert resp.status_code == 200
    # calls: enrichment(1) + synthesis(2) + rewrite(3) → fallback. Never a 4th LLM call.
    assert len(call_log) == 3


# ---------------------------------------------------------------------------
# 9. Fallback preserves Backend rank order
# ---------------------------------------------------------------------------


async def test_fallback_preserves_backend_rank_order(client):
    """Deterministic fallback must output articles in Backend rank order (1→2→3)."""
    # Force fallback via synthesis exception
    with patch("app.graph.user_briefing_graph.llm_client") as m:
        m.enabled = True
        m.call_json = AsyncMock(side_effect=LLMClientError("boom"))
        resp = await client.post("/briefings/generate", json=FULL_REQUEST)

    assert resp.status_code == 200
    articles = resp.json()["articles"]
    assert len(articles) == 3
    # _POOL_POSTINGS: rank 1=네이버, rank 2=카카오, rank 3=라인
    assert "네이버" in articles[0]["title"]
    assert "카카오" in articles[1]["title"]
    assert "라인" in articles[2]["title"]


# ---------------------------------------------------------------------------
# 10. isNew and isUrgent both displayed in fallback
# ---------------------------------------------------------------------------


async def test_new_and_urgent_both_shown_in_fallback(client):
    """When isNew=True and isUrgent=True, deterministic fallback shows both badges."""
    from datetime import date, timedelta

    urgent_new_posting = dict(_POOL_POSTINGS[0])
    urgent_new_posting["isNew"] = True
    urgent_new_posting["isUrgent"] = True
    urgent_new_posting["deadline"] = (date.today() + timedelta(days=2)).isoformat()

    request = {
        **FULL_REQUEST,
        "briefingDate": date.today().isoformat(),
        "candidatePool": {
            "jobPostings": [urgent_new_posting],
            "companyIssues": [],
            "industryIssues": [],
        },
    }

    with patch("app.graph.user_briefing_graph.llm_client") as m:
        m.enabled = True
        m.call_json = AsyncMock(side_effect=LLMClientError("boom"))
        resp = await client.post("/briefings/generate", json=request)

    assert resp.status_code == 200
    content = resp.json()["content"]
    assert "🆕 NEW" in content, "isNew badge missing"
    assert "⏰ URGENT" in content, "isUrgent badge missing"


# ---------------------------------------------------------------------------
# 11. Token usage accumulates enrichment + synthesis + rewrite
# ---------------------------------------------------------------------------


async def test_token_usage_accumulates_across_all_llm_calls(client):
    """Total token usage = enrichment + synthesis + rewrite tokens."""
    with patch("app.graph.user_briefing_graph.llm_client") as m:
        m.enabled = True
        m.call_json = AsyncMock(side_effect=[
            (_EMPTY_ENRICHMENT, _usage(100, 50)),           # enrichment
            (_synthesis_missing_section(), _usage(200, 80)), # synthesis (RETRYABLE)
            (_synthesis_ok(), _usage(150, 60)),              # rewrite (PASS)
        ])
        resp = await client.post("/briefings/generate", json=FULL_REQUEST)

    assert resp.status_code == 200
    usage = resp.json()["tokenUsage"]
    assert usage["inputTokens"] == 100 + 200 + 150   # 450
    assert usage["outputTokens"] == 50 + 80 + 60     # 190


# ---------------------------------------------------------------------------
# 12. Empty candidatePool → empty-state, no LLM calls
# ---------------------------------------------------------------------------


async def test_empty_candidatepool_uses_empty_state_no_llm(client):
    """Empty pool → check_pool routes to empty_state → END, LLM never called."""
    call_log: list[str] = []

    async def _mock(sys_p: str, usr_p: str):
        call_log.append("call")
        return {}, LLMTokenUsage()

    empty_request = {
        **FULL_REQUEST,
        "candidatePool": {"jobPostings": [], "companyIssues": [], "industryIssues": []},
    }

    with patch("app.graph.user_briefing_graph.llm_client") as m:
        m.enabled = True
        m.call_json = _mock
        resp = await client.post("/briefings/generate", json=empty_request)

    assert resp.status_code == 200
    body = resp.json()
    assert body["articles"] == []
    assert "없습니다" in body["content"]
    assert len(call_log) == 0   # LLM not called


# ---------------------------------------------------------------------------
# 13. Agent does not rerank (Backend rank preserved end-to-end)
# ---------------------------------------------------------------------------


async def test_agent_does_not_rerank_even_with_low_score_at_rank1(client):
    """posting with lower adjustedScore at rank 1 must appear first in articles."""
    low_score_rank1 = dict(_POOL_POSTINGS[0])
    low_score_rank1["rank"] = 1
    low_score_rank1["scoreBreakdown"] = {
        **low_score_rank1["scoreBreakdown"], "adjustedScore": 5
    }

    high_score_rank2 = dict(_POOL_POSTINGS[1])
    high_score_rank2["rank"] = 2
    high_score_rank2["scoreBreakdown"] = {
        **high_score_rank2["scoreBreakdown"], "adjustedScore": 999
    }

    request = {
        **FULL_REQUEST,
        "candidatePool": {
            "jobPostings": [low_score_rank1, high_score_rank2],
            "companyIssues": [],
            "industryIssues": [],
        },
    }

    with patch("app.graph.user_briefing_graph.llm_client") as m:
        m.enabled = True
        m.call_json = AsyncMock(side_effect=[
            (_EMPTY_ENRICHMENT, _usage()),
            ({
                "markdownContent": _VALID_MARKDOWN,
                "overallSummary": "요약",
                "referencedPostingIds": ["1", "2"],
            }, _usage()),
        ])
        resp = await client.post("/briefings/generate", json=request)

    assert resp.status_code == 200
    articles = resp.json()["articles"]
    assert len(articles) == 2
    # rank 1 (score=5, 네이버) must appear before rank 2 (score=999, 카카오)
    assert "네이버" in articles[0]["title"]
    assert "카카오" in articles[1]["title"]


# ---------------------------------------------------------------------------
# Unit tests for validate_report_node (deterministic, no LLM, no HTTP)
# ---------------------------------------------------------------------------


@pytest.mark.unit
def test_validate_pass_when_all_checks_ok():
    selected = _pool_postings()
    state: UserBriefingState = {
        "request": _make_request(),
        "selected": selected,
        "enrichments": {},
        "draft_summary": "한 줄 요약",
        "draft_content": _VALID_MARKDOWN,
        "draft_referenced_ids": ["1", "2", "3"],
        "validation_status": "pending",
        "validation_errors": [],
        "rewrite_count": 0,
        "articles": [],
        "title": "",
        "summary": "",
        "content": "",
        "token_usage": TokenUsage(),
        "llm_error_category": "",
        "fallback_reason": "",
        "used_fallback": False,
    }
    result = validate_report_node(state)
    assert result["validation_status"] == "pass"
    assert result["validation_errors"] == []


@pytest.mark.unit
def test_validate_retryable_on_missing_section():
    selected = _pool_postings()
    state: UserBriefingState = {
        "request": _make_request(),
        "selected": selected,
        "enrichments": {},
        "draft_summary": "요약",
        "draft_content": "# 오늘의 채용 브리핑\n\n내용만 있고 섹션 없음",
        "draft_referenced_ids": ["1", "2", "3"],
        "validation_status": "pending",
        "validation_errors": [],
        "rewrite_count": 0,
        "articles": [],
        "title": "",
        "summary": "",
        "content": "",
        "token_usage": TokenUsage(),
        "llm_error_category": "",
        "fallback_reason": "",
        "used_fallback": False,
    }
    result = validate_report_node(state)
    assert result["validation_status"] == "retryable"
    assert any("MISSING_SECTION" in e for e in result["validation_errors"])


@pytest.mark.unit
def test_validate_retryable_on_id_order_mismatch():
    selected = _pool_postings()
    state: UserBriefingState = {
        "request": _make_request(),
        "selected": selected,
        "enrichments": {},
        "draft_summary": "요약",
        "draft_content": _VALID_MARKDOWN,
        "draft_referenced_ids": ["3", "1", "2"],   # wrong order
        "validation_status": "pending",
        "validation_errors": [],
        "rewrite_count": 0,
        "articles": [],
        "title": "",
        "summary": "",
        "content": "",
        "token_usage": TokenUsage(),
        "llm_error_category": "",
        "fallback_reason": "",
        "used_fallback": False,
    }
    result = validate_report_node(state)
    assert result["validation_status"] == "retryable"
    assert "ID_ORDER_MISMATCH" in result["validation_errors"]


@pytest.mark.unit
def test_validate_fallback_required_on_empty_content():
    selected = _pool_postings()
    state: UserBriefingState = {
        "request": _make_request(),
        "selected": selected,
        "enrichments": {},
        "draft_summary": "",
        "draft_content": "",
        "draft_referenced_ids": [],
        "validation_status": "pending",
        "validation_errors": [],
        "rewrite_count": 0,
        "articles": [],
        "title": "",
        "summary": "",
        "content": "",
        "token_usage": TokenUsage(),
        "llm_error_category": "",
        "fallback_reason": "",
        "used_fallback": False,
    }
    result = validate_report_node(state)
    assert result["validation_status"] == "fallback_required"
    assert "EMPTY_CONTENT" in result["validation_errors"]


@pytest.mark.unit
def test_validate_fallback_required_on_investment_advice():
    selected = _pool_postings()
    state: UserBriefingState = {
        "request": _make_request(),
        "selected": selected,
        "enrichments": {},
        "draft_summary": "요약",
        "draft_content": _VALID_MARKDOWN + "\n매수 추천 종목: 삼성전자",
        "draft_referenced_ids": ["1", "2", "3"],
        "validation_status": "pending",
        "validation_errors": [],
        "rewrite_count": 0,
        "articles": [],
        "title": "",
        "summary": "",
        "content": "",
        "token_usage": TokenUsage(),
        "llm_error_category": "",
        "fallback_reason": "",
        "used_fallback": False,
    }
    result = validate_report_node(state)
    assert result["validation_status"] == "fallback_required"
    assert any("INVESTMENT_ADVICE" in e for e in result["validation_errors"])


@pytest.mark.unit
def test_validate_retryable_on_hallucination():
    selected = _pool_postings()
    state: UserBriefingState = {
        "request": _make_request(),
        "selected": selected,
        "enrichments": {},
        "draft_summary": "요약",
        "draft_content": _VALID_MARKDOWN + "\n합격 가능성이 높은 공고입니다.",
        "draft_referenced_ids": ["1", "2", "3"],
        "validation_status": "pending",
        "validation_errors": [],
        "rewrite_count": 0,
        "articles": [],
        "title": "",
        "summary": "",
        "content": "",
        "token_usage": TokenUsage(),
        "llm_error_category": "",
        "fallback_reason": "",
        "used_fallback": False,
    }
    result = validate_report_node(state)
    assert result["validation_status"] == "retryable"
    assert any("HALLUCINATION" in e for e in result["validation_errors"])


@pytest.mark.unit
def test_validate_retryable_on_unknown_url():
    selected = _pool_postings()
    # Add a URL not in any posting's sourceUrl
    content_with_bad_url = _VALID_MARKDOWN + "\n[공고 보기](https://www.evil.com/fake)\n"
    state: UserBriefingState = {
        "request": _make_request(),
        "selected": selected,
        "enrichments": {},
        "draft_summary": "요약",
        "draft_content": content_with_bad_url,
        "draft_referenced_ids": ["1", "2", "3"],
        "validation_status": "pending",
        "validation_errors": [],
        "rewrite_count": 0,
        "articles": [],
        "title": "",
        "summary": "",
        "content": "",
        "token_usage": TokenUsage(),
        "llm_error_category": "",
        "fallback_reason": "",
        "used_fallback": False,
    }
    result = validate_report_node(state)
    assert result["validation_status"] == "retryable"
    assert any("UNKNOWN_URL" in e for e in result["validation_errors"])


# ---------------------------------------------------------------------------
# Unit tests for deterministic_fallback_node
# ---------------------------------------------------------------------------


@pytest.mark.unit
def test_fallback_includes_all_required_sections():
    selected = _pool_postings()
    state: UserBriefingState = {
        "request": _make_request(),
        "selected": selected,
        "enrichments": {},
        "draft_summary": "",
        "draft_content": "",
        "draft_referenced_ids": [],
        "validation_status": "fallback_required",
        "validation_errors": ["EMPTY_CONTENT"],
        "rewrite_count": 0,
        "articles": [],
        "title": "",
        "summary": "",
        "content": "",
        "token_usage": TokenUsage(),
        "llm_error_category": "synthesis_failed",
        "fallback_reason": "synthesis_llm_error",
        "used_fallback": False,
    }
    result = deterministic_fallback_node(state)
    content = result["content"]
    required = [
        "오늘의 핵심 요약",
        "추천 공고 TOP",
        "신규/마감 임박 공고",
        "오늘의 지원 추천 액션",
        "오늘의 키워드",
        "한 줄 정리",
    ]
    for section in required:
        assert section in content, f"missing section: {section}"
    assert result["used_fallback"] is True


@pytest.mark.unit
def test_fallback_rank_order_preserved():
    selected = _pool_postings()
    state: UserBriefingState = {
        "request": _make_request(),
        "selected": selected,
        "enrichments": {},
        "draft_summary": "",
        "draft_content": "",
        "draft_referenced_ids": [],
        "validation_status": "fallback_required",
        "validation_errors": [],
        "rewrite_count": 0,
        "articles": [],
        "title": "",
        "summary": "",
        "content": "",
        "token_usage": TokenUsage(),
        "llm_error_category": "",
        "fallback_reason": "test",
        "used_fallback": False,
    }
    result = deterministic_fallback_node(state)
    articles = result["articles"]
    assert len(articles) == 3
    assert "네이버" in articles[0].title   # rank 1
    assert "카카오" in articles[1].title   # rank 2
    assert "라인" in articles[2].title     # rank 3
