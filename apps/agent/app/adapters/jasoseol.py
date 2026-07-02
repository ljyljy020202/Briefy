"""JasoseolAdapter — collects job postings from jasoseol.com public pages.

Strategy:
  1. Fetch sitemap XMLs to discover posting IDs with lastmod dates.
  2. Filter by lastmod >= collect_date - lookback_days.
  3. Fetch individual posting pages concurrently (max 5).
  4. Parse each page HTML for company_name, title, deadline, employment_type.
  5. Return RawJobPosting objects; missing optional fields remain None.

All per-operation failures (network errors, parse errors, timeouts) are caught,
logged, and accumulated in warnings. The adapter never raises to the caller.

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

from app.adapters.base import AdapterResult, JobBoardAdapter, RawJobPosting
from app.core.config import settings
from app.schemas.collection import CollectionOptions, SeedKeywords

log = logging.getLogger(__name__)

_SOURCE = "jasoseol"
_MAX_CONCURRENCY = 5
_SITEMAP_PATHS = [
    "/sitemap/employment_companies.xml",
    "/sitemap/intern_employment_companies.xml",
]
_EMPLOYMENT_TYPES = ["정규직", "계약직", "인턴", "파견직", "프리랜서"]
_KR_DATE_RE = re.compile(r"(\d{4})년\s*(\d{1,2})월\s*(\d{1,2})일")
_SM_NS = {"sm": "http://www.sitemaps.org/schemas/sitemap/0.9"}


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
                urls = await _collect_recent_urls(
                    client, base_url, collect_date, options, warnings
                )
                postings = await _fetch_pages(client, urls, warnings)
        except Exception as exc:
            msg = f"jasoseol: unexpected top-level error: {exc}"
            log.warning(msg)
            warnings.append(msg)
            return AdapterResult(postings=[], warnings=warnings)

        return AdapterResult(postings=postings, warnings=warnings)


# ── sitemap ───────────────────────────────────────────────────────────────────


async def _collect_recent_urls(
    client: AsyncClient,
    base_url: str,
    collect_date: date,
    options: CollectionOptions,
    warnings: list[str],
) -> list[str]:
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
    return [u for _, u in candidates[: options.max_items_per_source]]


def _parse_sitemap(xml_text: str, cutoff: date) -> list[tuple[date, str]]:
    """Parse sitemap XML and return (lastmod_date, url) pairs within cutoff.

    URLs without a parseable lastmod are included (conservative default).
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
            results.append((lastmod or date.max, loc))

    return results


def _parse_lastmod(value: str | None) -> date | None:
    if not value:
        return None
    try:
        return datetime.fromisoformat(value.strip()).date()
    except ValueError:
        return None


# ── page fetching ─────────────────────────────────────────────────────────────


async def _fetch_pages(
    client: AsyncClient,
    urls: list[str],
    warnings: list[str],
) -> list[RawJobPosting]:
    semaphore = asyncio.Semaphore(_MAX_CONCURRENCY)
    tasks = [_fetch_one(client, url, semaphore, warnings) for url in urls]
    results = await asyncio.gather(*tasks)
    return [p for p in results if p is not None]


async def _fetch_one(
    client: AsyncClient,
    url: str,
    semaphore: asyncio.Semaphore,
    warnings: list[str],
) -> RawJobPosting | None:
    async with semaphore:
        try:
            resp = await client.get(url)
            resp.raise_for_status()
            return parse_posting_page(resp.text, url)
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


# ── HTML parsing ──────────────────────────────────────────────────────────────


def parse_posting_page(html: str, url: str) -> RawJobPosting | None:
    """Parse a jasoseol.com individual posting page.

    Returns None when required fields (company_name or title) cannot be found.
    Optional fields are left as None / [] rather than fabricated.
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
    )


def _extract_company_name(soup: BeautifulSoup) -> str | None:
    img = soup.find(
        "img", alt=lambda a: isinstance(a, str) and "기업 아이콘" in a
    )
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
