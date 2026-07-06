"""Deterministic stub for the daily collection workflow.

Generates sample job postings from seed keywords without calling any
external job-board APIs or LLM. Stable contentHash values let the Spring
backend run end-to-end upsert tests against real data shapes before real
scrapers are wired in.
"""

import hashlib
from datetime import datetime, timedelta

from app.schemas.collection import (
    CollectedJobPosting,
    CollectionStats,
    DailyCollectRequest,
    DailyCollectResponse,
)

_SOURCES = [
    ("원티드", "https://www.wanted.co.kr/wd/{id:05d}"),
    ("사람인", "https://www.saramin.co.kr/zf_user/jobs/relay/view?rec_idx={id}"),
    ("LinkedIn", "https://www.linkedin.com/jobs/view/{id}"),
    ("잡코리아", "https://www.jobkorea.co.kr/Recruit/GI_Read/{id}"),
    ("로켓펀치", "https://rocketpunch.com/jobs/{id}"),
]

_MIN_POSTINGS = 3
_MAX_POSTINGS = 5


def _content_hash(company: str, title: str, source_url: str) -> str:
    raw = f"{company}|{title}|{source_url}"
    return hashlib.sha256(raw.encode()).hexdigest()


def _build_job_postings(request: DailyCollectRequest) -> list[CollectedJobPosting]:
    kw = request.seed_keywords
    roles = kw.roles or ["소프트웨어 엔지니어"]
    companies = kw.companies or ["테크 스타트업"]
    skills = kw.skills or ["개발"]
    locations = kw.locations or ["서울"]
    exp_levels = kw.experience_levels or ["경력 무관"]
    emp_types = kw.employment_types or ["정규직"]

    # Vary count (3–5) deterministically by total number of provided keywords
    total_kw = len(kw.roles) + len(kw.companies) + len(kw.skills)
    count = _MIN_POSTINGS + (total_kw % (_MAX_POSTINGS - _MIN_POSTINGS + 1))

    # Use the date's ordinal as a stable seed so the same request always
    # produces the same URL IDs across calls.
    date_seed = request.collect_date.toordinal()

    postings: list[CollectedJobPosting] = []
    for i in range(count):
        company = companies[i % len(companies)]
        role = roles[i % len(roles)]
        skill_a = skills[i % len(skills)]
        skill_b = skills[(i + 1) % len(skills)]
        skill_list = [skill_a] if skill_a == skill_b else [skill_a, skill_b]
        location = locations[i % len(locations)]
        exp = exp_levels[i % len(exp_levels)]
        emp = emp_types[i % len(emp_types)]

        source_name, url_template = _SOURCES[i % len(_SOURCES)]
        # 7919 is prime; multiplying by it spreads IDs across 0–99998
        url_id = (date_seed + i * 7919) % 99999
        source_url = url_template.format(id=url_id)

        title = f"{company} — {role}"
        deadline = request.collect_date + timedelta(days=7 + i * 3)
        posted_at = datetime(
            request.collect_date.year,
            request.collect_date.month,
            request.collect_date.day,
            9,
            0,
            0,
        )
        description = (
            f"{company}에서 {exp} {emp} {role}을 채용합니다. "
            f"주요 기술: {', '.join(skill_list)}. "
            f"근무지: {location}."
        )

        postings.append(
            CollectedJobPosting(
                source=source_name,
                source_url=source_url,
                company_name=company,
                title=title,
                position=role,
                employment_type=emp,
                experience_level=exp,
                location=location,
                deadline=deadline,
                skills=skill_list,
                roles=[role],
                description=description,
                posted_at=posted_at,
                content_hash=_content_hash(company, title, source_url),
            )
        )

    return postings


def collect(request: DailyCollectRequest) -> DailyCollectResponse:
    job_postings: list[CollectedJobPosting] = []

    if "JOB_POSTING" in request.categories:
        job_postings = _build_job_postings(request)

    stats = CollectionStats(
        discovered=len(job_postings),
        fetched=len(job_postings),
        parsed=len(job_postings),
        final=len(job_postings),
    )

    return DailyCollectResponse(
        collection_job_id=request.collection_job_id,
        collect_date=request.collect_date,
        job_postings=job_postings,
        company_issues=[],
        industry_issues=[],
        stats=stats,
        warnings=[],
    )
