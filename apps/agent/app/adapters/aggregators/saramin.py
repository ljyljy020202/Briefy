"""SaraminAdapter — collects job postings from the official Saramin API.

Pipeline:
  1. Build query list from seed keywords (bounded deterministic planner).
     Priority: roles → target companies → role+skill combinations.
     Deduplication by frozenset of whitespace-split tokens prevents equivalent
     queries from consuming extra budget.
  2. For each query, paginate the Saramin job-search API until the page budget
     is exhausted or the API reports no more results.
  3. Parse API response fields — only reliable official response fields are
     mapped.  Do NOT fabricate values for missing or ambiguous fields.
  4. Deduplicate by source_url within this adapter (cross-source dedup is
     handled by the shared Pipeline V2 normalization stage).
  5. Score parsed postings for collection-level relevance against seed keywords.
  6. Select up to max_results_per_source with an exploration quota.

Authentication: access-key query parameter per official Saramin API spec.
No HTML scraping, no internal/private APIs.
"""

import logging
from dataclasses import dataclass
from datetime import date, datetime
from typing import Any

from httpx import AsyncClient, HTTPStatusError, TimeoutException

from app.adapters.base import (
    AdapterResult,
    AdapterSourceStats,
    JobBoardAdapter,
    RawJobPosting,
)
from app.core.config import settings
from app.schemas.collection import CollectionOptions, SeedKeywords

log = logging.getLogger(__name__)

_SOURCE = "saramin"
_COUNT_PER_PAGE = 40  # Saramin API maximum items per response
_EXPLORATION_RATIO = 0.2


@dataclass
class _SaraminQuery:
    keywords: str
    sort: str = "pd"  # pd = posting date descending

    def dedup_key(self) -> frozenset[str]:
        return frozenset(self.keywords.lower().split())


def _build_queries(seed: SeedKeywords, max_queries: int) -> list[_SaraminQuery]:
    """Bounded deterministic query planner.

    Priority order:
      1. roles          (e.g. "백엔드 개발자")
      2. target companies  (e.g. "카카오")
      3. role + skill combinations  (top 3 roles × top 2 skills)

    Equivalent keyword sets (same tokens regardless of order) are deduplicated.
    Result is capped at max_queries.
    """
    queries: list[_SaraminQuery] = []
    seen: set[frozenset] = set()

    def _add(keywords: str) -> None:
        key = frozenset(keywords.lower().split())
        if not key or key in seen or len(queries) >= max_queries:
            return
        seen.add(key)
        queries.append(_SaraminQuery(keywords=keywords))

    for role in seed.roles:
        _add(role)

    for company in seed.companies:
        _add(company)

    for role in seed.roles[:3]:
        for skill in seed.skills[:2]:
            _add(f"{role} {skill}")

    return queries


async def _fetch_query(
    client: AsyncClient,
    query: _SaraminQuery,
    base_url: str,
    access_key: str,
    count: int,
    page_budget: int,
    warnings: list[str],
) -> tuple[list[RawJobPosting], int]:
    """Paginate one query up to page_budget API requests.

    Returns (postings, pages_used).
    Partial failures add warnings but do not raise.
    """
    postings: list[RawJobPosting] = []
    pages_used = 0
    start = 0

    while pages_used < page_budget:
        params = {
            "access-key": access_key,
            "keywords": query.keywords,
            "sort": query.sort,
            "count": count,
            "start": start,
        }
        try:
            resp = await client.get(f"{base_url}/job-search", params=params)
        except TimeoutException:
            warnings.append(
                f"saramin: timeout for keywords='{query.keywords}' start={start}"
            )
            break
        except Exception as exc:
            warnings.append(
                f"saramin: request error for keywords='{query.keywords}': {exc}"
            )
            break

        pages_used += 1

        if resp.status_code == 401:
            warnings.append(
                "saramin: authentication error (401) — check SARAMIN_ACCESS_KEY"
            )
            break
        try:
            resp.raise_for_status()
        except HTTPStatusError as exc:
            warnings.append(
                f"saramin: HTTP {exc.response.status_code} for"
                f" keywords='{query.keywords}'"
            )
            break

        try:
            data = resp.json()
            jobs_root = data.get("jobs") or {}
            total = int(jobs_root.get("total") or 0)
            job_list = jobs_root.get("job") or []
            if not isinstance(job_list, list):
                job_list = []
        except Exception as exc:
            warnings.append(
                f"saramin: malformed response for keywords='{query.keywords}': {exc}"
            )
            break

        for item in job_list:
            posting = _parse_job(item)
            if posting is not None:
                postings.append(posting)

        start += len(job_list)
        if not job_list or start >= total:
            break

    return postings, pages_used


def _parse_job(job: Any) -> RawJobPosting | None:
    """Map a Saramin API job object to RawJobPosting.

    Only fields reliably present in the official API response are set.
    Missing optional fields remain None / [] rather than being fabricated.
    """
    try:
        job_id = str(job.get("id") or "").strip() or None
        url = str(job.get("url") or "").strip()
        company_name = (
            ((job.get("company") or {}).get("detail") or {}).get("name") or ""
        ).strip()
        position = job.get("position") or {}
        title = str(position.get("title") or "").strip()

        if not url or not company_name or not title:
            return None

        emp_type = ((position.get("job-type") or {}).get("name") or "").strip() or None
        location = ((position.get("location") or {}).get("name") or "").strip() or None
        exp_level = (
            (position.get("experience-level") or {}).get("name") or ""
        ).strip() or None
        job_category = ((position.get("job-category") or {}).get("name") or "").strip()
        roles = [job_category] if job_category else []

        keyword_str = str(job.get("keyword") or "").strip()
        skills = [k.strip() for k in keyword_str.split(",") if k.strip()]

        deadline = _parse_api_date(str(job.get("expiration-date") or ""))
        posted_at = _parse_api_datetime(str(job.get("posting-date") or ""))

        return RawJobPosting(
            source=_SOURCE,
            source_url=url,
            source_external_id=job_id,
            company_name=company_name,
            title=title,
            employment_type=emp_type,
            location=location,
            experience_level=exp_level,
            deadline=deadline,
            skills=skills,
            roles=roles,
            posted_at=posted_at,
        )
    except Exception:
        return None


def _parse_api_date(value: str) -> date | None:
    value = value.strip()
    if not value:
        return None
    try:
        return datetime.fromisoformat(value).date()
    except ValueError:
        return None


def _parse_api_datetime(value: str) -> datetime | None:
    value = value.strip()
    if not value:
        return None
    try:
        return datetime.fromisoformat(value)
    except ValueError:
        return None


def _relevance_score(posting: RawJobPosting, seed: SeedKeywords) -> float:
    """Collection-level relevance using available Saramin API fields."""
    score = 0.0
    title_lower = posting.title.lower()
    company_lower = posting.company_name.lower()

    for company in seed.companies:
        if company.lower() in company_lower:
            score += 3.0
            break

    for role in seed.roles:
        if role.lower() in title_lower:
            score += 2.0
            break

    if posting.skills:
        for skill in seed.skills:
            if any(skill.lower() in s.lower() for s in posting.skills):
                score += 1.0
                break

    for keyword in seed.keywords:
        if keyword.lower() in title_lower:
            score += 1.0
            break

    if posting.employment_type:
        emp_lower = posting.employment_type.lower()
        for emp_type in seed.employment_types:
            if emp_type.lower() in emp_lower:
                score += 0.5
                break

    return score


def _select_postings(
    entries: list[tuple[date, RawJobPosting]],
    seed: SeedKeywords,
    limit: int,
) -> list[RawJobPosting]:
    """Select up to limit postings combining relevance, recency, and exploration.

    Budget split (deterministic):
      exploration_slots = min(max(1, int(limit * RATIO)), limit // 2)
      relevance_slots   = limit - exploration_slots
    """
    if not entries or limit <= 0:
        return []

    exploration_slots = min(max(1, int(limit * _EXPLORATION_RATIO)), limit // 2)
    relevance_slots = limit - exploration_slots

    scored = [
        (posting, _relevance_score(posting, seed), lastmod)
        for lastmod, posting in entries
    ]

    by_relevance = sorted(scored, key=lambda x: (x[1], x[2]), reverse=True)

    selected: list[RawJobPosting] = []
    selected_urls: set[str] = set()

    for posting, _, _ in by_relevance:
        if len(selected) >= relevance_slots:
            break
        selected.append(posting)
        selected_urls.add(posting.source_url)

    remaining_by_recency = sorted(
        [
            (lastmod, posting)
            for posting, _, lastmod in scored
            if posting.source_url not in selected_urls
        ],
        key=lambda x: x[0],
        reverse=True,
    )
    for _, posting in remaining_by_recency[:exploration_slots]:
        selected.append(posting)

    return selected


class SaraminAdapter(JobBoardAdapter):
    @property
    def source_name(self) -> str:
        return _SOURCE

    async def fetch(
        self,
        seed_keywords: SeedKeywords,
        options: CollectionOptions,
        collect_date: date | None = None,
    ) -> AdapterResult:
        if collect_date is None:
            collect_date = date.today()

        access_key = settings.saramin_access_key
        base_url = settings.saramin_api_base_url
        timeout = settings.job_collection_timeout_seconds
        warnings: list[str] = []

        if not access_key:
            warnings.append("saramin: SARAMIN_ACCESS_KEY not configured, skipping")
            return AdapterResult(
                postings=[],
                warnings=warnings,
                source_stats=AdapterSourceStats(),
            )

        queries = _build_queries(
            seed_keywords, settings.saramin_max_queries_per_collect
        )
        if not queries:
            log.info("saramin: no queries generated from seed keywords")
            return AdapterResult(
                postings=[],
                warnings=warnings,
                source_stats=AdapterSourceStats(),
            )

        all_raw: list[RawJobPosting] = []
        total_pages = 0
        remaining_budget = options.detail_fetch_limit_per_source

        try:
            async with AsyncClient(
                timeout=timeout,
                headers={"Accept": "application/json"},
                follow_redirects=True,
            ) as client:
                for query in queries:
                    if remaining_budget <= 0:
                        break
                    postings, pages_used = await _fetch_query(
                        client,
                        query,
                        base_url,
                        access_key,
                        _COUNT_PER_PAGE,
                        remaining_budget,
                        warnings,
                    )
                    all_raw.extend(postings)
                    total_pages += pages_used
                    remaining_budget -= pages_used
        except Exception as exc:
            msg = f"saramin: unexpected top-level error: {exc}"
            log.warning(msg)
            warnings.append(msg)
            return AdapterResult(
                postings=[],
                warnings=warnings,
                source_stats=AdapterSourceStats(),
            )

        discovered = len(all_raw)

        # Deduplicate by URL within this adapter before handing off to Pipeline V2
        seen_urls: set[str] = set()
        unique: list[RawJobPosting] = []
        for p in all_raw:
            if p.source_url not in seen_urls:
                seen_urls.add(p.source_url)
                unique.append(p)

        parsed = len(unique)

        entries = [(p.posted_at.date() if p.posted_at else date.min, p) for p in unique]
        selected = _select_postings(
            entries, seed_keywords, options.max_results_per_source
        )

        stats = AdapterSourceStats(
            discovered=discovered,
            fetched=total_pages,
            parsed=parsed,
            selected=len(selected),
        )
        log.info(
            "saramin: queries=%d discovered=%d fetched=%d parsed=%d selected=%d",
            len(queries),
            discovered,
            total_pages,
            parsed,
            len(selected),
        )
        return AdapterResult(postings=selected, warnings=warnings, source_stats=stats)
