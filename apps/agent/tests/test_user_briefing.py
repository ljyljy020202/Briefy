"""Tests for the candidatePool-based user briefing workflow.

Covers:
- Contract validation: ≤7 postings accepted, Backend rank order preserved, no re-rank
- Basic pipeline correctness (pass-through, report generation)
- LLM integration: success path, fallback on failure, token usage accumulation
- LLM isolation: only selected top items sent to LLM, not the full pool
- Quality guardrails: empty pool handled gracefully
- Schema compatibility with backend
"""

import json
import re
from pathlib import Path
from unittest.mock import AsyncMock, patch

from app.core.llm_client import LLMTokenUsage
from tests.conftest import _POOL_POSTINGS, FULL_REQUEST

_FIXTURES_DIR = Path(__file__).parent / "fixtures"

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

_VALID_SYNTHESIS_RESPONSE = {
    "markdownContent": (
        "# 오늘의 채용 브리핑\n\n"
        "## 오늘의 핵심 요약\n\n"
        "- 2026-06-26 기준, 네이버 등 3건의 공고를 선별했습니다.\n"
        "- 백엔드 개발자 역할과 Spring Boot 스킬이 주요 매칭 조건입니다.\n"
        "- 네이버 공고를 오늘 확인해 보세요.\n\n"
        "## 🏆 추천 공고 TOP 3\n\n"
        "공고 내용\n\n"
        "## ⏰ 신규/마감 임박 공고\n\n"
        "선별된 공고 중 7일 이내 마감 임박 공고가 없습니다.\n\n"
        "## 💡 오늘의 지원 추천 액션\n\n"
        "1. 지원하세요\n\n"
        "## 🔑 오늘의 키워드\n\n"
        "Spring Boot · Java\n\n"
        "## ✏️ 한 줄 정리\n\n"
        "오늘의 핵심 요약."
    ),
    "overallSummary": "LLM이 생성한 한 줄 요약입니다.",
    # FULL_REQUEST has postings with IDs 1, 2, 3
    "referencedPostingIds": ["1", "2", "3"],
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


def _posting(id: int, rank: int, company: str, title: str, **extra) -> dict:
    return {
        "id": id,
        "rank": rank,
        "source": "test",
        "sourceUrl": f"https://example.com/{id}",
        "companyName": company,
        "title": title,
        "skills": [],
        "roles": [],
        "isNew": False,
        "isUrgent": False,
        "scoreBreakdown": {
            "roleScore": 0, "companyScore": 0, "skillScore": 0,
            "experienceScore": 0, "industryScore": 0, "locationScore": 0,
            "employmentTypeScore": 0, "companySizeScore": 0,
            "relevanceScore": 0, "exposurePenalty": 0,
            "adjustedScore": extra.pop("adjusted_score", 0),
        },
        "matchEvidence": {
            "matchedRoles": [],
            "matchedCompanies": [],
            "matchedSkills": [],
            "matchedLocations": [],
            "matchedExperienceLevels": [],
            "matchedEmploymentTypes": [],
            "matchedIndustries": [],
            "matchedCompanySizes": [],
        },
        **extra,
    }


def _pool(*postings: dict) -> dict:
    return {"jobPostings": list(postings), "companyIssues": [], "industryIssues": []}


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


# ---------------------------------------------------------------------------
# Contract validation: rank order preserved, no re-rank
# ---------------------------------------------------------------------------


async def test_contract_backend_rank_order_preserved_when_sent_in_order(client):
    """Postings sent in rank order 1→2→3 arrive in articles order 1→2→3."""
    response = await client.post("/briefings/generate", json=FULL_REQUEST)
    articles = response.json()["articles"]
    assert len(articles) == 3
    assert "네이버" in articles[0]["title"]
    assert "카카오" in articles[1]["title"]
    assert "라인" in articles[2]["title"]


async def test_contract_backend_rank_order_preserved_when_shuffled(client):
    """Postings sent in shuffled rank order are sorted to Backend's intended rank."""
    # Send rank 3 → rank 1 → rank 2; Agent should sort to rank 1→2→3
    shuffled = [_POOL_POSTINGS[2], _POOL_POSTINGS[0], _POOL_POSTINGS[1]]
    request = {
        **FULL_REQUEST,
        "candidatePool": {
            "jobPostings": shuffled,
            "companyIssues": [],
            "industryIssues": [],
        },
    }
    response = await client.post("/briefings/generate", json=request)
    assert response.status_code == 200
    articles = response.json()["articles"]
    assert len(articles) == 3
    assert "네이버" in articles[0]["title"]   # rank 1
    assert "카카오" in articles[1]["title"]   # rank 2
    assert "라인" in articles[2]["title"]     # rank 3


async def test_contract_no_agent_reranking(client):
    """Agent does not re-score; lower adjustedScore at rank 1 stays first."""
    # rank 1 = adjustedScore 10 (low score); rank 2 = adjustedScore 100 (high score)
    # Agent must NOT re-rank by score; Backend rank is authoritative
    low_score_rank1 = dict(_POOL_POSTINGS[0])
    low_score_rank1["rank"] = 1
    low_score_rank1["scoreBreakdown"] = {
        **low_score_rank1["scoreBreakdown"], "adjustedScore": 10
    }

    high_score_rank2 = dict(_POOL_POSTINGS[1])
    high_score_rank2["rank"] = 2
    high_score_rank2["scoreBreakdown"] = {
        **high_score_rank2["scoreBreakdown"], "adjustedScore": 100
    }

    request = {
        **FULL_REQUEST,
        "candidatePool": {
            "jobPostings": [low_score_rank1, high_score_rank2],
            "companyIssues": [],
            "industryIssues": [],
        },
    }
    response = await client.post("/briefings/generate", json=request)
    assert response.status_code == 200
    articles = response.json()["articles"]
    assert len(articles) == 2
    # rank 1 (lower score) must be first — Backend order preserved
    assert "네이버" in articles[0]["title"]
    assert "카카오" in articles[1]["title"]


async def test_contract_7_postings_all_accepted(client):
    """Exactly 7 postings are all accepted and appear in articles."""
    seven_postings = [
        _posting(i, i, f"회사{i}", f"백엔드 개발자 {i}")
        for i in range(1, 8)
    ]
    request = {
        **FULL_REQUEST,
        "candidatePool": {
            "jobPostings": seven_postings,
            "companyIssues": [],
            "industryIssues": [],
        },
    }
    response = await client.post("/briefings/generate", json=request)
    assert response.status_code == 200
    assert len(response.json()["articles"]) == 7


async def test_contract_excess_postings_truncated_to_7(client):
    """If Backend sends >7 postings (bug), Agent truncates to 7."""
    eight_postings = [
        _posting(i, i, f"회사{i}", f"백엔드 개발자 {i}")
        for i in range(1, 9)
    ]
    request = {
        **FULL_REQUEST,
        "candidatePool": {
            "jobPostings": eight_postings,
            "companyIssues": [],
            "industryIssues": [],
        },
    }
    response = await client.post("/briefings/generate", json=request)
    assert response.status_code == 200
    assert len(response.json()["articles"]) <= 7


async def test_contract_posting_missing_title_is_skipped(client):
    """Postings with no title are silently dropped; pipeline continues."""
    valid = _posting(1, 1, "네이버", "백엔드 개발자")
    no_title = {**_posting(2, 2, "카카오", ""), "title": None}
    request = {
        **FULL_REQUEST,
        "candidatePool": {
            "jobPostings": [valid, no_title],
            "companyIssues": [],
            "industryIssues": [],
        },
    }
    response = await client.post("/briefings/generate", json=request)
    assert response.status_code == 200
    articles = response.json()["articles"]
    assert len(articles) == 1
    assert "네이버" in articles[0]["title"]


# ---------------------------------------------------------------------------
# matchEvidence in whyItMatters
# ---------------------------------------------------------------------------


async def test_match_evidence_used_in_why_it_matters(client):
    """When matchEvidence has content, it drives the whyItMatters text."""
    response = await client.post("/briefings/generate", json=FULL_REQUEST)
    assert response.status_code == 200
    articles = response.json()["articles"]
    # FULL_REQUEST postings have matchEvidence with actual company/role/skill names
    assert any(
        "네이버" in a.get("whyItMatters", "") or
        "카카오" in a.get("whyItMatters", "") or
        "라인" in a.get("whyItMatters", "")
        for a in articles
    )


# ---------------------------------------------------------------------------
# Fallback mode (no API key in test env)
# ---------------------------------------------------------------------------


async def test_no_llm_calls_made(client, monkeypatch):
    """Without OPENAI_API_KEY, LLM is skipped and token usage stays zero."""
    from unittest.mock import MagicMock

    import app.graph.user_briefing_graph as _graph

    fake = MagicMock()
    fake.enabled = False
    monkeypatch.setattr(_graph, "llm_client", fake)
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
# LLM isolation: only selected postings sent to enrichment
# ---------------------------------------------------------------------------


async def test_llm_enrichment_receives_only_selected_postings_not_full_pool(client):
    """Even if more than 7 postings arrive (backend bug), LLM only enriches ≤7."""
    many_postings = [
        _posting(i, i, f"회사{i}", "백엔드 개발자")
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
            # Enrichment call — just return empty enrichments
            return {"enrichments": []}, LLMTokenUsage()
        # Synthesis call — return a valid response with the 7 selected IDs (1..7)
        return {
            "markdownContent": (
                "# 오늘의 채용 브리핑\n\n"
                "## 오늘의 핵심 요약\n\n- 선별 요약\n\n"
                "## 🏆 추천 공고 TOP 7\n\n공고 내용\n\n"
                "## ⏰ 신규/마감 임박 공고\n\n없습니다.\n\n"
                "## 💡 오늘의 지원 추천 액션\n\n1. 지원\n\n"
                "## 🔑 오늘의 키워드\n\nJava\n\n"
                "## ✏️ 한 줄 정리\n\n정리."
            ),
            "overallSummary": "7건 선별",
            "referencedPostingIds": [str(i) for i in range(1, 8)],
        }, LLMTokenUsage()

    with patch("app.graph.user_briefing_graph.llm_client") as mock_llm:
        mock_llm.enabled = True
        mock_llm.call_json = _mock_call_json
        response = await client.post("/briefings/generate", json=request)

    assert response.status_code == 200
    assert len(captured) >= 1
    posting_count = len(re.findall(r'"id":\s*"\d+"', captured[0]))
    assert posting_count <= 7    # _TOP_N
    assert posting_count < 15   # definitely not the full pool


# ---------------------------------------------------------------------------
# LLM fallback on failure
# ---------------------------------------------------------------------------


async def test_llm_enrichment_failure_falls_back_to_deterministic(client):
    """When enrichment LLM call fails, pipeline continues with deterministic output."""
    from app.core.llm_client import LLMClientError

    with patch("app.graph.user_briefing_graph.llm_client") as mock_llm:
        mock_llm.enabled = True
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
    from app.core.llm_client import LLMClientError

    with patch("app.graph.user_briefing_graph.llm_client") as mock_llm:
        mock_llm.enabled = True
        mock_llm.call_json = AsyncMock(
            side_effect=[
                (
                    _VALID_ENRICHMENT_RESPONSE,
                    LLMTokenUsage(input_tokens=50, output_tokens=20, total_tokens=70),
                ),
                LLMClientError("mock synthesis error"),
            ]
        )
        response = await client.post("/briefings/generate", json=FULL_REQUEST)

    assert response.status_code == 200
    body = response.json()
    assert "##" in body["content"]
    assert len(body["articles"]) > 0


async def test_llm_invalid_json_falls_back_safely(client):
    """Non-parseable LLM response triggers fallback without crashing."""
    from app.core.llm_client import LLMClientError

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


# ---------------------------------------------------------------------------
# Report structure quality
# ---------------------------------------------------------------------------


async def test_report_has_top_level_heading(client):
    """Deterministic fallback report starts with the top-level heading."""
    response = await client.post("/briefings/generate", json=FULL_REQUEST)
    content = response.json()["content"]
    assert content.startswith("# 오늘의 채용 브리핑"), (
        f"content does not start with top-level heading; got: {content[:80]!r}"
    )


async def test_required_sections_present_in_fallback_report(client):
    """All six required sections exist in the deterministic fallback report."""
    response = await client.post("/briefings/generate", json=FULL_REQUEST)
    content = response.json()["content"]
    required_sections = [
        "오늘의 핵심 요약",
        "추천 공고 TOP",
        "신규/마감 임박 공고",
        "오늘의 지원 추천 액션",
        "오늘의 키워드",
        "한 줄 정리",
    ]
    for section in required_sections:
        assert section in content, f"required section missing: {section}"


async def test_empty_pool_report_also_has_required_sections(client):
    """Even the empty-pool report keeps all required section headers."""
    request = {
        **FULL_REQUEST,
        "candidatePool": {
            "jobPostings": [],
            "companyIssues": [],
            "industryIssues": [],
        },
    }
    response = await client.post("/briefings/generate", json=request)
    content = response.json()["content"]
    for section in ["오늘의 핵심 요약", "추천 공고 TOP", "신규/마감 임박 공고",
                    "오늘의 지원 추천 액션", "오늘의 키워드", "한 줄 정리"]:
        assert section in content, f"empty-state section missing: {section}"


# ---------------------------------------------------------------------------
# Matching reason quality
# ---------------------------------------------------------------------------


async def test_matching_reason_excludes_vague_phrases(client):
    """whyItMatters must not contain generic filler phrases."""
    response = await client.post("/briefings/generate", json=FULL_REQUEST)
    articles = response.json()["articles"]
    vague_phrases = [
        "좋은 기회입니다",
        "적합한 공고입니다",
        "사용자 관심사와 잘 맞습니다",
        "추천드립니다",
        "기대해볼 만한",
        "선호도 기반 추천 공고",
    ]
    for article in articles:
        why = article.get("whyItMatters", "")
        for phrase in vague_phrases:
            assert phrase not in why, (
                f"vague phrase {phrase!r} found in whyItMatters: {why!r}"
            )


async def test_matching_reason_includes_concrete_terms(client):
    """whyItMatters must mention actual preference terms from matchEvidence."""
    response = await client.post("/briefings/generate", json=FULL_REQUEST)
    articles = response.json()["articles"]
    # Terms in FULL_REQUEST matchEvidence fields
    concrete_terms = {
        "Spring Boot", "Java", "Kotlin",
        "백엔드 개발자", "풀스택 개발자",
        "네이버", "카카오", "라인",
        "서울", "판교",
        "신입", "3년 이상", "정규직",
    }
    for article in articles:
        why = article.get("whyItMatters", "")
        matched = [t for t in concrete_terms if t in why]
        assert matched, (
            f"no concrete preference terms found in whyItMatters: {why!r}"
        )


# ---------------------------------------------------------------------------
# Hallucination guardrails
# ---------------------------------------------------------------------------


async def test_no_acceptance_probability_in_fallback_content(client):
    """Deterministic report must not contain hallucination patterns."""
    response = await client.post("/briefings/generate", json=FULL_REQUEST)
    content = response.json()["content"]
    hallucination_phrases = [
        "합격 가능성이 높",
        "합격 보장",
        "반드시 합격",
        "경쟁률이 낮",
    ]
    for phrase in hallucination_phrases:
        assert phrase not in content, (
            f"hallucination phrase {phrase!r} found in content"
        )


# ---------------------------------------------------------------------------
# isNew / isUrgent flags
# ---------------------------------------------------------------------------


async def test_is_urgent_flag_reflected_in_why_it_matters(client):
    """Posting with isUrgent=True and deadline within 7 days surfaces urgency text."""
    from datetime import date, timedelta

    urgent_deadline = (date.today() + timedelta(days=3)).isoformat()
    urgent_posting = dict(_POOL_POSTINGS[0])
    urgent_posting["isUrgent"] = True
    urgent_posting["deadline"] = urgent_deadline

    request = {
        **FULL_REQUEST,
        "briefingDate": date.today().isoformat(),
        "candidatePool": {
            "jobPostings": [urgent_posting],
            "companyIssues": [],
            "industryIssues": [],
        },
    }
    response = await client.post("/briefings/generate", json=request)
    assert response.status_code == 200
    articles = response.json()["articles"]
    assert len(articles) == 1
    why = articles[0].get("whyItMatters", "")
    # Urgency should be surfaced when isUrgent=True
    assert "마감" in why or "긴급" in why, f"expected urgency in whyItMatters: {why!r}"


# ---------------------------------------------------------------------------
# isNew + isUrgent simultaneously true
# ---------------------------------------------------------------------------


async def test_is_new_and_is_urgent_both_true_accepted(client):
    """A posting with isNew=True and isUrgent=True simultaneously is accepted (200)."""
    from datetime import date, timedelta

    posting = dict(_POOL_POSTINGS[0])
    posting["isNew"] = True
    posting["isUrgent"] = True
    posting["deadline"] = (date.today() + timedelta(days=2)).isoformat()

    request = {
        **FULL_REQUEST,
        "briefingDate": date.today().isoformat(),
        "candidatePool": {
            "jobPostings": [posting],
            "companyIssues": [],
            "industryIssues": [],
        },
    }
    response = await client.post("/briefings/generate", json=request)
    assert response.status_code == 200
    articles = response.json()["articles"]
    assert len(articles) == 1


# ---------------------------------------------------------------------------
# Spring-Agent contract fixture — shared deserialization test
# ---------------------------------------------------------------------------


async def test_contract_fixture_accepted_by_agent_schema(client):
    """The shared contract JSON fixture round-trips through Agent without error."""
    fixture_path = _FIXTURES_DIR / "contract_job_briefing_request.json"
    assert fixture_path.exists(), f"fixture not found: {fixture_path}"
    payload = json.loads(fixture_path.read_text())

    response = await client.post("/briefings/generate", json=payload)
    assert response.status_code == 200, (
        f"contract fixture rejected: {response.status_code} {response.text}"
    )
    body = response.json()
    for field in ("title", "summary", "content", "articles", "tokenUsage"):
        assert field in body, f"missing top-level field: {field}"


async def test_contract_fixture_preserves_both_postings(client):
    """Both postings in the contract fixture appear in articles."""
    fixture_path = _FIXTURES_DIR / "contract_job_briefing_request.json"
    payload = json.loads(fixture_path.read_text())

    response = await client.post("/briefings/generate", json=payload)
    assert response.status_code == 200
    articles = response.json()["articles"]
    assert len(articles) == 2
    companies = {a.get("companyName") for a in articles}
    assert "네이버" in companies
    assert "카카오" in companies


async def test_contract_fixture_is_new_and_urgent_posting_accepted(client):
    """The contract fixture's rank-1 posting (isNew=True, isUrgent=True) is included."""
    fixture_path = _FIXTURES_DIR / "contract_job_briefing_request.json"
    payload = json.loads(fixture_path.read_text())

    response = await client.post("/briefings/generate", json=payload)
    assert response.status_code == 200
    articles = response.json()["articles"]
    # rank-1 posting is 네이버 (isNew=True, isUrgent=True)
    assert articles[0].get("companyName") == "네이버"


# ---------------------------------------------------------------------------
# scoreBreakdown and matchEvidence field presence
# ---------------------------------------------------------------------------


async def test_score_breakdown_fields_do_not_appear_in_response(client):
    """scoreBreakdown is input-only; it must not leak into Agent response."""
    response = await client.post("/briefings/generate", json=FULL_REQUEST)
    assert response.status_code == 200
    body_text = response.text
    assert "scoreBreakdown" not in body_text, (
        "scoreBreakdown must not appear in Agent response"
    )
    assert "adjustedScore" not in body_text, (
        "adjustedScore must not appear in Agent response"
    )
