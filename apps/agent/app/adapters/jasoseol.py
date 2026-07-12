"""JasoseolAdapter — collects job postings from jasoseol.com public pages.

Pipeline (Phase 2 — role-aware hybrid discovery):

  Pre-fetch discovery:
    A. TARGETED: public /search?dutyGroupIds=... → extract posting URLs
       (only when seedKeywords.roles can be mapped to developer duty groups)
    B. EXPLORATION: sitemap XMLs → (lastmod, url) pairs filtered by lookback

  Candidate merge:
    → deduplicate by sourceExternalId (URL fallback)
    → cap at discovery_limit_per_source
    → tag each candidate: TARGETED | EXPLORATION | BOTH

  Detail-fetch budget allocation (80/20 preferred split, configurable):
    → preferred 80 % of detail_fetch_limit for TARGETED+BOTH candidates
    → preferred 20 % for EXPLORATION-only candidates
    → shortfalls in either pool backfill from the other

  Post-fetch (unchanged from Phase 1):
    → parse_posting_page()
    → _select_postings() (relevance + recency, exploration quota)
    → AdapterSourceStats

Policy:
  - Targeted Discovery failure → warning + Sitemap Exploration fallback only
  - Empty or unmapped roles → Sitemap Exploration only (no targeted query)
  - All per-operation failures are caught, logged, and added to warnings.
  - The adapter never raises to the caller.

robots.txt (jasoseol.com, verified 2026-07-09):
  Disallow: /crt/*, /core, /rocket_correction, /coach-sellers, /webview/, /demo/
  Allow: / (all other paths, including /search)
No login, no CAPTCHA bypass, no browser automation.
"""

import asyncio
import logging
import re
from dataclasses import dataclass, replace
from datetime import date, datetime, timedelta
from xml.etree import ElementTree as ET

from bs4 import BeautifulSoup
from httpx import AsyncClient, HTTPStatusError, TimeoutException

from app.adapters.base import (
    AdapterResult,
    AdapterSourceStats,
    JobBoardAdapter,
    RawJobPosting,
)
from app.adapters.jasoseol_role_planner import (
    get_combined_duty_ids,
    plan_developer_roles,
)
from app.adapters.jasoseol_search import SearchCandidate, discover_from_search
from app.core.config import settings
from app.schemas.collection import CollectionOptions, SeedKeywords

log = logging.getLogger(__name__)

_SOURCE = "jasoseol"
_MAX_CONCURRENCY = 5
# Set False to collect via /search targeted discovery only (no sitemap exploration).
# Set True to re-enable sitemap exploration with the quota below.
_EXPLORATION_ENABLED = False
# Post-parse exploration quota — fraction of max_results_per_source reserved
# for recency-only (non-relevance) picks after parsing.
_POST_PARSE_EXPLORATION_RATIO = 0.2
_SITEMAP_PATHS = [
    "/sitemap/employment_companies.xml",
    "/sitemap/intern_employment_companies.xml",
]
_EMPLOYMENT_TYPES = ["정규직", "계약직", "인턴", "파견직", "프리랜서"]
_KR_DATE_RE = re.compile(r"(\d{4})년\s*(\d{1,2})월\s*(\d{1,2})일")
_SM_NS = {"sm": "http://www.sitemaps.org/schemas/sitemap/0.9"}
_EXTERNAL_ID_RE = re.compile(r"/(?:recruit|intern)/(\d+)")


@dataclass
class _CandidateEntry:
    """Unified candidate record for pre-detail-fetch dedup and allocation."""

    url: str
    source_external_id: str | None
    # date.min for TARGETED-only candidates (no sitemap lastmod available)
    lastmod: date
    # "TARGETED" | "EXPLORATION" | "BOTH"
    provenance: str


class JasoseolAdapter(JobBoardAdapter):
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

        base_url = settings.jasoseol_base_url
        timeout = settings.job_collection_timeout_seconds
        targeted_ratio = settings.jasoseol_targeted_discovery_ratio
        warnings: list[str] = []

        try:
            async with AsyncClient(
                timeout=timeout,
                headers={"User-Agent": "Briefy-Agent/1.0 (+https://briefy.io)"},
                follow_redirects=True,
            ) as client:
                # ── A. Targeted Discovery ──────────────────────────────────────
                targeted_candidates: list[SearchCandidate] = []
                role_groups = plan_developer_roles(seed_keywords.roles)
                duty_ids = get_combined_duty_ids(role_groups)

                if duty_ids:
                    try:
                        targeted_candidates = await discover_from_search(
                            client,
                            base_url,
                            duty_ids,
                            limit=options.discovery_limit_per_source,
                            warnings=warnings,
                        )
                    except Exception as exc:
                        msg = (
                            f"jasoseol: targeted discovery failed, "
                            f"falling back to sitemap only: {exc}"
                        )
                        log.warning(msg)
                        warnings.append(msg)
                        targeted_candidates = []

                # ── B. Sitemap Exploration Discovery ──────────────────────────
                if _EXPLORATION_ENABLED:
                    exploration_entries = await _collect_recent_url_entries(
                        client, base_url, collect_date, options, warnings
                    )
                else:
                    exploration_entries = []

                # ── C. Merge & Dedup ──────────────────────────────────────────
                candidates = _merge_candidates(
                    targeted_candidates,
                    exploration_entries,
                    options.discovery_limit_per_source,
                )
                discovered = len(candidates)

                # ── D. Budget Allocation & Detail Fetch ───────────────────────
                to_fetch = _allocate_fetch_budget(
                    candidates,
                    options.detail_fetch_limit_per_source,
                    targeted_ratio,
                )
                fetch_entries = [(c.lastmod, c.url) for c in to_fetch]

                raw_entries = await _fetch_pages_with_dates(
                    client, fetch_entries, warnings
                )
                parsed = len(raw_entries)

                # ── E. Post-parse Selection (unchanged) ───────────────────────
                postings = _select_postings(
                    raw_entries, seed_keywords, options.max_results_per_source
                )

        except Exception as exc:
            msg = f"jasoseol: unexpected top-level error: {exc}"
            log.warning(msg)
            warnings.append(msg)
            return AdapterResult(
                postings=[],
                warnings=warnings,
                source_stats=AdapterSourceStats(),
            )

        stats = AdapterSourceStats(
            discovered=discovered,
            fetched=len(to_fetch),
            parsed=parsed,
            selected=len(postings),
        )
        log.info(
            "jasoseol: discovered=%d fetched=%d parsed=%d selected=%d "
            "(targeted=%d exploration=%d)",
            discovered,
            len(to_fetch),
            parsed,
            len(postings),
            sum(1 for c in candidates if c.provenance in ("TARGETED", "BOTH")),
            sum(1 for c in candidates if c.provenance == "EXPLORATION"),
        )
        return AdapterResult(postings=postings, warnings=warnings, source_stats=stats)


# ── candidate merge ───────────────────────────────────────────────────────────


def _merge_candidates(
    targeted: list[SearchCandidate],
    exploration: list[tuple[date, str]],
    discovery_limit: int,
) -> list[_CandidateEntry]:
    """Merge TARGETED and EXPLORATION candidates, dedup by sourceExternalId.

    - A URL present in both paths becomes provenance=BOTH and inherits the
      sitemap lastmod for the EXPLORATION-derived recency ordering.
    - TARGETED candidates without a lastmod use date.min so they sort AFTER
      dated EXPLORATION candidates when recency-sorted.
    - Result is capped at discovery_limit.
    """
    entries: dict[str, _CandidateEntry] = {}

    for sc in targeted:
        key = sc.source_external_id
        entries[key] = _CandidateEntry(
            url=sc.url,
            source_external_id=sc.source_external_id,
            lastmod=date.min,
            provenance="TARGETED",
        )

    for lastmod, url in exploration:
        sid = _extract_source_external_id(url)
        key = sid if sid else url
        if key in entries:
            entries[key] = replace(
                entries[key], provenance="BOTH", lastmod=lastmod
            )
        else:
            entries[key] = _CandidateEntry(
                url=url,
                source_external_id=sid,
                lastmod=lastmod,
                provenance="EXPLORATION",
            )

    result = list(entries.values())[:discovery_limit]
    return result


# ── budget allocation ─────────────────────────────────────────────────────────


def _allocate_fetch_budget(
    candidates: list[_CandidateEntry],
    detail_fetch_limit: int,
    targeted_ratio: float,
) -> list[_CandidateEntry]:
    """Allocate the detail-fetch budget with a preferred 80/20 split.

    Preferred:
      targeted_ratio  fraction → TARGETED+BOTH candidates
      1 - targeted_ratio       → EXPLORATION-only candidates

    Shortfalls in either pool are backfilled from the other to avoid wasting
    budget.  Total selection never exceeds detail_fetch_limit.

    Ordering within each pool (deterministic):
      TARGETED+BOTH: source_external_id desc (higher numeric = newer)
      EXPLORATION:   lastmod desc, source_external_id desc as tie-breaker
    """
    targeted_pool = sorted(
        [c for c in candidates if c.provenance in ("TARGETED", "BOTH")],
        key=lambda c: (int(c.source_external_id or 0), c.url),
        reverse=True,
    )
    exploration_pool = sorted(
        [c for c in candidates if c.provenance == "EXPLORATION"],
        key=lambda c: (c.lastmod, int(c.source_external_id or 0), c.url),
        reverse=True,
    )

    pref_t = min(int(detail_fetch_limit * targeted_ratio), detail_fetch_limit)
    pref_e = detail_fetch_limit - pref_t

    t_count = min(pref_t, len(targeted_pool))
    taken_t = targeted_pool[:t_count]

    e_count = min(pref_e, len(exploration_pool))
    taken_e = exploration_pool[:e_count]

    remaining = detail_fetch_limit - t_count - e_count

    if remaining > 0:
        # Try to backfill from remaining exploration first
        extra_e = exploration_pool[e_count: e_count + remaining]
        taken_e = taken_e + extra_e
        remaining -= len(extra_e)

    if remaining > 0:
        # Then backfill from remaining targeted
        extra_t = targeted_pool[t_count: t_count + remaining]
        taken_t = taken_t + extra_t

    return (taken_t + taken_e)[:detail_fetch_limit]


# ── sitemap ───────────────────────────────────────────────────────────────────


async def _collect_recent_url_entries(
    client: AsyncClient,
    base_url: str,
    collect_date: date,
    options: CollectionOptions,
    warnings: list[str],
) -> list[tuple[date, str]]:
    """Return (lastmod, url) pairs from all sitemaps, sorted recency-first.

    Unknown-lastmod entries use date.min so they sort AFTER known-date entries.
    Result is capped at discovery_limit_per_source.
    """
    cutoff = collect_date - timedelta(days=options.lookback_days)
    candidates: list[tuple[date, str]] = []

    for path in _SITEMAP_PATHS:
        url = f"{base_url}{path}"
        try:
            resp = await client.get(url)
            resp.raise_for_status()
            entries = _parse_sitemap(resp.text, cutoff)
            candidates.extend(entries)
        except TimeoutException:
            msg = f"jasoseol: sitemap timeout: {path}"
            log.warning(msg)
            warnings.append(msg)
        except HTTPStatusError as exc:
            msg = f"jasoseol: sitemap HTTP {exc.response.status_code}: {path}"
            log.warning(msg)
            warnings.append(msg)
        except Exception as exc:
            msg = f"jasoseol: sitemap error {path}: {exc}"
            log.warning(msg)
            warnings.append(msg)

    candidates.sort(key=lambda t: t[0], reverse=True)
    return candidates[: options.discovery_limit_per_source]


def _parse_sitemap(xml_text: str, cutoff: date) -> list[tuple[date, str]]:
    """Parse sitemap XML and return (lastmod_date, url) pairs within cutoff.

    URLs without a parseable lastmod are included (conservative default) and
    assigned date.min so they sort after all known-date entries.
    """
    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError as exc:
        raise ValueError(f"invalid sitemap XML: {exc}") from exc

    results: list[tuple[date, str]] = []

    for url_el in root.findall("sm:url", _SM_NS):
        loc_el = url_el.find("sm:loc", _SM_NS)
        lastmod_el = url_el.find("sm:lastmod", _SM_NS)

        if loc_el is None or not loc_el.text:
            continue

        loc = loc_el.text.strip()
        lastmod = _parse_lastmod(lastmod_el.text if lastmod_el is not None else None)

        if lastmod is None or lastmod >= cutoff:
            results.append((lastmod or date.min, loc))

    return results


def _parse_lastmod(value: str | None) -> date | None:
    if not value:
        return None
    try:
        return datetime.fromisoformat(value.strip()).date()
    except ValueError:
        return None


# ── page fetching ─────────────────────────────────────────────────────────────


async def _fetch_pages_with_dates(
    client: AsyncClient,
    entries: list[tuple[date, str]],
    warnings: list[str],
) -> list[tuple[date, RawJobPosting]]:
    semaphore = asyncio.Semaphore(_MAX_CONCURRENCY)
    tasks = [
        _fetch_one_with_date(client, lastmod, url, semaphore, warnings)
        for lastmod, url in entries
    ]
    results = await asyncio.gather(*tasks)
    return [r for r in results if r is not None]


async def _fetch_one_with_date(
    client: AsyncClient,
    lastmod: date,
    url: str,
    semaphore: asyncio.Semaphore,
    warnings: list[str],
) -> tuple[date, RawJobPosting] | None:
    async with semaphore:
        try:
            resp = await client.get(url)
            resp.raise_for_status()
            posting = parse_posting_page(resp.text, url)
            if posting is not None:
                return (lastmod, posting)
        except TimeoutException:
            msg = f"jasoseol: page timeout: {url}"
            log.warning(msg)
            warnings.append(msg)
        except HTTPStatusError as exc:
            msg = f"jasoseol: page HTTP {exc.response.status_code}: {url}"
            log.warning(msg)
            warnings.append(msg)
        except Exception as exc:
            msg = f"jasoseol: page error {url}: {exc}"
            log.warning(msg)
            warnings.append(msg)
    return None


# ── relevance and post-parse selection ───────────────────────────────────────


def _extract_source_external_id(url: str) -> str | None:
    """Extract the numeric posting ID from a Jasoseol recruit or intern URL."""
    m = _EXTERNAL_ID_RE.search(url)
    return m.group(1) if m else None


def _relevance_score(posting: RawJobPosting, seed: SeedKeywords) -> float:
    """Collection-level relevance using only Jasoseol's available fields.

    Scores on company_name, title, and employment_type — the only fields
    Jasoseol HTML reliably provides.  Skills, location, and experience_level
    are NOT used; fabricating them is forbidden.
    """
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
    """Select up to `limit` postings combining relevance, recency, and exploration.

    This is the post-parse selection step — distinct from the pre-fetch
    TARGETED vs EXPLORATION allocation.

    Budget split (deterministic):
      relevance_slots = limit - exploration_slots
        → sorted by relevance desc, recency as tiebreaker
      exploration_slots = min(max(1, int(limit * RATIO)), limit // 2)
        → remaining postings sorted by recency desc

    Exploration ensures diverse recent postings outside current seeds are not
    silently discarded.  When all relevance scores are equal (no seeds), the
    full limit is filled by recency.
    """
    if not entries or limit <= 0:
        return []

    if _EXPLORATION_ENABLED:
        exploration_slots = min(
            max(1, int(limit * _POST_PARSE_EXPLORATION_RATIO)), limit // 2
        )
    else:
        exploration_slots = 0
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


# ── HTML parsing ──────────────────────────────────────────────────────────────


def parse_posting_page(html: str, url: str) -> RawJobPosting | None:
    """Parse a jasoseol.com individual posting page.

    Returns None when required fields (company_name or title) cannot be found.
    Optional fields are left as None / [] rather than fabricated.
    source_external_id is extracted from the URL path when possible.
    """
    soup = BeautifulSoup(html, "lxml")

    company_name = _extract_company_name(soup)
    title = _extract_title(soup)

    if not company_name or not title:
        return None

    return RawJobPosting(
        source=_SOURCE,
        source_url=url,
        company_name=company_name,
        title=title,
        position=None,
        employment_type=_extract_employment_type(soup),
        deadline=_extract_deadline(soup),
        skills=[],
        roles=[],
        location=None,
        experience_level=None,
        description=None,
        posted_at=None,
        source_external_id=_extract_source_external_id(url),
    )


def _extract_company_name(soup: BeautifulSoup) -> str | None:
    img = soup.find("img", alt=lambda a: isinstance(a, str) and "기업 아이콘" in a)
    if img and img.get("alt"):
        name = img["alt"].replace("기업 아이콘", "").strip()
        return name if name else None
    return None


def _extract_title(soup: BeautifulSoup) -> str | None:
    for tag in ("h1", "h2"):
        el = soup.find(tag)
        if el:
            text = el.get_text(separator=" ", strip=True)
            if text:
                return text
    return None


def _extract_deadline(soup: BeautifulSoup) -> date | None:
    """Return the last Korean date found in page text (usually the end date)."""
    matches = _KR_DATE_RE.findall(soup.get_text())
    if not matches:
        return None
    year, month, day = matches[-1]
    try:
        return date(int(year), int(month), int(day))
    except ValueError:
        return None


def _extract_employment_type(soup: BeautifulSoup) -> str | None:
    text = soup.get_text()
    for keyword in _EMPLOYMENT_TYPES:
        if keyword in text:
            return keyword
    return None
