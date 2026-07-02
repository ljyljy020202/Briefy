"""LLM-enhanced user briefing workflow with deterministic fallback.

Pipeline:
  filter_job_postings_node   (deterministic: remove invalid/expired)
    → rank_job_postings_node (deterministic: preScore + preference scoring)
    → select_top_items_node  (deterministic: top _TOP_N candidates)
    → enrich_selected_node   (async: LLM batch summary + matching reason)
    → synthesize_report_node (async: LLM Markdown report + summary line)
    → quality_check_node     (deterministic: log-only guardrail)

Both LLM nodes fall back to deterministic equivalents on any failure or when
OPENAI_API_KEY is not configured. The pipeline never raises HTTP 500 due to
LLM error.
"""

import logging
from datetime import date as date_cls
from typing import TypedDict

from app.prompts import briefing_synthesis, job_enrichment
from app.prompts.briefing_synthesis import EnrichedArticleInput
from app.schemas.briefing import (
    BriefingGenerateRequest,
    BriefingGenerateResponse,
    CandidateJobPosting,
    JobArticle,
    JobPostingPreference,
    TokenUsage,
)
from app.schemas.llm import JobPostingEnrichment
from app.services.llm_client import (
    LLMClientError,
    LLMUnavailableError,
    llm_client,
)

logger = logging.getLogger(__name__)

_TOP_N = 7

_INVESTMENT_ADVICE_PATTERNS = [
    "매수",
    "매도",
    "추천 종목",
    "투자 추천",
    "수익률 보장",
]
_HALLUCINATION_PATTERNS = [
    "합격 가능성이 높",
    "합격 보장",
    "반드시 합격",
    "경쟁률이 낮",
]
_REQUIRED_SECTIONS = [
    "오늘의 핵심 요약",
    "추천 공고 TOP",
    "신규/마감 임박 공고",
    "오늘의 지원 추천 액션",
    "오늘의 키워드",
    "한 줄 정리",
]


class UserBriefingState(TypedDict):
    request: BriefingGenerateRequest
    filtered: list[CandidateJobPosting]
    ranked: list[CandidateJobPosting]
    selected: list[CandidateJobPosting]
    enrichments: dict[str, JobPostingEnrichment]
    articles: list[JobArticle]
    title: str
    summary: str
    content: str
    token_usage: TokenUsage


# ---------------------------------------------------------------------------
# Deterministic nodes
# ---------------------------------------------------------------------------


def filter_job_postings_node(state: UserBriefingState) -> dict:
    req = state["request"]

    if req.category != "JOB_POSTING":
        return {"filtered": []}

    try:
        today = date_cls.fromisoformat(req.briefing_date)
    except ValueError:
        today = date_cls.today()

    filtered = []
    for p in req.candidate_pool.job_postings:
        if not p.title or not p.company_name:
            continue
        if not p.source_url:
            continue
        if p.deadline:
            try:
                if date_cls.fromisoformat(p.deadline) < today:
                    continue
            except ValueError:
                pass
        filtered.append(p)

    return {"filtered": filtered}


def _agent_score(
    posting: CandidateJobPosting,
    pref: JobPostingPreference,
    today: date_cls,
) -> int:
    score = 0
    title_lower = (posting.title or "").lower()
    position_lower = (posting.position or "").lower()

    for role in pref.roles:
        if role.lower() in title_lower or role.lower() in position_lower:
            score += 30
            break

    for co in pref.companies:
        if co == posting.company_name:
            score += 15
            break

    skill_score = 0
    posting_skills_lower = [s.lower() for s in posting.skills]
    for skill in pref.skills:
        if skill.lower() in posting_skills_lower:
            skill_score += 5
            if skill_score >= 25:
                break
    score += skill_score

    for loc in pref.locations:
        if loc in (posting.location or ""):
            score += 10
            break

    for exp in pref.experience_levels:
        if exp == posting.experience_level:
            score += 10
            break

    for emp in pref.employment_types:
        if emp == posting.employment_type:
            score += 5
            break

    if posting.deadline:
        try:
            days = (date_cls.fromisoformat(posting.deadline) - today).days
            if 0 <= days <= 7:
                score += 10
        except ValueError:
            pass

    # Recency bonus: collected within the last 3 days
    if posting.collected_date:
        try:
            collected = date_cls.fromisoformat(posting.collected_date)
            if (today - collected).days <= 3:
                score += 5
        except ValueError:
            pass

    return score


def rank_job_postings_node(state: UserBriefingState) -> dict:
    req = state["request"]
    pref = req.preference

    try:
        today = date_cls.fromisoformat(req.briefing_date)
    except ValueError:
        today = date_cls.today()

    def total_score(posting: CandidateJobPosting) -> int:
        return posting.pre_score + _agent_score(posting, pref, today)

    ranked = sorted(state["filtered"], key=total_score, reverse=True)
    return {"ranked": ranked}


def select_top_items_node(state: UserBriefingState) -> dict:
    return {"selected": state["ranked"][:_TOP_N]}


# ---------------------------------------------------------------------------
# Async: LLM enrichment (per-posting summary + matching reason)
# ---------------------------------------------------------------------------


async def enrich_selected_node(state: UserBriefingState) -> dict:
    """Call LLM once to enrich all selected postings in a single batch.

    Falls back to an empty enrichment dict — deterministic summaries are
    applied in the synthesis node.
    """
    selected = state["selected"]
    if not selected or not llm_client.enabled:
        return {"enrichments": {}, "token_usage": TokenUsage()}

    try:
        raw, usage = await llm_client.call_json(
            job_enrichment.get_system_prompt(),
            job_enrichment.build_user_prompt(state["request"].preference, selected),
        )
        enrichment_list = job_enrichment.parse_response(raw)
        enrichments: dict[str, JobPostingEnrichment] = {
            e.id: e for e in enrichment_list
        }
        logger.info(
            "LLM enrichment: %d/%d postings enriched",
            len(enrichments),
            len(selected),
        )
        return {
            "enrichments": enrichments,
            "token_usage": TokenUsage(
                input_tokens=usage.input_tokens,
                output_tokens=usage.output_tokens,
            ),
        }
    except (LLMUnavailableError, LLMClientError) as exc:
        logger.warning(
            "LLM enrichment failed (%s); using deterministic fallback", exc
        )
        return {"enrichments": {}, "token_usage": TokenUsage()}


# ---------------------------------------------------------------------------
# Async: report synthesis (LLM Markdown or deterministic template)
# ---------------------------------------------------------------------------


async def synthesize_report_node(state: UserBriefingState) -> dict:
    """Write the final briefing report.

    Merges LLM enrichment results with deterministic fallbacks, then calls
    LLM to synthesise the Markdown report. Falls back to a template report
    if LLM synthesis is unavailable or fails.
    """
    req = state["request"]
    pref = req.preference
    selected = state["selected"]
    enrichments = state["enrichments"]
    enrich_token_usage = state["token_usage"]

    try:
        today = date_cls.fromisoformat(req.briefing_date)
    except ValueError:
        today = date_cls.today()

    if not selected:
        return _empty_state_report(req.briefing_date)

    # Merge LLM enrichment with deterministic fallback per posting.
    # Deterministic helpers fill in any posting not covered by LLM.
    enriched_inputs = _build_enriched_inputs(selected, enrichments, pref, today)
    articles = _build_articles_from_inputs(enriched_inputs, req.briefing_date)

    if llm_client.enabled:
        try:
            raw, usage = await llm_client.call_json(
                briefing_synthesis.get_system_prompt(),
                briefing_synthesis.build_user_prompt(
                    req.briefing_date, pref, enriched_inputs
                ),
            )
            result = briefing_synthesis.parse_response(raw)
            total_usage = TokenUsage(
                input_tokens=enrich_token_usage.input_tokens + usage.input_tokens,
                output_tokens=enrich_token_usage.output_tokens + usage.output_tokens,
            )
            primary_role = pref.roles[0] if pref.roles else "개발자"
            return {
                "articles": articles,
                "title": (
                    f"오늘의 채용 브리핑 — {primary_role} ({req.briefing_date})"
                ),
                "summary": result.overall_summary,
                "content": result.markdown_content,
                "token_usage": total_usage,
            }
        except (LLMUnavailableError, LLMClientError) as exc:
            logger.warning(
                "LLM synthesis failed (%s); using deterministic fallback", exc
            )
        except Exception as exc:
            # Pydantic ValidationError or other processing errors
            logger.warning(
                "LLM synthesis response invalid (%s: %s); "
                "using deterministic fallback",
                type(exc).__name__,
                exc,
            )

    return _build_deterministic_report(
        enriched_inputs, pref, req.briefing_date, articles, enrich_token_usage
    )


# ---------------------------------------------------------------------------
# Quality check (log-only guardrail, never crashes the pipeline)
# ---------------------------------------------------------------------------


def quality_check_node(state: UserBriefingState) -> dict:
    content = state.get("content", "")
    articles = state.get("articles", [])
    selected = state.get("selected", [])

    if "# 오늘의 채용 브리핑" not in content:
        logger.warning(
            "[quality_check] missing top-level heading: # 오늘의 채용 브리핑"
        )

    for section in _REQUIRED_SECTIONS:
        if section not in content:
            logger.warning(
                "[quality_check] required section missing: %s", section
            )

    for phrase in _INVESTMENT_ADVICE_PATTERNS:
        if phrase in content:
            logger.warning(
                "[quality_check] investment advice phrase detected: %r", phrase
            )

    for phrase in _HALLUCINATION_PATTERNS:
        if phrase in content:
            logger.warning(
                "[quality_check] hallucination phrase detected: %r", phrase
            )

    for i, article in enumerate(articles):
        if not article.summary:
            logger.warning("[quality_check] article[%d] has no summary", i)
        if not article.why_it_matters:
            logger.warning(
                "[quality_check] article[%d] has no whyItMatters", i
            )

    if selected and len(articles) != len(selected):
        logger.warning(
            "[quality_check] article count (%d) != selected count (%d)",
            len(articles),
            len(selected),
        )

    if len(content) > 15000:
        logger.warning(
            "[quality_check] content very long: %d chars", len(content)
        )

    return {}


# ---------------------------------------------------------------------------
# Deterministic helpers
# ---------------------------------------------------------------------------


def _build_enriched_inputs(
    selected: list[CandidateJobPosting],
    enrichments: dict[str, JobPostingEnrichment],
    pref: JobPostingPreference,
    today: date_cls,
) -> list[EnrichedArticleInput]:
    result = []
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
            posting_skills_lower = [s.lower() for s in posting.skills]
            matched_keywords = [
                s for s in pref.skills
                if s.lower() in posting_skills_lower
            ]

        days_until = (
            _safe_days_until(posting.deadline, today)
            if posting.deadline
            else None
        )

        result.append(
            EnrichedArticleInput(
                id=post_id,
                title=posting.title or "채용 공고",
                company_name=posting.company_name or "",
                url=posting.source_url,
                source=posting.source,
                summary=summary,
                matching_reason=matching_reason,
                matched_keywords=matched_keywords,
                deadline=posting.deadline,
                days_until_deadline=days_until,
                display_order=i + 1,
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
    return f"{posting.company_name} — {posting.position or posting.title} 채용"


def _build_why_it_matters(
    posting: CandidateJobPosting,
    pref: JobPostingPreference,
    today: date_cls | None = None,
) -> str:
    """Build a concrete matching reason from actual preference-posting overlap."""
    reasons: list[str] = []

    # Company match
    for co in pref.companies:
        if co == posting.company_name:
            reasons.append(f"관심 기업 {co}")
            break

    # Role match — check title and position
    title_lower = (posting.title or "").lower()
    position_lower = (posting.position or "").lower()
    for role in pref.roles:
        if role.lower() in title_lower or role.lower() in position_lower:
            reasons.append(f"{role} 역할 일치")
            break

    # Skill match
    posting_skills_lower = [s.lower() for s in posting.skills]
    matched_skills = [s for s in pref.skills if s.lower() in posting_skills_lower]
    if matched_skills:
        reasons.append(f"{', '.join(matched_skills[:3])} 스킬 매칭")

    # Location match
    for loc in pref.locations:
        if loc in (posting.location or ""):
            reasons.append(f"{loc} 근무")
            break

    # Experience level match
    for exp in pref.experience_levels:
        if exp == posting.experience_level:
            reasons.append(f"{exp} 경력 조건 부합")
            break

    # Employment type match
    for emp in pref.employment_types:
        if emp == posting.employment_type:
            reasons.append(f"{emp}")
            break

    # Deadline urgency
    if posting.deadline and today:
        try:
            days = (date_cls.fromisoformat(posting.deadline) - today).days
            if 0 <= days <= 3:
                reasons.append(f"마감 {days}일 전 — 긴급")
            elif 0 <= days <= 7:
                reasons.append(f"마감 {days}일 이내")
        except ValueError:
            pass

    if reasons:
        return " · ".join(reasons)
    # Non-vague fallback when nothing matched: at least name the company/title
    company = posting.company_name or ""
    title = posting.title or ""
    return f"{company} {title}".strip() or "관심 조건에 기반한 추천"


def _empty_state_report(briefing_date: str) -> dict:
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
        "## 추천 공고 TOP 0",
        "",
        "오늘 추천할 공고가 없습니다.",
        "",
        "---",
        "",
        "## 신규/마감 임박 공고",
        "",
        "선별된 공고 중 7일 이내 마감 임박 공고가 없습니다.",
        "",
        "---",
        "",
        "## 오늘의 지원 추천 액션",
        "",
        "1. 선호 조건(역할, 기업, 스킬, 위치)을 다시 확인해 보세요.",
        "2. 내일 다시 브리핑을 확인해 주세요.",
        "",
        "---",
        "",
        "## 오늘의 키워드",
        "",
        "해당 사항 없습니다.",
        "",
        "---",
        "",
        "## 한 줄 정리",
        "",
        "내일은 더 많은 공고가 준비되어 있을 거예요.",
    ])
    return {
        "articles": [],
        "title": f"오늘의 채용 브리핑 ({briefing_date})",
        "summary": "오늘은 설정하신 조건에 맞는 채용 공고가 없습니다.",
        "content": content,
        "token_usage": TokenUsage(),
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

    unique_companies = list(
        dict.fromkeys(
            inp["company_name"] for inp in enriched_inputs if inp["company_name"]
        )
    )
    co_display = " · ".join(unique_companies[:3])
    extra = len(unique_companies) - 3
    extra_str = f" 외 {extra}개 기업" if extra > 0 else ""
    summary = (
        f"{briefing_date} 기준, {co_display}{extra_str}에서 "
        f"추천 공고 {n}건을 선별했습니다."
    )

    # ── 핵심 요약: exactly 3 bullets ─────────────────────────────────────
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
        bullet2 = (
            f"- 주요 매칭 조건: {' 및 '.join(match_parts)}"
            "이 선별 기준으로 반영되었습니다."
        )
    else:
        bullet2 = f"- 선호도 설정에 따라 {n}건의 공고가 추천되었습니다."
    top_company = unique_companies[0] if unique_companies else "관심 기업"
    bullet3 = f"- {top_company}의 공고를 오늘 확인해 지원 타이밍을 놓치지 마세요."

    lines = [
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
        f"## 추천 공고 TOP {n}",
        "",
    ]

    for i, inp in enumerate(enriched_inputs, 1):
        source_label = inp.get("source") or "채용 사이트"
        url = inp.get("url") or ""
        link = f"[공고 보기]({url})" if url else source_label
        lines += [
            f"### {i}. {inp['title']}",
            f"- **기업**: {inp['company_name']}",
            f"- **출처**: {link}",
            f"- **추천 이유**: {inp['matching_reason']}",
        ]
        if inp.get("deadline"):
            lines.append(f"- **마감일**: {inp['deadline']}")
        lines.append("")

    # ── 신규/마감 임박 공고 ───────────────────────────────────────────────
    deadline_near = [
        inp for inp in enriched_inputs
        if inp.get("days_until_deadline") is not None
        and 0 <= inp["days_until_deadline"] <= 7
    ]

    lines += ["---", "", "## 신규/마감 임박 공고", ""]
    if deadline_near:
        for inp in deadline_near:
            lines.append(
                f"- **{inp['title']}** ({inp['company_name']}) — "
                f"마감: {inp.get('deadline', '')}"
            )
    else:
        lines.append("선별된 공고 중 7일 이내 마감 임박 공고가 없습니다.")
    lines.append("")

    # ── 오늘의 지원 추천 액션 ─────────────────────────────────────────────
    action_num = 1
    action_lines = ["---", "", "## 오늘의 지원 추천 액션", ""]
    action_lines.append(
        f"{action_num}. {top_company}의 공고를 "
        "확인하고 즉시 지원해 보세요."
    )
    action_num += 1
    if pref.skills:
        skills_str = ", ".join(pref.skills[:2])
        action_lines.append(
            f"{action_num}. 이력서에 **{skills_str}** "
            "관련 프로젝트 경험을 구체적으로 작성하세요."
        )
        action_num += 1
    if deadline_near:
        near_count = len(deadline_near)
        action_lines.append(
            f"{action_num}. 마감 임박 공고({near_count}건)를 "
            "오늘 안에 지원 검토하세요."
        )
        action_num += 1
    if pref.roles:
        action_lines.append(
            f"{action_num}. {pref.roles[0]} 관련 "
            "GitHub 또는 기술 포트폴리오를 최신화하세요."
        )
    action_lines.append("")
    lines += action_lines

    # ── 오늘의 키워드 ─────────────────────────────────────────────────────
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
        "## 오늘의 키워드",
        "",
        " · ".join(all_keywords[:10]) if all_keywords else "채용 공고",
        "",
        "---",
        "",
        "## 한 줄 정리",
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


# ---------------------------------------------------------------------------
# Pipeline entry point
# ---------------------------------------------------------------------------


async def run(request: BriefingGenerateRequest) -> BriefingGenerateResponse:
    state: UserBriefingState = {
        "request": request,
        "filtered": [],
        "ranked": [],
        "selected": [],
        "enrichments": {},
        "articles": [],
        "title": "",
        "summary": "",
        "content": "",
        "token_usage": TokenUsage(),
    }

    state.update(filter_job_postings_node(state))
    state.update(rank_job_postings_node(state))
    state.update(select_top_items_node(state))
    state.update(await enrich_selected_node(state))
    state.update(await synthesize_report_node(state))
    state.update(quality_check_node(state))

    token = state["token_usage"]
    return BriefingGenerateResponse(
        title=state["title"],
        summary=state["summary"],
        content=state["content"],
        articles=state["articles"],
        token_usage=TokenUsage(
            input_tokens=token.input_tokens,
            output_tokens=token.output_tokens,
        ),
    )
