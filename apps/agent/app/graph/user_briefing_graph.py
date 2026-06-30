"""Deterministic user briefing workflow (no LLM).

Pipeline:
  load_request_node
    → filter_job_postings_node   (remove past-deadline / invalid postings)
    → rank_job_postings_node     (preScore + preference-match re-score)
    → select_top_items_node      (top N)
    → write_markdown_report_node (build title, summary, Markdown content, articles)
    → quality_check_node         (no-op; hook for future validation)
"""

from datetime import date as date_cls
from typing import TypedDict

from app.schemas.briefing import (
    BriefingGenerateRequest,
    BriefingGenerateResponse,
    CandidateJobPosting,
    JobArticle,
    JobPostingPreference,
    TokenUsage,
)

_TOP_N = 10


class UserBriefingState(TypedDict):
    request: BriefingGenerateRequest
    filtered: list[CandidateJobPosting]
    ranked: list[CandidateJobPosting]
    selected: list[CandidateJobPosting]
    articles: list[JobArticle]
    title: str
    summary: str
    content: str


def load_request_node(state: UserBriefingState) -> dict:
    return {}


def filter_job_postings_node(state: UserBriefingState) -> dict:
    req = state["request"]
    postings = req.candidate_pool.job_postings

    try:
        today = date_cls.fromisoformat(req.briefing_date)
    except ValueError:
        today = date_cls.today()

    filtered = []
    for p in postings:
        if not p.title or not p.source_url:
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


def _build_why_it_matters(
    posting: CandidateJobPosting, pref: JobPostingPreference
) -> str:
    reasons = []

    for co in pref.companies:
        if co == posting.company_name:
            reasons.append(f"관심 기업({co})")
            break

    title_lower = (posting.title or "").lower()
    for role in pref.roles:
        if role.lower() in title_lower:
            reasons.append(f"{role} 포지션 매칭")
            break

    posting_skills_lower = [s.lower() for s in posting.skills]
    matched_skills = [s for s in pref.skills if s.lower() in posting_skills_lower]
    if matched_skills:
        reasons.append(f"스킬 매칭: {', '.join(matched_skills[:3])}")

    return " · ".join(reasons) if reasons else "선호도 기반 추천 공고"


def write_markdown_report_node(state: UserBriefingState) -> dict:
    req = state["request"]
    pref = req.preference
    selected = state["selected"]
    briefing_date = req.briefing_date

    if not selected:
        title = f"오늘의 채용 브리핑 ({briefing_date})"
        summary = "오늘은 설정하신 조건에 맞는 채용 공고가 없습니다."
        content = "\n".join(
            [
                "## 오늘의 채용 브리핑",
                "",
                (
                    f"{briefing_date} 기준, 설정하신 선호도에 맞는 "
                    "채용 공고가 오늘은 없습니다."
                ),
                "",
                "내일 다시 확인해 주세요.",
            ]
        )
        return {
            "articles": [],
            "title": title,
            "summary": summary,
            "content": content,
        }

    try:
        today = date_cls.fromisoformat(briefing_date)
    except ValueError:
        today = date_cls.today()

    articles = [
        JobArticle(
            title=p.title or "채용 공고",
            source=p.source,
            url=p.source_url,
            summary=(
                f"{p.company_name} — "
                f"{p.position or p.title} 채용"
            ),
            why_it_matters=_build_why_it_matters(p, pref),
            company_name=p.company_name,
            published_at=p.posted_at,
        )
        for p in selected
    ]

    primary_role = pref.roles[0] if pref.roles else "개발자"
    title = f"오늘의 채용 브리핑 — {primary_role} ({briefing_date})"

    unique_companies = list(
        dict.fromkeys(p.company_name for p in selected if p.company_name)
    )
    co_display = "·".join(unique_companies[:3])
    extra = len(unique_companies) - 3
    extra_str = f" 외 {extra}개 기업" if extra > 0 else ""
    total = len(selected)
    summary = (
        f"{briefing_date} 기준, {co_display}{extra_str}에서 "
        f"추천 공고 {total}건을 선별했습니다."
    )

    deadline_near = [
        p
        for p in selected
        if p.deadline
        and _safe_days_until(p.deadline, today) is not None
        and 0 <= _safe_days_until(p.deadline, today) <= 7  # type: ignore[operator]
    ]

    lines = [
        "## 오늘의 핵심 요약",
        "",
        (
            f"{briefing_date} 기준, 선호 조건에 맞는 추천 공고 "
            f"**{total}건**이 선별되었습니다."
        ),
        "",
        "---",
        "",
        f"## 🏆 추천 공고 TOP {total}",
        "",
    ]

    for i, p in enumerate(selected, 1):
        why = _build_why_it_matters(p, pref)
        source_label = p.source or "채용 사이트"
        lines += [
            f"### {i}. {p.title}",
            f"- **기업**: {p.company_name}",
            f"- **출처**: [{source_label}]({p.source_url})",
            f"- **포지션**: {p.position or p.title}",
            f"- **추천 이유**: {why}",
            "",
        ]

    if deadline_near:
        lines += [
            "---",
            "",
            "## ⏰ 마감 임박 공고 (7일 이내)",
            "",
        ]
        for p in deadline_near:
            lines.append(
                f"- **{p.title}** ({p.company_name}) — 마감: {p.deadline}"
            )
        lines.append("")

    action_company = unique_companies[0] if unique_companies else "관심 기업"
    action_skill = pref.skills[0] if pref.skills else "핵심 기술"
    lines += [
        "---",
        "",
        "## 💡 오늘의 지원 추천 액션",
        "",
        f"1. {action_company}의 공고를 확인하고 즉시 지원해 보세요.",
        f"2. 이력서에 **{action_skill}** 프로젝트 경험을 업데이트하세요.",
        "3. 마감 임박 공고를 오늘 내로 확인하세요.",
        f"4. {primary_role} 관련 기술 블로그나 GitHub를 최신화하세요.",
        "",
    ]

    all_skills: list[str] = []
    for p in selected:
        for s in p.skills:
            if s not in all_skills:
                all_skills.append(s)
    keywords = all_skills[:10] if all_skills else pref.skills[:10]

    lines += [
        "---",
        "",
        "## 🔑 오늘의 키워드",
        "",
        " · ".join(keywords) if keywords else "채용 공고",
        "",
        "---",
        "",
        "## ✏️ 한 줄 정리",
        "",
        (
            f"{primary_role} 포지션을 중심으로 {total}건의 공고가 확인되었습니다. "
            "지금 바로 지원해 보세요!"
        ),
    ]

    return {
        "articles": articles,
        "title": title,
        "summary": summary,
        "content": "\n".join(lines),
    }


def _safe_days_until(deadline: str, today: date_cls) -> int | None:
    try:
        return (date_cls.fromisoformat(deadline) - today).days
    except ValueError:
        return None


def quality_check_node(state: UserBriefingState) -> dict:
    return {}


def run(request: BriefingGenerateRequest) -> BriefingGenerateResponse:
    state: UserBriefingState = {
        "request": request,
        "filtered": [],
        "ranked": [],
        "selected": [],
        "articles": [],
        "title": "",
        "summary": "",
        "content": "",
    }

    state.update(load_request_node(state))
    state.update(filter_job_postings_node(state))
    state.update(rank_job_postings_node(state))
    state.update(select_top_items_node(state))
    state.update(write_markdown_report_node(state))
    state.update(quality_check_node(state))

    return BriefingGenerateResponse(
        title=state["title"],
        summary=state["summary"],
        content=state["content"],
        articles=state["articles"],
        token_usage=TokenUsage(input_tokens=0, output_tokens=0),
    )
