"""LLM-enhanced user briefing workflow — LangGraph implementation.

Graph topology (abbreviated):
  check_pool → empty_state → END
             → enrich_postings → [fail] → fallback → END
                               → synthesize_report → [fail] → fallback
                                                   → validate_report
                                                     → pass → END
                                                     → retryable (n=0) → rewrite_report
                                                       → [fail] → fallback → END
                                                       → validate_report (max 1 cycle)
                                                     → retryable (n≥1) → fallback
                                                     → fallback_required → fallback

Invariants:
- Backend Top-7 rank order is preserved end-to-end.
- LLM is never called for filtering, ranking, or selection decisions.
- Rewrite is attempted at most once (rewrite_count ≤ 1).
- Every LLM failure or validation failure routes to deterministic_fallback_node.
- The pipeline never raises HTTP 500 due to LLM error.
"""

from __future__ import annotations

import logging
import re
from datetime import date as date_cls
from typing import TypedDict

from langgraph.graph import END, StateGraph

from app.core.llm_client import (
    LLMClientError,
    LLMTokenUsage,
    LLMUnavailableError,
    llm_client,
)
from app.prompts import briefing_synthesis, job_enrichment
from app.prompts import rewrite as rewrite_prompts
from app.prompts.briefing_synthesis import EnrichedArticleInput
from app.schemas.briefing import (
    BriefingGenerateRequest,
    BriefingGenerateResponse,
    CandidateJobPosting,
    GenerationMode,
    JobArticle,
    JobPostingPreference,
    TokenUsage,
)
from app.schemas.llm import JobPostingEnrichment, ValidationStatus

logger = logging.getLogger(__name__)

_TOP_N = 7
_MAX_CONTENT_LENGTH = 15000

_REQUIRED_SECTIONS = [
    "오늘의 핵심 요약",
    "추천 공고 TOP",
    "신규/마감 임박 공고",
    "오늘의 지원 추천 액션",
    "오늘의 키워드",
    "한 줄 정리",
]
_PATTERNS_RETRYABLE = [
    "합격 가능성이 높",
    "합격 보장",
    "반드시 합격",
    "경쟁률이 낮",
]
_PATTERNS_FALLBACK_REQUIRED = [
    "매수",
    "매도",
    "투자 추천",
    "수익률 보장",
]


# ---------------------------------------------------------------------------
# Graph state
# ---------------------------------------------------------------------------


class UserBriefingState(TypedDict):
    # ── Input (immutable after check_pool) ──────────────────────────────────
    request: BriefingGenerateRequest
    selected: list[CandidateJobPosting]   # Backend Top-7 sorted by rank

    # ── Enrichment (structured, from enrich_postings) ───────────────────────
    enrichments: dict[str, JobPostingEnrichment]

    # ── LLM draft (synthesis or rewrite output) ─────────────────────────────
    draft_summary: str
    draft_content: str
    draft_referenced_ids: list[str]      # posting IDs declared by LLM

    # ── Validation ──────────────────────────────────────────────────────────
    validation_status: ValidationStatus   # pending|pass|retryable|fallback_required
    validation_errors: list[str]          # error codes
    rewrite_count: int                    # max 1

    # ── Final output (written by synthesis/rewrite; overwritten by fallback) ─
    articles: list[JobArticle]
    title: str
    summary: str
    content: str

    # ── Observability ────────────────────────────────────────────────────────
    token_usage: TokenUsage
    llm_error_category: str  # "" | enrichment_failed | synthesis_failed
    fallback_reason: str
    used_fallback: bool


# ---------------------------------------------------------------------------
# Node: check_pool  (deterministic — sorts by Backend rank, validates pool)
# ---------------------------------------------------------------------------


def check_pool_node(state: UserBriefingState) -> dict:
    """Sort postings by Backend rank; validate contract invariants."""
    req = state["request"]
    postings = req.candidate_pool.job_postings

    if len(postings) > _TOP_N:
        logger.warning(
            "[check_pool] userId=%s received %d postings (max %d); truncating",
            req.user_id, len(postings), _TOP_N,
        )
        postings = postings[:_TOP_N]

    valid: list[CandidateJobPosting] = []
    for p in postings:
        if not p.title or not p.source_url:
            logger.warning(
                "[check_pool] userId=%s skipping posting id=%s (no title/url)",
                req.user_id, p.id,
            )
            continue
        valid.append(p)

    selected = sorted(valid, key=lambda p: p.rank if p.rank > 0 else _TOP_N + 1)

    if req.category != "JOB_POSTING":
        logger.info("[check_pool] category=%s → empty selected", req.category)
        return {"selected": []}

    logger.info(
        "[check_pool] userId=%s briefingDate=%s selected=%d postings",
        req.user_id, req.briefing_date, len(selected),
    )
    return {"selected": selected}


# ---------------------------------------------------------------------------
# Node: empty_state  (deterministic — no candidates)
# ---------------------------------------------------------------------------


def empty_state_node(state: UserBriefingState) -> dict:
    req = state["request"]
    logger.info("[empty_state] userId=%s no candidates", req.user_id)
    result = _build_empty_report(req.briefing_date)
    return {**result, "used_fallback": False, "fallback_reason": ""}


# ---------------------------------------------------------------------------
# Node: enrich_postings  (async — LLM batch enrichment)
# ---------------------------------------------------------------------------


async def enrich_postings_node(state: UserBriefingState) -> dict:
    """Call LLM once to enrich all selected postings in a single batch.

    On LLM exception: sets llm_error_category → graph routes to fallback.
    When LLM is disabled: returns empty enrichments and proceeds normally.
    """
    req = state["request"]
    selected = state["selected"]
    existing_usage = state.get("token_usage", TokenUsage())

    if not selected or not llm_client.enabled:
        return {"enrichments": {}, "token_usage": existing_usage}

    try:
        raw, usage = await llm_client.call_json(
            job_enrichment.get_system_prompt(),
            job_enrichment.build_user_prompt(req.preference, selected),
        )
        enrichment_list = job_enrichment.parse_response(raw)
        enrichments: dict[str, JobPostingEnrichment] = {
            e.id: e for e in enrichment_list
        }
        new_usage = _add_usage(existing_usage, usage)
        logger.info(
            "[enrich_postings] userId=%s enriched=%d/%d inputTokens=%d",
            req.user_id, len(enrichments), len(selected), new_usage.input_tokens,
        )
        return {
            "enrichments": enrichments,
            "token_usage": new_usage,
            "llm_error_category": "",
        }
    except (LLMUnavailableError, LLMClientError) as exc:
        logger.warning(
            "[enrich_postings] userId=%s LLM enrichment failed (%s: %s)",
            req.user_id, type(exc).__name__, exc,
        )
        return {
            "enrichments": {},
            "token_usage": existing_usage,
            "llm_error_category": "enrichment_failed",
            "fallback_reason": f"enrichment_llm_error: {type(exc).__name__}",
        }


# ---------------------------------------------------------------------------
# Node: synthesize_report  (async — LLM full report synthesis)
# ---------------------------------------------------------------------------


async def synthesize_report_node(state: UserBriefingState) -> dict:
    """Build the full briefing report.

    Deterministic path (LLM disabled): builds template-based report and sets
    draft_referenced_ids to the expected Backend ID order so validation passes.

    LLM path: calls LLM for markdownContent + overallSummary + referencedPostingIds.
    On exception: sets llm_error_category → graph routes to fallback.
    """
    req = state["request"]
    selected = state["selected"]
    enrichments = state.get("enrichments", {})
    existing_usage = state.get("token_usage", TokenUsage())

    try:
        today = date_cls.fromisoformat(req.briefing_date)
    except ValueError:
        today = date_cls.today()

    enriched_inputs = _build_enriched_inputs(
        selected, enrichments, req.preference, today
    )
    articles = _build_articles_from_inputs(enriched_inputs, req.briefing_date)
    primary_role = req.preference.roles[0] if req.preference.roles else "개발자"
    title = f"오늘의 채용 브리핑 — {primary_role} ({req.briefing_date})"
    expected_ids = [str(p.id) for p in selected]

    if llm_client.enabled:
        try:
            raw, usage = await llm_client.call_json(
                briefing_synthesis.get_system_prompt(len(enriched_inputs)),
                briefing_synthesis.build_user_prompt(
                    req.briefing_date, req.preference, enriched_inputs
                ),
            )
            result = briefing_synthesis.parse_response(raw)
            new_usage = _add_usage(existing_usage, usage)
            logger.info(
                "[synthesize_report] userId=%s LLM synthesis ok inputTokens=%d",
                req.user_id, new_usage.input_tokens,
            )
            return {
                "articles": articles,
                "title": title,
                "draft_summary": result.overall_summary,
                "draft_content": result.markdown_content,
                "draft_referenced_ids": result.referenced_posting_ids,
                "summary": result.overall_summary,
                "content": result.markdown_content,
                "token_usage": new_usage,
                "llm_error_category": "",
                "validation_status": "pending",
                "validation_errors": [],
            }
        except (LLMUnavailableError, LLMClientError) as exc:
            logger.warning(
                "[synthesize_report] userId=%s LLM synthesis failed (%s: %s)",
                req.user_id, type(exc).__name__, exc,
            )
            return {
                "articles": articles,
                "title": title,
                "draft_summary": "",
                "draft_content": "",
                "draft_referenced_ids": [],
                "token_usage": existing_usage,
                "llm_error_category": "synthesis_failed",
                "fallback_reason": f"synthesis_llm_error: {type(exc).__name__}",
            }
        except Exception as exc:
            logger.warning(
                "[synthesize_report] userId=%s synthesis response invalid (%s: %s)",
                req.user_id, type(exc).__name__, exc,
            )
            return {
                "articles": articles,
                "title": title,
                "draft_summary": "",
                "draft_content": "",
                "draft_referenced_ids": [],
                "token_usage": existing_usage,
                "llm_error_category": "synthesis_failed",
                "fallback_reason": f"synthesis_parse_error: {type(exc).__name__}",
            }

    # LLM disabled — build deterministic report; set referenced IDs correctly so
    # validate_report_node passes on the first check.
    det = _build_deterministic_report(
        enriched_inputs, req.preference, req.briefing_date, articles, existing_usage
    )
    return {
        **det,
        "draft_summary": det["summary"],
        "draft_content": det["content"],
        "draft_referenced_ids": expected_ids,
        "llm_error_category": "",
        "validation_status": "pending",
        "validation_errors": [],
    }


# ---------------------------------------------------------------------------
# Node: validate_report  (deterministic — no LLM)
# ---------------------------------------------------------------------------


def validate_report_node(state: UserBriefingState) -> dict:
    """Validate LLM draft content deterministically.

    Returns validation_status (PASS / RETRYABLE / FALLBACK_REQUIRED) and a
    list of error codes. Does not call LLM.
    """
    draft_content = state.get("draft_content", "")
    draft_summary = state.get("draft_summary", "")
    draft_referenced_ids = state.get("draft_referenced_ids", [])
    selected = state.get("selected", [])
    enrichments = state.get("enrichments", {})
    req = state["request"]

    errors: list[str] = []
    status: ValidationStatus = "pass"

    def retryable(code: str) -> None:
        nonlocal status
        errors.append(code)
        if status == "pass":
            status = "retryable"

    def fallback_required(code: str) -> None:
        nonlocal status
        errors.append(code)
        status = "fallback_required"

    # 1. Empty content → FALLBACK_REQUIRED (nothing for LLM to fix)
    if not draft_content.strip():
        fallback_required("EMPTY_CONTENT")
        logger.warning(
            "[validate_report] userId=%s FALLBACK_REQUIRED: EMPTY_CONTENT",
            req.user_id,
        )
        return {"validation_status": status, "validation_errors": errors}

    # 2. Empty summary → RETRYABLE
    if not draft_summary.strip():
        retryable("EMPTY_SUMMARY")

    # 3. Required sections
    for section in _REQUIRED_SECTIONS:
        if section not in draft_content:
            retryable(f"MISSING_SECTION:{section}")

    # 4. referencedPostingIds must match Backend IDs (count + order)
    expected_ids = [str(p.id) for p in selected]
    if draft_referenced_ids != expected_ids:
        expected_set = set(expected_ids)
        draft_set = set(draft_referenced_ids)
        if draft_set == expected_set:
            retryable("ID_ORDER_MISMATCH")
        else:
            retryable("ID_SET_MISMATCH")

    # 5. No posting IDs unknown to Backend
    allowed_ids = set(expected_ids)
    for pid in draft_referenced_ids:
        if pid not in allowed_ids:
            retryable(f"UNKNOWN_POSTING_ID:{pid}")

    # 6. URLs in content must be from allowed sourceUrls
    allowed_urls = {p.source_url for p in selected if p.source_url}
    for m in re.finditer(r"\(https?://[^)\s]+\)", draft_content):
        url = m.group()[1:-1]
        if url not in allowed_urls:
            retryable(f"UNKNOWN_URL:{url[:80]}")

    # 7. matchedKeywords scope — keywords must be in matchEvidence or posting skills
    all_allowed_kw: set[str] = set()
    for p in selected:
        all_allowed_kw.update(s.lower() for s in p.skills)
        ev = p.match_evidence
        all_allowed_kw.update(s.lower() for s in ev.matched_skills)
        all_allowed_kw.update(s.lower() for s in ev.matched_roles)
        all_allowed_kw.update(s.lower() for s in ev.matched_companies)
    for enrich in enrichments.values():
        for kw in enrich.matched_keywords:
            if len(kw) > 1 and kw.lower() not in all_allowed_kw:
                retryable(f"KEYWORD_OUT_OF_SCOPE:{kw[:30]}")
                break

    # 8. Hallucination patterns → RETRYABLE
    for phrase in _PATTERNS_RETRYABLE:
        if phrase in draft_content:
            retryable(f"HALLUCINATION:{phrase}")

    # 9. Investment advice → FALLBACK_REQUIRED
    for phrase in _PATTERNS_FALLBACK_REQUIRED:
        if phrase in draft_content:
            fallback_required(f"INVESTMENT_ADVICE:{phrase}")

    # 10. Content length
    if len(draft_content) > _MAX_CONTENT_LENGTH:
        retryable(f"CONTENT_TOO_LONG:{len(draft_content)}")

    if errors:
        logger.warning(
            "[validate_report] userId=%s status=%s errors=%s rewrite_count=%d",
            req.user_id, status, errors, state.get("rewrite_count", 0),
        )
    else:
        logger.info("[validate_report] userId=%s status=pass", req.user_id)

    return {"validation_status": status, "validation_errors": errors}


# ---------------------------------------------------------------------------
# Node: rewrite_report  (async — LLM targeted rewrite, max 1)
# ---------------------------------------------------------------------------


async def rewrite_report_node(state: UserBriefingState) -> dict:
    """Ask LLM to fix only the flagged validation errors.

    Increments rewrite_count (bounded at 1 by graph routing).
    On LLM exception: sets llm_error_category → graph routes to fallback.
    """
    req = state["request"]
    selected = state["selected"]
    enrichments = state.get("enrichments", {})
    existing_usage = state.get("token_usage", TokenUsage())
    rewrite_count = state.get("rewrite_count", 0)

    try:
        today = date_cls.fromisoformat(req.briefing_date)
    except ValueError:
        today = date_cls.today()

    enriched_inputs = _build_enriched_inputs(
        selected, enrichments, req.preference, today
    )
    expected_ids = [str(p.id) for p in selected]

    try:
        raw, usage = await llm_client.call_json(
            rewrite_prompts.get_system_prompt(),
            rewrite_prompts.build_user_prompt(
                draft_content=state.get("draft_content", ""),
                draft_summary=state.get("draft_summary", ""),
                validation_errors=state.get("validation_errors", []),
                expected_posting_ids=expected_ids,
                briefing_date=req.briefing_date,
                preference=req.preference,
                enriched_inputs=enriched_inputs,
            ),
        )
        result = rewrite_prompts.parse_response(raw)
        new_count = rewrite_count + 1
        new_usage = _add_usage(existing_usage, usage)
        logger.info(
            "[rewrite_report] userId=%s rewrite #%d ok inputTokens=%d",
            req.user_id, new_count, new_usage.input_tokens,
        )
        return {
            "draft_summary": result.overall_summary,
            "draft_content": result.markdown_content,
            "draft_referenced_ids": result.referenced_posting_ids,
            "summary": result.overall_summary,
            "content": result.markdown_content,
            "rewrite_count": new_count,
            "token_usage": new_usage,
            "llm_error_category": "",
            "validation_status": "pending",
            "validation_errors": [],
        }
    except (LLMUnavailableError, LLMClientError) as exc:
        logger.warning(
            "[rewrite_report] userId=%s LLM rewrite failed (%s: %s)",
            req.user_id, type(exc).__name__, exc,
        )
        return {
            "rewrite_count": rewrite_count + 1,
            "token_usage": existing_usage,
            "llm_error_category": "rewrite_failed",
            "fallback_reason": f"rewrite_llm_error: {type(exc).__name__}",
        }
    except Exception as exc:
        logger.warning(
            "[rewrite_report] userId=%s rewrite response invalid (%s: %s)",
            req.user_id, type(exc).__name__, exc,
        )
        return {
            "rewrite_count": rewrite_count + 1,
            "token_usage": existing_usage,
            "llm_error_category": "rewrite_failed",
            "fallback_reason": f"rewrite_parse_error: {type(exc).__name__}",
        }


# ---------------------------------------------------------------------------
# Node: deterministic_fallback  (deterministic — no LLM)
# ---------------------------------------------------------------------------


def deterministic_fallback_node(state: UserBriefingState) -> dict:
    """Build a guaranteed-valid report from Backend data without calling LLM.

    Uses matchEvidence from each posting for matching reasons. Preserves Backend
    rank order. Never exposes internal error details in user-facing content.
    """
    req = state["request"]
    selected = state.get("selected", [])
    enrichments = state.get("enrichments", {})
    existing_usage = state.get("token_usage", TokenUsage())
    fallback_reason = state.get("fallback_reason", "unknown")
    llm_error_category = state.get("llm_error_category", "")
    validation_errors = state.get("validation_errors", [])

    logger.warning(
        "[deterministic_fallback] userId=%s briefingDate=%s "
        "error_category=%s validation_errors=%s rewrite_count=%d fallback_reason=%s",
        req.user_id, req.briefing_date,
        llm_error_category, validation_errors, state.get("rewrite_count", 0),
        fallback_reason,
    )

    try:
        today = date_cls.fromisoformat(req.briefing_date)
    except ValueError:
        today = date_cls.today()

    enriched_inputs = _build_enriched_inputs(
        selected, enrichments, req.preference, today
    )
    articles = _build_articles_from_inputs(enriched_inputs, req.briefing_date)
    det = _build_deterministic_report(
        enriched_inputs, req.preference, req.briefing_date, articles, existing_usage
    )
    return {
        **det,
        "draft_summary": det["summary"],
        "draft_content": det["content"],
        "draft_referenced_ids": [str(p.id) for p in selected],
        "used_fallback": True,
        "fallback_reason": (
            fallback_reason or llm_error_category or "deterministic_fallback"
        ),
    }


# ---------------------------------------------------------------------------
# Graph routing functions
# ---------------------------------------------------------------------------


def _route_pool(state: UserBriefingState) -> str:
    return "empty" if not state.get("selected") else "proceed"


def _route_after_enrich(state: UserBriefingState) -> str:
    if state.get("llm_error_category") == "enrichment_failed":
        return "fallback"
    return "proceed"


def _route_after_synthesize(state: UserBriefingState) -> str:
    if state.get("llm_error_category") == "synthesis_failed":
        return "fallback"
    return "proceed"


def _route_after_validate(state: UserBriefingState) -> str:
    status = state.get("validation_status", "fallback_required")
    if status == "pass":
        return "pass"
    rewrite_count = state.get("rewrite_count", 0)
    if status == "retryable" and rewrite_count == 0:
        return "rewrite"
    return "fallback"


def _route_after_rewrite(state: UserBriefingState) -> str:
    if state.get("llm_error_category") == "rewrite_failed":
        return "fallback"
    return "validate"


# ---------------------------------------------------------------------------
# Build and compile the LangGraph StateGraph
# ---------------------------------------------------------------------------

_builder = StateGraph(UserBriefingState)

_builder.add_node("check_pool", check_pool_node)
_builder.add_node("empty_state", empty_state_node)
_builder.add_node("enrich_postings", enrich_postings_node)
_builder.add_node("synthesize_report", synthesize_report_node)
_builder.add_node("validate_report", validate_report_node)
_builder.add_node("rewrite_report", rewrite_report_node)
_builder.add_node("deterministic_fallback", deterministic_fallback_node)

_builder.set_entry_point("check_pool")
_builder.add_conditional_edges(
    "check_pool",
    _route_pool,
    {"empty": "empty_state", "proceed": "enrich_postings"},
)
_builder.add_edge("empty_state", END)
_builder.add_conditional_edges(
    "enrich_postings",
    _route_after_enrich,
    {"fallback": "deterministic_fallback", "proceed": "synthesize_report"},
)
_builder.add_conditional_edges(
    "synthesize_report",
    _route_after_synthesize,
    {"fallback": "deterministic_fallback", "proceed": "validate_report"},
)
_builder.add_conditional_edges(
    "validate_report",
    _route_after_validate,
    {"pass": END, "rewrite": "rewrite_report", "fallback": "deterministic_fallback"},
)
_builder.add_conditional_edges(
    "rewrite_report",
    _route_after_rewrite,
    {"fallback": "deterministic_fallback", "validate": "validate_report"},
)
_builder.add_edge("deterministic_fallback", END)

_graph = _builder.compile()


# ---------------------------------------------------------------------------
# Pipeline entry point
# ---------------------------------------------------------------------------


async def run(request: BriefingGenerateRequest) -> BriefingGenerateResponse:
    """Invoke the LangGraph workflow and return the briefing response."""
    initial_state: UserBriefingState = {
        "request": request,
        "selected": [],
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

    result = await _graph.ainvoke(initial_state)

    selected = result.get("selected", [])
    used_fallback = result.get("used_fallback", False)
    rewrite_count = result.get("rewrite_count", 0)
    raw_fallback_reason: str = result.get("fallback_reason") or ""

    # Determine generation mode
    if not selected:
        generation_mode: GenerationMode = "EMPTY"
    elif used_fallback:
        generation_mode = "FALLBACK"
    elif not llm_client.enabled:
        # LLM disabled → synthesize_report built deterministic path (no LLM used)
        generation_mode = "FALLBACK"
    elif rewrite_count > 0:
        generation_mode = "REWRITTEN"
    else:
        generation_mode = "LLM"

    # Normalize fallback_reason: map internal details to canonical categories
    fallback_reason: str | None = None
    if generation_mode in ("FALLBACK", "EMPTY"):
        if not llm_client.enabled and not raw_fallback_reason:
            fallback_reason = "llm_disabled"
        elif raw_fallback_reason:
            if "enrichment" in raw_fallback_reason:
                fallback_reason = "enrichment_failed"
            elif "synthesis" in raw_fallback_reason:
                fallback_reason = "synthesis_failed"
            elif "rewrite" in raw_fallback_reason:
                fallback_reason = "rewrite_failed"
            elif (
                "validation_status" in raw_fallback_reason
                or "FALLBACK_REQUIRED" in raw_fallback_reason
            ):
                fallback_reason = "validation_fallback_required"
            elif raw_fallback_reason in ("", "unknown", "deterministic_fallback"):
                fallback_reason = "validation_retry_exhausted"
            else:
                fallback_reason = raw_fallback_reason.split(":")[0].strip()[:60]
        else:
            fallback_reason = "validation_retry_exhausted"

    token = result.get("token_usage", TokenUsage())
    return BriefingGenerateResponse(
        title=result.get("title", ""),
        summary=result.get("summary", ""),
        content=result.get("content", ""),
        articles=result.get("articles", []),
        token_usage=TokenUsage(
            input_tokens=token.input_tokens,
            output_tokens=token.output_tokens,
        ),
        generation_mode=generation_mode,
        used_fallback=(generation_mode in ("FALLBACK", "EMPTY")),
        fallback_reason=fallback_reason,
        rewrite_count=rewrite_count,
    )


# ---------------------------------------------------------------------------
# Deterministic helpers (shared by synthesize_report and deterministic_fallback)
# ---------------------------------------------------------------------------


def _build_enriched_inputs(
    selected: list[CandidateJobPosting],
    enrichments: dict[str, JobPostingEnrichment],
    pref: JobPostingPreference,
    today: date_cls,
) -> list[EnrichedArticleInput]:
    result: list[EnrichedArticleInput] = []
    for i, posting in enumerate(selected):
        post_id = str(posting.id or "")
        enrichment = enrichments.get(post_id)

        if enrichment:
            summary = enrichment.summary
            matching_reason = enrichment.matching_reason or _build_why_it_matters(
                posting, pref, today
            )
            matched_keywords = enrichment.matched_keywords
        else:
            summary = _deterministic_summary(posting)
            matching_reason = _build_why_it_matters(posting, pref, today)
            ev = posting.match_evidence
            matched_keywords = list(ev.matched_skills) if ev.matched_skills else [
                s for s in pref.skills
                if s.lower() in [sk.lower() for sk in posting.skills]
            ]

        days_until = (
            _safe_days_until(posting.deadline, today) if posting.deadline else None
        )

        company = posting.company_name or ""
        raw_title = posting.title or "채용 공고"
        display_title = f"{company} — {raw_title}" if company else raw_title
        display_order = posting.rank if posting.rank > 0 else i + 1

        result.append(
            EnrichedArticleInput(
                id=post_id,
                title=display_title,
                company_name=company,
                url=posting.source_url,
                source=posting.source,
                summary=summary,
                matching_reason=matching_reason,
                matched_keywords=matched_keywords,
                deadline=posting.deadline,
                days_until_deadline=days_until,
                display_order=display_order,
                is_new=posting.is_new,
                is_urgent=posting.is_urgent,
            )
        )
    return result


def _build_articles_from_inputs(
    inputs: list[EnrichedArticleInput],
    briefing_date: str,
) -> list[JobArticle]:
    published_at = f"{briefing_date}T09:00:00"
    return [
        JobArticle(
            title=inp["title"],
            source=inp.get("source"),
            url=inp.get("url"),
            summary=inp["summary"],
            why_it_matters=inp["matching_reason"],
            company_name=inp["company_name"],
            published_at=published_at,
        )
        for inp in inputs
    ]


def _deterministic_summary(posting: CandidateJobPosting) -> str:
    return f"{posting.company_name} — {posting.title} 채용"


def _build_why_it_matters(
    posting: CandidateJobPosting,
    pref: JobPostingPreference,
    today: date_cls | None = None,
) -> str:
    reasons: list[str] = []
    ev = posting.match_evidence

    if ev.matched_companies:
        reasons.append(f"관심 기업 {', '.join(ev.matched_companies[:2])}")
    if ev.matched_roles:
        reasons.append(f"{', '.join(ev.matched_roles[:2])} 역할 일치")
    if ev.matched_skills:
        reasons.append(f"{', '.join(ev.matched_skills[:3])} 스킬 매칭")
    if ev.matched_locations:
        reasons.append(f"{', '.join(ev.matched_locations[:2])} 근무")
    if ev.matched_experience_levels:
        reasons.append(f"{', '.join(ev.matched_experience_levels[:1])} 경력 조건 부합")
    if ev.matched_employment_types:
        reasons.append(f"{', '.join(ev.matched_employment_types[:1])}")

    if not reasons:
        for co in pref.companies:
            if co == posting.company_name:
                reasons.append(f"관심 기업 {co}")
                break
        title_lower = (posting.title or "").lower()
        for role in pref.roles:
            if role.lower() in title_lower:
                reasons.append(f"{role} 역할 일치")
                break
        posting_skills_lower = [s.lower() for s in posting.skills]
        matched = [s for s in pref.skills if s.lower() in posting_skills_lower]
        if matched:
            reasons.append(f"{', '.join(matched[:3])} 스킬 매칭")
        for loc in pref.locations:
            if loc in (posting.location or ""):
                reasons.append(f"{loc} 근무")
                break
        for exp in pref.experience_levels:
            if exp == posting.experience_level:
                reasons.append(f"{exp} 경력 조건 부합")
                break

    if posting.is_urgent and posting.deadline and today:
        try:
            days = (date_cls.fromisoformat(posting.deadline) - today).days
            if 0 <= days <= 3:
                reasons.append(f"마감 {days}일 전 — 긴급")
            elif 0 < days <= 7:
                reasons.append(f"마감 {days}일 이내")
        except ValueError:
            pass

    if reasons:
        return " · ".join(reasons)
    company = posting.company_name or ""
    title = posting.title or ""
    return f"{company} {title}".strip() or "관심 조건에 기반한 추천"


def _build_empty_report(briefing_date: str) -> dict:
    content = "\n".join([
        "# 오늘의 채용 브리핑",
        "",
        "## 오늘의 핵심 요약",
        "",
        f"- {briefing_date} 기준, 선호도에 맞는 채용 공고가 오늘은 없습니다.",
        "- 선호 조건을 확인하고 내일 다시 브리핑을 살펴보세요.",
        "- 조건을 조금 넓히면 더 많은 공고를 만날 수 있습니다.",
        "",
        "---",
        "",
        "## 🏆 추천 공고 TOP 0",
        "",
        "오늘 추천할 공고가 없습니다.",
        "",
        "---",
        "",
        "## ⏰ 신규/마감 임박 공고",
        "",
        "선별된 공고 중 7일 이내 마감 임박 공고가 없습니다.",
        "",
        "---",
        "",
        "## 💡 오늘의 지원 추천 액션",
        "",
        "1. 선호 조건(역할, 기업, 스킬, 위치)을 다시 확인해 보세요.",
        "2. 내일 다시 브리핑을 확인해 주세요.",
        "",
        "---",
        "",
        "## 🔑 오늘의 키워드",
        "",
        "해당 사항 없습니다.",
        "",
        "---",
        "",
        "## ✏️ 한 줄 정리",
        "",
        "내일은 더 많은 공고가 준비되어 있을 거예요.",
    ])
    return {
        "articles": [],
        "title": f"오늘의 채용 브리핑 ({briefing_date})",
        "summary": "오늘은 설정하신 조건에 맞는 채용 공고가 없습니다.",
        "content": content,
        "token_usage": TokenUsage(),
        "draft_summary": "",
        "draft_content": content,
        "draft_referenced_ids": [],
    }


def _build_deterministic_report(
    enriched_inputs: list[EnrichedArticleInput],
    pref: JobPostingPreference,
    briefing_date: str,
    articles: list[JobArticle],
    token_usage: TokenUsage,
) -> dict:
    n = len(enriched_inputs)
    primary_role = pref.roles[0] if pref.roles else "개발자"
    title = f"오늘의 채용 브리핑 — {primary_role} ({briefing_date})"

    unique_companies = list(dict.fromkeys(
        inp["company_name"] for inp in enriched_inputs if inp["company_name"]
    ))
    co_display = " · ".join(unique_companies[:3])
    extra = len(unique_companies) - 3
    extra_str = f" 외 {extra}개 기업" if extra > 0 else ""
    summary = (
        f"{briefing_date} 기준, {co_display}{extra_str}에서 "
        f"추천 공고 {n}건을 선별했습니다."
    )

    bullet1 = (
        f"- {briefing_date} 기준, {co_display}{extra_str}"
        f"에서 {n}건의 추천 공고를 선별했습니다."
    )
    match_parts: list[str] = []
    if pref.roles:
        match_parts.append(f"{pref.roles[0]} 역할")
    if pref.skills:
        match_parts.append(f"{', '.join(pref.skills[:2])} 스킬")
    if match_parts:
        cond = " 및 ".join(match_parts)
        bullet2 = f"- 주요 매칭 조건: {cond}이 선별 기준으로 반영되었습니다."
    else:
        bullet2 = f"- 선호도 설정에 따라 {n}건의 공고가 추천되었습니다."
    top_company = unique_companies[0] if unique_companies else "관심 기업"
    bullet3 = f"- {top_company}의 공고를 오늘 확인해 지원 타이밍을 놓치지 마세요."

    lines: list[str] = [
        "# 오늘의 채용 브리핑",
        "",
        "## 오늘의 핵심 요약",
        "",
        bullet1,
        bullet2,
        bullet3,
        "",
        "---",
        "",
        f"## 🏆 추천 공고 TOP {n}",
        "",
    ]

    for i, inp in enumerate(enriched_inputs, 1):
        source_label = inp.get("source") or "채용 사이트"
        url = inp.get("url") or ""
        link = f"[공고 보기]({url})" if url else source_label

        badge = ""
        badges: list[str] = []
        if inp.get("is_new"):
            badges.append("🆕 NEW")
        if inp.get("is_urgent"):
            badges.append("⏰ URGENT")
        if badges:
            badge = " " + " ".join(badges)

        lines += [
            f"### {i}. {inp['title']}{badge}",
            f"- **기업**: {inp['company_name']}",
            f"- **출처**: {link}",
            f"- **추천 이유**: {inp['matching_reason']}",
        ]
        if inp.get("deadline"):
            lines.append(f"- **마감일**: {inp['deadline']}")
        lines.append("")

    deadline_near = [
        inp for inp in enriched_inputs
        if inp.get("days_until_deadline") is not None
        and 0 <= inp["days_until_deadline"] <= 7
    ]
    lines += ["---", "", "## ⏰ 신규/마감 임박 공고", ""]
    if deadline_near:
        for inp in deadline_near:
            lines.append(
                f"- **{inp['title']}** ({inp['company_name']})"
                f" — 마감: {inp.get('deadline', '')}"
            )
    else:
        lines.append("선별된 공고 중 7일 이내 마감 임박 공고가 없습니다.")
    lines.append("")

    action_lines = ["---", "", "## 💡 오늘의 지원 추천 액션", ""]
    action_num = 1
    action_lines.append(
        f"{action_num}. {top_company}의 공고를 확인하고 즉시 지원해 보세요."
    )
    action_num += 1
    if pref.skills:
        skills_str = ", ".join(pref.skills[:2])
        action_lines.append(
            f"{action_num}. 이력서에 **{skills_str}**"
            " 관련 프로젝트 경험을 구체적으로 작성하세요."
        )
        action_num += 1
    if deadline_near:
        action_lines.append(
            f"{action_num}. 마감 임박 공고({len(deadline_near)}건)를"
            " 오늘 안에 지원 검토하세요."
        )
        action_num += 1
    if pref.roles:
        action_lines.append(
            f"{action_num}. {pref.roles[0]}"
            " 관련 GitHub 또는 기술 포트폴리오를 최신화하세요."
        )
    action_lines.append("")
    lines += action_lines

    all_keywords: list[str] = []
    for inp in enriched_inputs:
        for kw in inp.get("matched_keywords", []):
            if kw not in all_keywords:
                all_keywords.append(kw)
    if not all_keywords:
        seen: set[str] = set()
        for kw in list(pref.skills[:5]) + list(pref.roles[:2]):
            if kw not in seen:
                all_keywords.append(kw)
                seen.add(kw)

    lines += [
        "---",
        "",
        "## 🔑 오늘의 키워드",
        "",
        " · ".join(all_keywords[:10]) if all_keywords else "채용 공고",
        "",
        "---",
        "",
        "## ✏️ 한 줄 정리",
        "",
        (
            f"{primary_role} 포지션 중심으로 {co_display}{extra_str}에서 "
            f"{n}건의 공고가 확인되었습니다. 지금 바로 지원해 보세요!"
        ),
    ]

    return {
        "articles": articles,
        "title": title,
        "summary": summary,
        "content": "\n".join(lines),
        "token_usage": token_usage,
    }


def _safe_days_until(deadline: str, today: date_cls) -> int | None:
    try:
        return (date_cls.fromisoformat(deadline) - today).days
    except ValueError:
        return None


def _add_usage(existing: TokenUsage, new: LLMTokenUsage) -> TokenUsage:
    return TokenUsage(
        input_tokens=existing.input_tokens + new.input_tokens,
        output_tokens=existing.output_tokens + new.output_tokens,
    )
