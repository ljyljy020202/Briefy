"""AblyCareersParser — custom parser for ably.team official career site.

Registered as "ABLY_CAREERS".

Collection flow:
  1. GET https://ably.team/recruit
     SSR page embeds the full listing in an inline <script> tag as
     {"props":{"pageProps":{"recruits":[...],...}}}.  No separate API.
  2. Filter: status == "in_progress" and isPrivate == false.
  3. Extract addressKey from applyUrl path; deduplicate; cap at max_items.
  4. Construct canonical URL: https://recruit.ably.team/job_posting/{addressKey}
  5. Fetch each detail page concurrently (semaphore-limited):
       https://recruit.ably.team/job_posting/{addressKey}
     Extract __NEXT_DATA__ → pageProps.jobPosting.content (HTML description).
  6. Build RawJobPosting from listing metadata + stripped description.

config_json:
  {
    "parser_key": "ABLY_CAREERS",
    "max_items": 100         // detail-fetch cap (default 100)
  }

Deadline semantics:
  deadlineType == "open_ended"   → deadline = None (상시채용)
  deadlineType == "until_filled" → deadline = None (채용 시 마감)
  deadline field non-null        → parsed as YYYY-MM-DD date

Career field mapping (from listing):
  career == "newcomer"            → "신입"
  career == "irrelevant"          → "경력 무관"
  career == "experienced":
    over > 0 and below > 0        → "경력 N~M년"
    over > 0 only                 → "경력 N년 이상"
    below > 0 only                → "경력 N년 이하"   (NOT "이상" — preserved as-is)
    neither                       → "경력"
  career == null                  → None

Intern / newcomer distinction:
  career and employmentType are independent fields.
  e.g. title "데이터 분석가 (채용 연계형 인턴)" with career="newcomer"
       + employmentTypes=["intern"]
       → experience_level="신입", employment_type="인턴"  (not conflated)

Roles:
  roles = [jobGroup]  — original Department preserved verbatim.
  No title-derived sub-role inference for non-Engineering departments.

robots.txt:
  ably.team does not publish /robots.txt at implementation time.
  The public career listing is the intended publicly accessible page.
"""

import asyncio
import json
import logging
import re
from dataclasses import dataclass
from datetime import date, datetime
from typing import Any
from urllib.parse import urlparse

from bs4 import BeautifulSoup
from httpx import AsyncClient, HTTPStatusError, TimeoutException

from app.adapters.base import AdapterResult, AdapterSourceStats, RawJobPosting
from app.adapters.official_company import _CUSTOM_REGISTRY_BY_KEY, CustomParser
from app.core.config import settings
from app.schemas.collection import (
    CollectionOptions,
    CompanyProfile,
    OfficialCompanySource,
)

log = logging.getLogger(__name__)

_LIST_URL = "https://ably.team/recruit"
_DETAIL_URL_TMPL = "https://recruit.ably.team/job_posting/{address_key}"
_SOURCE = "ably_careers"

_DEFAULT_MAX_ITEMS = 100
_SEMAPHORE_LIMIT = 5

_EMP_TYPE_MAP: dict[str, str] = {
    "full_time": "정규직",
    "intern": "인턴",
    "contractor": "계약직",
}


# ── Config ────────────────────────────────────────────────────────────────────


@dataclass
class _AblyConfig:
    max_items: int


def _parse_config(config_json: str | None) -> _AblyConfig:
    data: dict = {}
    if config_json:
        try:
            data = json.loads(config_json)
        except Exception:
            pass
    max_items = int(data.get("max_items") or _DEFAULT_MAX_ITEMS)
    return _AblyConfig(max_items=max_items)


# ── List page parsing ─────────────────────────────────────────────────────────


def _parse_list_html(html: str) -> list[dict]:
    """Extract recruits[] from ably.team/recruit SSR inline script.

    The page contains one <script> tag whose body IS a JSON object:
      {"props":{"pageProps":{"recruits":[...],...}}}

    Returns a (possibly empty) list on success.
    Raises ValueError for parse failures (malformed JSON or missing key).
    """
    scripts = re.findall(r"<script[^>]*>(.*?)</script>", html, re.DOTALL)
    for raw in scripts:
        s = raw.strip()
        if '"recruits"' not in s or '"pageProps"' not in s:
            continue
        try:
            data = json.loads(s)
            recruits = data["props"]["pageProps"]["recruits"]
            if isinstance(recruits, list):
                return recruits
        except (json.JSONDecodeError, KeyError):
            continue
    raise ValueError("pageProps.recruits not found in page HTML")


# ── Detail page parsing ───────────────────────────────────────────────────────


def _extract_detail_content(html: str) -> str | None:
    """Extract jobPosting.content HTML from __NEXT_DATA__ on a detail page."""
    m = re.search(
        r'<script[^>]+id=["\']__NEXT_DATA__["\'][^>]*>(.*?)</script>',
        html,
        re.DOTALL,
    )
    if not m:
        return None
    try:
        data = json.loads(m.group(1))
        content = data["props"]["pageProps"]["jobPosting"].get("content") or ""
        return content if isinstance(content, str) else None
    except (json.JSONDecodeError, KeyError, TypeError):
        return None


def _html_to_text(content_html: str) -> str | None:
    """Strip HTML tags and return plain text (up to 3000 chars)."""
    if not content_html:
        return None
    try:
        soup = BeautifulSoup(content_html, "html.parser")
        text = soup.get_text(separator="\n", strip=True)
        return text[:3000] if text else None
    except Exception:
        return None


# ── Field extractors ──────────────────────────────────────────────────────────


def _extract_address_key(apply_url: str) -> str | None:
    """Extract base62 addressKey from ninehire applyUrl.

    'https://tydtr0dj.ninehire.site/job_posting/1Ni2VkMj' → '1Ni2VkMj'
    Returns None if URL format is unexpected.
    """
    try:
        path = urlparse(apply_url).path.rstrip("/")
        parts = path.split("/")
        if len(parts) >= 2 and parts[-2] == "job_posting":
            key = parts[-1]
            return key if key else None
    except Exception:
        pass
    return None


def _career_to_experience(
    career: str | None, career_range: dict | None
) -> str | None:
    """Convert Ably career fields to a normalized experience_level string.

    'below only' → "N년 이하"  (never flipped to "이상").
    """
    if career == "newcomer":
        return "신입"
    if career == "irrelevant":
        return "경력 무관"
    if career == "experienced":
        over = int((career_range or {}).get("over") or 0)
        below = int((career_range or {}).get("below") or 0)
        if over > 0 and below > 0:
            return f"경력 {over}~{below}년"
        if over > 0:
            return f"경력 {over}년 이상"
        if below > 0:
            return f"경력 {below}년 이하"
        return "경력"
    return None


def _employment_type(emp_types: list[str]) -> str | None:
    """Return the primary employment_type string."""
    for t in emp_types:
        mapped = _EMP_TYPE_MAP.get(t)
        if mapped:
            return mapped
    return None


def _deadline(item: dict[str, Any]) -> date | None:
    """Parse deadline date; None for open_ended / until_filled."""
    raw = item.get("deadline")
    if not raw:
        return None
    try:
        return date.fromisoformat(str(raw)[:10])
    except Exception:
        return None


def _location(item: dict[str, Any]) -> str | None:
    locs = item.get("jobLocations") or []
    if locs:
        return str(locs[0].get("name") or "").strip() or None
    return None


def _roles(item: dict[str, Any]) -> list[str]:
    group = str(item.get("jobGroup") or "").strip()
    return [group] if group else []


def _posted_at(item: dict[str, Any]) -> datetime | None:
    raw = item.get("createdAt")
    if not raw:
        return None
    try:
        return datetime.fromisoformat(str(raw).replace("Z", "+00:00"))
    except Exception:
        return None


# ── RawJobPosting builder ─────────────────────────────────────────────────────


def _build_posting(
    item: dict[str, Any],
    company_name: str,
    address_key: str,
    description: str | None,
) -> RawJobPosting:
    return RawJobPosting(
        source=_SOURCE,
        source_url=_DETAIL_URL_TMPL.format(address_key=address_key),
        company_name=company_name,
        title=str(item.get("title") or "").strip(),
        employment_type=_employment_type(item.get("employmentTypes") or []),
        experience_level=_career_to_experience(
            item.get("career"), item.get("careerRange")
        ),
        location=_location(item),
        deadline=_deadline(item),
        roles=_roles(item),
        description=description,
        posted_at=_posted_at(item),
        source_external_id=address_key,
    )


# ── Parser ────────────────────────────────────────────────────────────────────


class AblyCareersParser(CustomParser):
    PARSER_KEY = "ABLY_CAREERS"

    async def fetch(
        self,
        source: OfficialCompanySource,
        profile: CompanyProfile | None,
        options: CollectionOptions,
        collect_date: date | None = None,
    ) -> AdapterResult:
        config = _parse_config(source.config_json)
        sid = f"company_{source.company_id}_custom_ably_careers"
        company_name = (profile.canonical_name if profile else None) or "에이블리"
        warnings: list[str] = []
        timeout = settings.job_collection_timeout_seconds

        async with AsyncClient(
            timeout=timeout,
            headers={
                "User-Agent": (
                    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                    "AppleWebKit/537.36 (KHTML, like Gecko) "
                    "Chrome/124.0.0.0 Safari/537.36"
                ),
                "Accept-Language": "ko-KR,ko;q=0.9",
            },
            follow_redirects=True,
        ) as client:
            # ── Phase 1: listing ──────────────────────────────────────────────
            try:
                list_resp = await client.get(_LIST_URL)
                list_resp.raise_for_status()
            except TimeoutException:
                warnings.append(f"ably_careers: listing timeout [{sid}]")
                return AdapterResult(
                    warnings=warnings, source_stats=AdapterSourceStats()
                )
            except HTTPStatusError as exc:
                warnings.append(
                    f"ably_careers: listing HTTP"
                    f" {exc.response.status_code} [{sid}]"
                )
                return AdapterResult(
                    warnings=warnings, source_stats=AdapterSourceStats()
                )
            except Exception as exc:
                warnings.append(
                    f"ably_careers: listing error [{sid}]: {exc}"
                )
                return AdapterResult(
                    warnings=warnings, source_stats=AdapterSourceStats()
                )

            try:
                all_recruits = _parse_list_html(list_resp.text)
            except ValueError as exc:
                warnings.append(f"ably_careers: {exc} [{sid}]")
                return AdapterResult(
                    warnings=warnings, source_stats=AdapterSourceStats()
                )

            active = [
                r for r in all_recruits
                if r.get("status") == "in_progress"
                and not r.get("isPrivate", False)
            ]

            # Deduplicate by addressKey
            seen: set[str] = set()
            candidates: list[tuple[dict, str]] = []
            for item in active:
                apply_url = item.get("applyUrl") or ""
                key = _extract_address_key(apply_url)
                if not key or key in seen:
                    continue
                seen.add(key)
                candidates.append((item, key))

            discovered = len(candidates)
            candidates = candidates[: config.max_items]

            log.info(
                "ably_careers: discovered=%d capped=%d [%s]",
                discovered,
                len(candidates),
                sid,
            )

            stats = AdapterSourceStats(discovered=discovered)

            # ── Phase 2: detail pages (description) ───────────────────────────
            sem = asyncio.Semaphore(_SEMAPHORE_LIMIT)

            async def _fetch_detail(
                item: dict, address_key: str
            ) -> RawJobPosting | None:
                detail_url = _DETAIL_URL_TMPL.format(address_key=address_key)
                description: str | None = None
                try:
                    async with sem:
                        resp = await client.get(detail_url)
                        resp.raise_for_status()
                    stats.fetched += 1
                    content_html = _extract_detail_content(resp.text)
                    description = _html_to_text(content_html or "")
                except TimeoutException:
                    warnings.append(
                        f"ably_careers: detail timeout {address_key}"
                    )
                    stats.fetched += 1
                except HTTPStatusError as exc:
                    warnings.append(
                        f"ably_careers: detail HTTP"
                        f" {exc.response.status_code} {address_key}"
                    )
                    stats.fetched += 1
                except Exception as exc:
                    warnings.append(
                        f"ably_careers: detail error {address_key}: {exc}"
                    )

                posting = _build_posting(item, company_name, address_key, description)
                if not posting.title:
                    warnings.append(
                        f"ably_careers: empty title {address_key}"
                    )
                    return None
                stats.parsed += 1
                return posting

            results = await asyncio.gather(
                *[_fetch_detail(item, key) for item, key in candidates]
            )
            postings = [p for p in results if p is not None]
            stats.selected = len(postings)

        log.info(
            "ably_careers: discovered=%d fetched=%d parsed=%d selected=%d [%s]",
            stats.discovered,
            stats.fetched,
            stats.parsed,
            stats.selected,
            sid,
        )
        return AdapterResult(postings=postings, warnings=warnings, source_stats=stats)


# ── Self-registration ─────────────────────────────────────────────────────────

_CUSTOM_REGISTRY_BY_KEY["ABLY_CAREERS"] = AblyCareersParser()


# ── Preflight ─────────────────────────────────────────────────────────────────


async def ably_preflight(source: OfficialCompanySource) -> dict:
    """Bounded preflight probe for ABLY_CAREERS."""
    timeout = settings.job_collection_timeout_seconds
    warnings: list[str] = []

    try:
        async with AsyncClient(
            timeout=timeout,
            headers={
                "User-Agent": (
                    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                    "AppleWebKit/537.36 (KHTML, like Gecko) "
                    "Chrome/124.0.0.0 Safari/537.36"
                ),
                "Accept-Language": "ko-KR,ko;q=0.9",
            },
            follow_redirects=True,
        ) as client:
            resp = await client.get(_LIST_URL)
            resp.raise_for_status()
    except Exception as exc:
        return {
            "reachable": False,
            "failure_code": "URL_UNREACHABLE",
            "failure_message": str(exc),
            "warnings": warnings,
        }

    try:
        recruits = _parse_list_html(resp.text)
    except ValueError as exc:
        return {
            "reachable": True,
            "failure_code": "PARSE_FAILED",
            "failure_message": str(exc),
            "warnings": warnings,
        }

    active = [
        r for r in recruits
        if r.get("status") == "in_progress" and not r.get("isPrivate", False)
    ]
    discovered_count = len(active)
    sample_parsed: dict | None = None

    if active:
        item = active[0]
        key = _extract_address_key(item.get("applyUrl") or "")
        if key:
            posting = _build_posting(item, "에이블리", key, None)
            sample_parsed = {
                "title": posting.title,
                "roles": posting.roles,
                "experience_level": posting.experience_level,
                "employment_type": posting.employment_type,
                "location": posting.location,
                "source_external_id": posting.source_external_id,
            }

    return {
        "reachable": True,
        "discovered_count": discovered_count,
        "sample_parsed": sample_parsed,
        "warnings": warnings,
    }
