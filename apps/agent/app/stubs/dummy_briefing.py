"""Parameterised dummy briefing generator — no LLM, no real crawling.

Produces a realistic-looking job briefing from the user's preference
fields so the Spring Boot backend can integrate against a stable schema
before real agent workflows are available.
"""

from app.schemas.briefing import (
    BriefingGenerateRequest,
    BriefingGenerateResponse,
    JobArticle,
    TokenUsage,
)

_SOURCES = ["원티드", "잡코리아", "LinkedIn", "로켓펀치", "사람인"]
_NEW_COUNT = 3
_TOTAL_COUNT = 5


def _build_postings(
    companies: list[str],
    roles: list[str],
    skills: list[str],
    exp_levels: list[str],
    emp_types: list[str],
    user_id: int,
    briefing_date: str,
) -> list[dict]:
    eff_companies = companies or ["테크 스타트업", "소프트웨어 회사"]
    eff_roles = roles or ["소프트웨어 엔지니어"]
    eff_skills = skills or ["개발"]
    eff_exp = exp_levels or ["경력 무관"]
    eff_emp = emp_types or ["정규직"]

    postings = []
    for i in range(_TOTAL_COUNT):
        company = eff_companies[i % len(eff_companies)]
        role = eff_roles[i % len(eff_roles)]
        skill_a = eff_skills[i % len(eff_skills)]
        skill_b = eff_skills[(i + 1) % len(eff_skills)]
        exp = eff_exp[i % len(eff_exp)]
        emp = eff_emp[i % len(eff_emp)]
        source = _SOURCES[i % len(_SOURCES)]
        url_id = (user_id * 100 + i) % 99999
        url = f"https://www.wanted.co.kr/wd/{url_id:05d}"

        skill_str = f"{skill_a}, {skill_b}" if skill_a != skill_b else skill_a

        postings.append(
            {
                "title": f"{company} — {role}",
                "source": source,
                "url": url,
                "summary": (
                    f"{company}에서 {exp} {emp} {role}을 채용합니다. "
                    f"주요 기술: {skill_str}."
                ),
                "why_it_matters": (
                    f"목표 기업({company})의 공고이며 "
                    f"핵심 스킬({skill_a})과 매칭됩니다."
                ),
            }
        )
    return postings


def _build_markdown(
    briefing_date: str,
    new_postings: list[dict],
    deadline_postings: list[dict],
    primary_role: str,
    primary_skill: str,
    companies: list[str],
) -> str:
    lines = [
        "## 오늘의 채용 요약",
        "",
        (
            f"{briefing_date} 기준, 설정하신 선호도에 맞는 "
            f"신규 공고 **{len(new_postings)}건**, "
            f"마감 임박 공고 **{len(deadline_postings)}건**이 확인되었습니다."
        ),
        "",
        "---",
        "",
        "### 📌 신규 공고",
        "",
    ]

    for i, p in enumerate(new_postings, 1):
        lines += [
            f"#### {i}. {p['title']}",
            f"**출처**: {p['source']}",
            f"**요약**: {p['summary']}",
            f"**매칭 이유**: {p['why_it_matters']}",
            f"[공고 보기]({p['url']})",
            "",
        ]

    if deadline_postings:
        lines += [
            "---",
            "",
            "### ⏰ 마감 임박 공고 (3일 이내)",
            "",
        ]
        for i, p in enumerate(deadline_postings, 1):
            lines += [
                f"#### {i}. {p['title']}",
                "**⚠️ 마감 임박**: 지원 기한을 확인하세요.",
                f"**요약**: {p['summary']}",
                f"[공고 보기]({p['url']})",
                "",
            ]

    company_label = "·".join(companies[:3]) if companies else "관심 기업"
    lines += [
        "---",
        "",
        "### 💡 오늘의 추천 액션",
        "",
        f"1. {company_label}의 채용 공고를 지금 바로 확인하고 지원해보세요.",
        (
            f"2. 포트폴리오에 **{primary_skill}** 프로젝트를 "
            "추가하면 서류 통과율이 높아집니다."
        ),
        "3. 오늘 마감되는 공고가 없는지 다시 한 번 확인하세요.",
        (
            f"4. {primary_role} 관련 기술 블로그나 오픈소스 기여로 "
            "GitHub를 업데이트하세요."
        ),
        "",
    ]

    return "\n".join(lines)


def generate(request: BriefingGenerateRequest) -> BriefingGenerateResponse:
    pref = request.preference
    roles = pref.roles
    companies = pref.companies
    skills = pref.skills
    exp_levels = pref.experience_levels
    emp_types = pref.employment_types

    primary_role = roles[0] if roles else "소프트웨어 엔지니어"
    primary_skill = skills[0] if skills else "개발"

    all_postings = _build_postings(
        companies, roles, skills, exp_levels, emp_types,
        request.user_id, request.briefing_date,
    )
    new_postings = all_postings[:_NEW_COUNT]
    deadline_postings = all_postings[_NEW_COUNT:]

    title = f"오늘의 채용 브리핑 — {primary_role} ({request.briefing_date})"
    if companies:
        co_display = "·".join(companies[:3])
        extra = len(companies) - 3
        extra_str = f" 외 {extra}개 기업" if extra > 0 else ""
        summary = (
            f"{co_display}{extra_str}에서 신규 공고 {len(new_postings)}건, "
            f"마감 임박 공고 {len(deadline_postings)}건이 확인되었습니다."
        )
    else:
        summary = (
            f"신규 공고 {len(new_postings)}건, "
            f"마감 임박 공고 {len(deadline_postings)}건이 확인되었습니다."
        )

    content = _build_markdown(
        request.briefing_date,
        new_postings,
        deadline_postings,
        primary_role,
        primary_skill,
        companies,
    )

    published_at = f"{request.briefing_date}T09:00:00"
    articles = [
        JobArticle(
            title=p["title"],
            source=p["source"],
            url=p["url"],
            summary=p["summary"],
            why_it_matters=p["why_it_matters"],
            published_at=published_at,
        )
        for p in all_postings
    ]

    return BriefingGenerateResponse(
        title=title,
        summary=summary,
        content=content,
        articles=articles,
        token_usage=TokenUsage(input_tokens=0, output_tokens=0),
    )
