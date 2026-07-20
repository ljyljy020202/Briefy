"""OfficialCompanyAdapter — dispatches official company sources by adapterType.

Routes:
  SITEMAP — generic XML sitemap discovery + best-effort page title extraction
  RSS     — standard RSS 2.0 / Atom 1.0 feed parsing
  CUSTOM  — parser registry; primary key is parser_key from config_json,
             with a deprecated fallback to company_id for backward compatibility.

CUSTOM dispatch priority:
  1. config_json contains parser_key → look up _CUSTOM_REGISTRY_BY_KEY[parser_key]
  2. config_json null or empty       → legacy _CUSTOM_REGISTRY[company_id] (DEPRECATED)
  3. config_json present but broken  → warning + empty (invalid JSON / missing key)

config_json contract for CUSTOM sources:
  {
    "parser_key": "GREETING",   # required; trim + uppercase applied before lookup
    "max_discover": 50,          # optional; parsed by individual parsers as needed
    "max_fetch": 20              # optional; parsed by individual parsers as needed
  }

Unsupported adapterTypes or missing CUSTOM registrations add warnings and
never cause the overall collection to fail.

Rules enforced here:
  - No hardcoded company URLs — all URLs come from OfficialCompanySource.source_url
  - No browser automation, no private/internal APIs, no anti-bot bypass
  - Output (RawJobPosting) feeds shared Pipeline V2 normalization unchanged
"""

import asyncio
import json
import logging
import re
from abc import ABC, abstractmethod
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from email.utils import parsedate_to_datetime
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
from app.schemas.collection import (
    CollectionOptions,
    CompanyProfile,
    OfficialCompanySource,
    SeedKeywords,
)

log = logging.getLogger(__name__)

_EMPLOYMENT_KEYWORDS = ["정규직", "계약직", "인턴", "파견직", "프리랜서"]
_KR_DATE_RE = re.compile(r"(\d{4})년\s*(\d{1,2})월\s*(\d{1,2})일")
_SM_NS = {"sm": "http://www.sitemaps.org/schemas/sitemap/0.9"}
_MAX_CONCURRENCY = 5
_TITLE_STRIP_SUFFIXES = [
    " | 채용",
    " - 채용",
    " | 채용공고",
    " | Jobs",
    " | Careers",
    " | 경력채용",
    " | 신입채용",
]


# ── CUSTOM parser interface ───────────────────────────────────────────────────


class CustomParser(ABC):
    """Abstract interface for per-company custom parsers.

    Register an instance in _CUSTOM_REGISTRY_BY_KEY (keyed by parser_key) to
    activate it without modifying the dispatcher.  The parser receives the full
    OfficialCompanySource (including config_json) so it can read max_discover /
    max_fetch or any other config it needs.

    Minimum contract:
      Input:  source (OfficialCompanySource), profile (CompanyProfile | None),
              options (CollectionOptions), collect_date (date).
              Create an httpx.AsyncClient inside fetch() as needed.
      Output: AdapterResult
    """

    @property
    def company_id(self) -> int | None:
        """DEPRECATED: used only by the legacy _CUSTOM_REGISTRY (company_id-keyed).
        New parsers registered in _CUSTOM_REGISTRY_BY_KEY do not need to override this.
        """
        return None

    @abstractmethod
    async def fetch(
        self,
        source: OfficialCompanySource,
        profile: CompanyProfile | None,
        options: CollectionOptions,
        collect_date: date,
    ) -> AdapterResult: ...


# Primary registry: parser_key (str, uppercase) → CustomParser instance.
# Key must match config_json["parser_key"] after strip + uppercase.
# Empty by default — add entries as companies are onboarded.
_CUSTOM_REGISTRY_BY_KEY: dict[str, CustomParser] = {}

# DEPRECATED legacy registry: company_id (int) → CustomParser instance.
# Kept for backward compatibility when config_json is absent (null/empty).
# Prefer _CUSTOM_REGISTRY_BY_KEY + config_json parser_key for new parsers.
_CUSTOM_REGISTRY: dict[int, CustomParser] = {}


# ── config helpers ────────────────────────────────────────────────────────────


def _extract_parser_key(config_json: str | None) -> tuple[str | None, str | None]:
    """Extract and normalize the parser_key from a CUSTOM source's config_json.

    Returns ``(parser_key, error)`` where:

    * ``parser_key`` is the normalized key (stripped + uppercase), or ``None``.
    * ``error`` is one of ``None | "invalid_json" | "missing_key" | "empty_key"``.

    Decision table:

    =================== ========== ==========
    config_json         parser_key error
    =================== ========== ==========
    None or ""          None       None       → caller should try legacy registry
    invalid JSON        None       invalid_json
    valid JSON, no key  None       missing_key
    valid JSON, key=""  None       empty_key
    valid JSON, key="x" "X"        None
    =================== ========== ==========
    """
    if not config_json or not config_json.strip():
        return None, None
    try:
        data = json.loads(config_json)
    except json.JSONDecodeError:
        return None, "invalid_json"

    raw_key = data.get("parser_key")
    if raw_key is None:
        return None, "missing_key"
    if not isinstance(raw_key, str) or not raw_key.strip():
        return None, "empty_key"
    return raw_key.strip().upper(), None


@dataclass
class _SitemapConfig:
    max_discover: int = 50
    max_fetch: int = 20


@dataclass
class _RssConfig:
    max_items: int = 50


def _parse_sitemap_config(config_json: str | None) -> _SitemapConfig:
    if not config_json:
        return _SitemapConfig()
    try:
        data = json.loads(config_json)
        return _SitemapConfig(
            max_discover=int(data.get("max_discover", 50)),
            max_fetch=int(data.get("max_fetch", 20)),
        )
    except Exception:
        return _SitemapConfig()


def _parse_rss_config(config_json: str | None) -> _RssConfig:
    if not config_json:
        return _RssConfig()
    try:
        data = json.loads(config_json)
        return _RssConfig(max_items=int(data.get("max_items", 50)))
    except Exception:
        return _RssConfig()


# ── XML / HTML utilities ──────────────────────────────────────────────────────


def _strip_ns(tag: str) -> str:
    """Remove XML namespace prefix: {uri}local → local."""
    return tag.split("}")[-1] if "}" in tag else tag


def _find_text(el: ET.Element, local_name: str) -> str | None:
    for child in el:
        if _strip_ns(child.tag) == local_name:
            return child.text.strip() if child.text else None
    return None


def _find_link(el: ET.Element) -> str | None:
    """Extract URL from RSS <link> (text) or Atom <link href=""> (attr)."""
    for child in el:
        if _strip_ns(child.tag) == "link":
            if child.text and child.text.strip():
                return child.text.strip()
            href = child.get("href")
            if href:
                return href
    return None


def _parse_lastmod(value: str | None) -> date | None:
    if not value:
        return None
    try:
        return datetime.fromisoformat(value.strip()).date()
    except ValueError:
        return None


def _parse_rss_date(value: str | None) -> datetime | None:
    if not value:
        return None
    value = value.strip()
    try:
        return datetime.fromisoformat(value)
    except ValueError:
        pass
    try:
        return parsedate_to_datetime(value)
    except Exception:
        return None


def _extract_deadline_generic(text: str) -> date | None:
    matches = _KR_DATE_RE.findall(text)
    if not matches:
        return None
    year, month, day = matches[-1]
    try:
        return date(int(year), int(month), int(day))
    except ValueError:
        return None


# ── sitemap XML parsing ───────────────────────────────────────────────────────


def parse_sitemap_xml(xml_text: str, cutoff: date) -> list[tuple[date, str]]:
    """Parse sitemap XML and return (lastmod, url) pairs within the lookback window.

    Unknown-lastmod URLs are included (conservative) with date.min so they
    sort after known-date entries.
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
        lastmod = _parse_lastmod(
            lastmod_el.text if lastmod_el is not None else None
        )
        if lastmod is None or lastmod >= cutoff:
            results.append((lastmod or date.min, loc))
    return results


# ── generic page extraction ───────────────────────────────────────────────────


def parse_page_generic(
    html: str,
    url: str,
    company_name: str,
    source_id: str,
) -> RawJobPosting | None:
    """Extract a job posting from an arbitrary career-site page.

    Title comes from <h1> (preferred) or <title> tag (fallback) with
    common suffix stripping.  Deadline and employment type use heuristics.
    Returns None when no title can be found.
    """
    soup = BeautifulSoup(html, "lxml")

    title: str | None = None
    h1 = soup.find("h1")
    if h1:
        text = h1.get_text(separator=" ", strip=True)
        if text and len(text) < 200:
            title = text

    if not title:
        title_tag = soup.find("title")
        if title_tag:
            text = title_tag.get_text(strip=True)
            for suffix in _TITLE_STRIP_SUFFIXES:
                if text.endswith(suffix):
                    text = text[: -len(suffix)]
            text = text.strip()
            if text:
                title = text

    if not title:
        return None

    page_text = soup.get_text()
    employment_type: str | None = None
    for keyword in _EMPLOYMENT_KEYWORDS:
        if keyword in page_text:
            employment_type = keyword
            break

    return RawJobPosting(
        source=source_id,
        source_url=url,
        company_name=company_name,
        title=title,
        employment_type=employment_type,
        deadline=_extract_deadline_generic(page_text),
        skills=[],
        roles=[],
    )


# ── RSS / Atom feed parsing ───────────────────────────────────────────────────


def parse_rss_feed(
    xml_text: str,
    company_name: str,
    source_id: str,
    max_items: int = 50,
) -> list[RawJobPosting]:
    """Parse RSS 2.0 or Atom 1.0 feed into RawJobPosting list.

    Only standard feed fields are mapped.  Items missing both title and link
    are skipped.  Raises ValueError for unparseable XML.
    """
    try:
        root = ET.fromstring(xml_text)
    except ET.ParseError as exc:
        raise ValueError(f"invalid RSS/Atom XML: {exc}") from exc

    postings: list[RawJobPosting] = []

    # ── RSS 2.0 ──
    if _strip_ns(root.tag) == "rss":
        channel = root.find("channel")
        if channel is None:
            return []
        for item in channel.findall("item")[:max_items]:
            title = _find_text(item, "title")
            link = _find_link(item)
            if not title or not link:
                continue
            postings.append(
                RawJobPosting(
                    source=source_id,
                    source_url=link,
                    company_name=company_name,
                    title=title,
                    description=_find_text(item, "description"),
                    posted_at=_parse_rss_date(_find_text(item, "pubDate")),
                    skills=[],
                    roles=[],
                )
            )
        return postings

    # ── Atom 1.0 ──
    entries = [child for child in root if _strip_ns(child.tag) == "entry"]
    for entry in entries[:max_items]:
        title = _find_text(entry, "title")
        link = _find_link(entry)
        if not title or not link:
            continue
        summary: str | None = None
        for child in entry:
            if _strip_ns(child.tag) in ("summary", "content"):
                summary = child.text.strip() if child.text else None
                break
        pub_text: str | None = None
        for child in entry:
            if _strip_ns(child.tag) in ("published", "updated"):
                pub_text = child.text.strip() if child.text else None
                break
        postings.append(
            RawJobPosting(
                source=source_id,
                source_url=link,
                company_name=company_name,
                title=title,
                description=summary,
                posted_at=_parse_rss_date(pub_text),
                skills=[],
                roles=[],
            )
        )
    return postings


# ── source ID helper ──────────────────────────────────────────────────────────


def _source_id(source: OfficialCompanySource) -> str:
    """Unique source identifier for a specific company+adapterType combination."""
    return f"company_{source.company_id}_{(source.adapter_type or 'unknown').lower()}"


# ── per-source async handlers ─────────────────────────────────────────────────


async def _handle_sitemap(
    source: OfficialCompanySource,
    profile: CompanyProfile | None,
    options: CollectionOptions,
    collect_date: date,
    timeout: int,
    warnings: list[str],
) -> AdapterResult:
    if not source.source_url:
        warnings.append(
            f"official_company: SITEMAP source has no source_url"
            f" (company_id={source.company_id})"
        )
        return AdapterResult(source_stats=AdapterSourceStats())

    company_name = (
        profile.canonical_name if profile else f"company_{source.company_id}"
    )
    config = _parse_sitemap_config(source.config_json)
    sid = _source_id(source)
    cutoff = collect_date - timedelta(days=options.lookback_days)

    try:
        async with AsyncClient(
            timeout=timeout,
            headers={"User-Agent": "Briefy-Agent/1.0 (+https://briefy.io)"},
            follow_redirects=True,
        ) as client:
            try:
                resp = await client.get(source.source_url)
                resp.raise_for_status()
                entries = parse_sitemap_xml(resp.text, cutoff)
            except TimeoutException:
                warnings.append(
                    f"official_company: sitemap timeout"
                    f" company_id={source.company_id}"
                )
                return AdapterResult(source_stats=AdapterSourceStats())
            except HTTPStatusError as exc:
                warnings.append(
                    f"official_company: sitemap HTTP {exc.response.status_code}"
                    f" company_id={source.company_id}"
                )
                return AdapterResult(source_stats=AdapterSourceStats())
            except ValueError as exc:
                warnings.append(
                    f"official_company: sitemap parse error"
                    f" company_id={source.company_id}: {exc}"
                )
                return AdapterResult(source_stats=AdapterSourceStats())

            discovered = len(entries)
            entries.sort(key=lambda t: t[0], reverse=True)
            to_fetch = entries[: config.max_fetch]

            semaphore = asyncio.Semaphore(_MAX_CONCURRENCY)
            tasks = [
                _fetch_page(client, url, company_name, sid, semaphore, warnings)
                for _, url in to_fetch
            ]
            postings = [
                p for p in await asyncio.gather(*tasks) if p is not None
            ]

    except Exception as exc:
        msg = (
            f"official_company: unexpected SITEMAP error"
            f" company_id={source.company_id}: {exc}"
        )
        log.warning(msg)
        warnings.append(msg)
        return AdapterResult(source_stats=AdapterSourceStats())

    stats = AdapterSourceStats(
        discovered=discovered,
        fetched=len(to_fetch),
        parsed=len(postings),
        selected=len(postings),
    )
    return AdapterResult(postings=postings, source_stats=stats)


async def _fetch_page(
    client: AsyncClient,
    url: str,
    company_name: str,
    source_id: str,
    semaphore: asyncio.Semaphore,
    warnings: list[str],
) -> RawJobPosting | None:
    async with semaphore:
        try:
            resp = await client.get(url)
            resp.raise_for_status()
            return parse_page_generic(resp.text, url, company_name, source_id)
        except TimeoutException:
            warnings.append(f"official_company: page timeout: {url}")
        except HTTPStatusError as exc:
            warnings.append(
                f"official_company: page HTTP {exc.response.status_code}: {url}"
            )
        except Exception as exc:
            warnings.append(f"official_company: page error {url}: {exc}")
    return None


async def _handle_rss(
    source: OfficialCompanySource,
    profile: CompanyProfile | None,
    options: CollectionOptions,
    collect_date: date,
    timeout: int,
    warnings: list[str],
) -> AdapterResult:
    if not source.source_url:
        warnings.append(
            f"official_company: RSS source has no source_url"
            f" (company_id={source.company_id})"
        )
        return AdapterResult(source_stats=AdapterSourceStats())

    company_name = (
        profile.canonical_name if profile else f"company_{source.company_id}"
    )
    config = _parse_rss_config(source.config_json)
    sid = _source_id(source)

    try:
        async with AsyncClient(
            timeout=timeout,
            headers={"User-Agent": "Briefy-Agent/1.0 (+https://briefy.io)"},
            follow_redirects=True,
        ) as client:
            try:
                resp = await client.get(source.source_url)
                resp.raise_for_status()
                postings = parse_rss_feed(
                    resp.text, company_name, sid, config.max_items
                )
            except TimeoutException:
                warnings.append(
                    f"official_company: RSS timeout"
                    f" company_id={source.company_id}"
                )
                return AdapterResult(source_stats=AdapterSourceStats())
            except HTTPStatusError as exc:
                warnings.append(
                    f"official_company: RSS HTTP {exc.response.status_code}"
                    f" company_id={source.company_id}"
                )
                return AdapterResult(source_stats=AdapterSourceStats())
            except ValueError as exc:
                warnings.append(
                    f"official_company: RSS parse error"
                    f" company_id={source.company_id}: {exc}"
                )
                return AdapterResult(source_stats=AdapterSourceStats())
    except Exception as exc:
        msg = (
            f"official_company: unexpected RSS error"
            f" company_id={source.company_id}: {exc}"
        )
        log.warning(msg)
        warnings.append(msg)
        return AdapterResult(source_stats=AdapterSourceStats())

    stats = AdapterSourceStats(
        discovered=len(postings),
        fetched=1,
        parsed=len(postings),
        selected=len(postings),
    )
    return AdapterResult(postings=postings, source_stats=stats)


# ── Dispatcher ────────────────────────────────────────────────────────────────


class OfficialCompanyAdapter(JobBoardAdapter):
    """Routes each OfficialCompanySource to SITEMAP, RSS, or CUSTOM handler.

    Constructed with the sources and profiles extracted from DailyCollectRequest.
    The adapter itself is stateless across collection runs.
    """

    def __init__(
        self,
        sources: list[OfficialCompanySource],
        profiles: list[CompanyProfile],
    ) -> None:
        self._sources = sources
        self._profile_map: dict[int, CompanyProfile] = {p.id: p for p in profiles}

    @property
    def source_name(self) -> str:
        return "official_company"

    async def fetch(
        self,
        seed_keywords: SeedKeywords,
        options: CollectionOptions,
        collect_date: date | None = None,
    ) -> AdapterResult:
        if collect_date is None:
            collect_date = date.today()

        timeout = settings.job_collection_timeout_seconds
        all_postings: list[RawJobPosting] = []
        all_warnings: list[str] = []
        agg = AdapterSourceStats()

        for source in self._sources:
            profile = self._profile_map.get(source.company_id)
            try:
                result = await self._dispatch(
                    source, profile, options, collect_date, timeout
                )
            except Exception as exc:
                msg = (
                    f"official_company: unexpected error"
                    f" company_id={source.company_id}: {exc}"
                )
                log.warning(msg)
                all_warnings.append(msg)
                continue

            all_postings.extend(result.postings)
            all_warnings.extend(result.warnings)
            if result.source_stats:
                agg.discovered += result.source_stats.discovered
                agg.fetched += result.source_stats.fetched
                agg.parsed += result.source_stats.parsed
                agg.selected += result.source_stats.selected

        log.info(
            "official_company: sources=%d discovered=%d fetched=%d"
            " parsed=%d selected=%d",
            len(self._sources),
            agg.discovered,
            agg.fetched,
            agg.parsed,
            agg.selected,
        )
        return AdapterResult(
            postings=all_postings,
            warnings=all_warnings,
            source_stats=agg,
        )

    async def _dispatch(
        self,
        source: OfficialCompanySource,
        profile: CompanyProfile | None,
        options: CollectionOptions,
        collect_date: date,
        timeout: int,
    ) -> AdapterResult:
        adapter_type = (source.adapter_type or "").upper()
        extra_warnings: list[str] = []

        if adapter_type == "SITEMAP":
            result = await _handle_sitemap(
                source, profile, options, collect_date, timeout, extra_warnings
            )
        elif adapter_type == "RSS":
            result = await _handle_rss(
                source, profile, options, collect_date, timeout, extra_warnings
            )
        elif adapter_type == "CUSTOM":
            parser_key, key_error = _extract_parser_key(source.config_json)

            if parser_key is not None:
                # Primary path: parser_key → _CUSTOM_REGISTRY_BY_KEY
                ck_parser = _CUSTOM_REGISTRY_BY_KEY.get(parser_key)
                if ck_parser is not None:
                    result = await ck_parser.fetch(
                        source, profile, options, collect_date
                    )
                else:
                    extra_warnings.append(
                        f"official_company: no custom parser registered for"
                        f" parser_key={parser_key}"
                        f" (company_id={source.company_id})"
                    )
                    result = AdapterResult(source_stats=AdapterSourceStats())

            elif key_error is not None:
                # config_json was present but broken (invalid JSON / missing key)
                if key_error == "invalid_json":
                    extra_warnings.append(
                        f"official_company: invalid config_json for"
                        f" company_id={source.company_id}"
                    )
                else:  # missing_key or empty_key
                    extra_warnings.append(
                        f"official_company: missing custom parser_key for"
                        f" company_id={source.company_id}"
                    )
                result = AdapterResult(source_stats=AdapterSourceStats())

            else:
                # config_json is null/empty: legacy company_id lookup (DEPRECATED)
                legacy_parser = _CUSTOM_REGISTRY.get(source.company_id)
                if legacy_parser is not None:
                    result = await legacy_parser.fetch(
                        source, profile, options, collect_date
                    )
                else:
                    extra_warnings.append(
                        f"official_company: no custom parser registered for"
                        f" company_id={source.company_id}"
                    )
                    result = AdapterResult(source_stats=AdapterSourceStats())
        else:
            extra_warnings.append(
                f"official_company: unsupported adapterType='{source.adapter_type}'"
                f" company_id={source.company_id}"
            )
            result = AdapterResult(source_stats=AdapterSourceStats())

        if extra_warnings:
            return AdapterResult(
                postings=result.postings,
                warnings=result.warnings + extra_warnings,
                source_stats=result.source_stats,
            )
        return result
