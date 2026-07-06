"""JasoseolAdapter — collects job postings from jasoseol.com public pages.

Pipeline:
  1. Fetch sitemap XMLs → discover (lastmod, url) pairs filtered by lookback.
     Capped at discovery_limit_per_source, sorted recency-first.
     Unknown-lastmod URLs are included (conservative) and sort after dated ones.
  2. Fetch individual posting pages, capped at detail_fetch_limit_per_source.
  3. Parse each page for company_name, title, deadline, employment_type.
     source_external_id is extracted from the URL path (/recruit/<id> or
     /intern/<id>).  Roles, skills, location, experience_level are NOT
     fabricated — those fields remain empty.
  4. Score parsed postings for collection-level relevance against seed
     keywords using only available fields (title, company_name,
     employment_type).  Do NOT use seeds to filter sitemap discovery;
     jasoseol sitemaps cannot be searched by keyword.
  5. Select up to max_results_per_source with an exploration quota so recent
     postings outside current seeds are not silently dropped.

All per-operation failures are caught, logged, and added to warnings.
The adapter never raises to the caller.

robots.txt: jasoseol.com allows all paths under /.
No login, no CAPTCHA bypass, no browser automation.
"""

import asyncio
import logging
import re
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
from app.core.config import settings
from app.schemas.collection import CollectionOptions, SeedKeywords

log = logging.getLogger(__name__)

_SOURCE = "jasoseol"
_MAX_CONCURRENCY = 5
# Fraction of the per-source budget reserved for recency-only exploration.
# Prevents the adapter from returning only seed-matching postings and missing
# novel recent listings.
_EXPLORATION_RATIO = 0.2
_SITEMAP_PATHS = [
    "/sitemap/employment_companies.xml",
    "/sitemap/intern_employment_companies.xml",
]
_EMPLOYMENT_TYPES = ["정규직", "계약직", "인턴", "파견직", "프리랜서"]
_KR_DATE_RE = re.compile(r"(\d{4})년\s*(\d{1,2})월\s*(\d{1,2})일")
_SM_NS = {"sm": "http://www.sitemaps.org/schemas/sitemap/0.9"}
# Matches /recruit/<id> or /intern/<id> in a Jasoseol URL
_EXTERNAL_ID_RE = re.compile(r"/(?:recruit|intern)/(\d+)")


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
        warnings: list[str] = []

        try:
            async with AsyncClient(
                timeout=timeout,
                headers={"User-Agent": "Briefy-Agent/1.0 (+https://briefy.io)"},
                follow_redirects=True,
            ) as client:
                # Stage 1: Discover URLs from sitemaps (sorted recency-first)
                url_entries = await _collect_recent_url_entries(
                    client, base_url, collect_date, options, warnings
                )
                discovered = len(url_entries)

                # Stage 2: Fetch pages (cap at detail_fetch_limit_per_source)
                fetch_limit = min(discovered, options.detail_fetch_limit_per_source)
                entries_to_fetch = url_entries[:fetch_limit]

                # Stage 3: Parse pages → (lastmod, RawJobPosting) pairs
                raw_entries = await _fetch_pages_with_dates(
                    client, entries_to_fetch, warnings
                )
                parsed = len(raw_entries)

                # Stage 4+5: Score, select with exploration quota
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
            fetched=len(entries_to_fetch),
            parsed=parsed,
            selected=len(postings),
        )
        log.info(
            "jasoseol: discovered=%d fetched=%d parsed=%d selected=%d",
            discovered,
            len(entries_to_fetch),
            parsed,
            len(postings),
        )
        return AdapterResult(postings=postings, warnings=warnings, source_stats=stats)


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
            # Use date.min for unknown lastmod so these sort LAST, not first
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


# ── relevance and selection ───────────────────────────────────────────────────


def _extract_source_external_id(url: str) -> str | None:
    """Extract the numeric posting ID from a Jasoseol recruit or intern URL.

    Returns the bare numeric ID string, e.g. "104949" for
    https://jasoseol.com/recruit/104949, or None for unrecognised patterns.
    """
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

    exploration_slots = min(max(1, int(limit * _EXPLORATION_RATIO)), limit // 2)
    relevance_slots = limit - exploration_slots

    scored = [
        (posting, _relevance_score(posting, seed), lastmod)
        for lastmod, posting in entries
    ]

    # Primary: highest relevance first, recency as tiebreaker for ties
    by_relevance = sorted(scored, key=lambda x: (x[1], x[2]), reverse=True)

    selected: list[RawJobPosting] = []
    selected_urls: set[str] = set()

    for posting, _, _ in by_relevance:
        if len(selected) >= relevance_slots:
            break
        selected.append(posting)
        selected_urls.add(posting.source_url)

    # Exploration: remaining entries sorted by recency
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
