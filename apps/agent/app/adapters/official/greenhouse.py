"""GreenhouseParser — reusable custom parser for Greenhouse job board API.

Registered as:
  "GREENHOUSE"     — generic; requires board_slug in config_json
  "DAANGN_CAREERS" — alias; defaults board_slug="daangn",
                   career URL to careers.daangn.com

API contract (no pagination — list returns all jobs in one response):
  GET https://boards-api.greenhouse.io/v1/boards/{board_slug}/jobs?content=true
  Response: {"jobs": [...], "meta": {"total": N}}

config_json:
  {
    "parser_key": "GREENHOUSE",
    "board_slug": "daangn",      // required for GREENHOUSE; auto-set for known aliases
    "max_items": 200,             // cap on postings returned (default 200)
    "career_url_tmpl": "https://careers.daangn.com/jobs/role/{job_id}/"
  }

Roles strategy:
  roles = [Division from metadata] + [departments[].name], deduped.
  Division gives the broad category (Tech, Business, Design, Product Management).
  departments[].name gives the specific role (Software Engineer, Backend; Sales; etc.).

Company / legal entity:
  metadata.Corporate → company_name (preserves 당근 vs 당근마켓 vs 당근페이).
  Falls back to profile.canonical_name if metadata.Corporate is absent.
  The backend alias table resolves company_id from company_name.

Experience enrichment:
  metadata.Prior Experience → initial value ("신입", "경력", "신입/경력").
  Content year-range/minimum patterns extracted from required-qualification
  sections only (not from preferred/nice-to-have sections).

Deadline:
  metadata.Valid Through "" or null → None (상시채용).
  Non-empty → parsed as YYYY-MM-DD date.
"""

import html
import json
import logging
import re
from dataclasses import dataclass
from datetime import date, datetime

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

_GH_LIST_URL = "https://boards-api.greenhouse.io/v1/boards/{board_slug}/jobs"

# Per-board defaults that are NOT required in config_json.
_BOARD_DEFAULTS: dict[str, dict] = {
    "daangn": {
        "career_url_tmpl": "https://careers.daangn.com/jobs/role/{job_id}/",
    },
}

# parser_key → board_slug auto-inference for known aliases.
_KEY_TO_SLUG: dict[str, str] = {
    "DAANGN_CAREERS": "daangn",
}

_DEFAULT_MAX_ITEMS = 200

# ── Field value mappings ──────────────────────────────────────────────────────

_PRIOR_EXP_MAP: dict[str, str] = {
    "신입": "신입",
    "경력": "경력",
    "신입/경력": "신입/경력",
    "경력무관": "경력 무관",
    "무관": "경력 무관",
}

_EMP_TYPE_MAP: dict[str, str] = {
    "정규직": "정규직",
    "계약직": "계약직",
    "인턴": "인턴",
    "파견직": "파견직",
    "프리랜서": "프리랜서",
}

_LOCATION_NORMALIZE: dict[str, str] = {
    "SEOUL": "서울",
    "Seoul": "서울",
    "서울": "서울",
    "BUSAN": "부산",
    "REMOTE": "원격",
    "Remote": "원격",
}

# ── Experience year extraction ────────────────────────────────────────────────

_EXP_YEAR_RANGE_RE = re.compile(r"경력\s*(\d+)\s*년\s*이상\s*(\d+)\s*년\s*이하")
_EXP_TILDE_RANGE_RE = re.compile(r"(\d+)\s*[~～&]\s*(?:sim;)?(\d+)\s*년")
_EXP_MIN_RE = re.compile(r"(\d+)\s*년\s*이상")

# Headings that mark required-qualifications sections in job content.
_REQUIRED_HEADINGS: frozenset[str] = frozenset(
    {
        "이런 분을 찾고 있어요",
        "이런 분과 함께하고 싶어요",
        "이런 분이에요",
        "지원 자격",
        "자격 요건",
        "필수 요건",
        "필요 역량",
    }
)

# Headings that mark preferred/nice-to-have sections — do NOT extract from these.
_PREFERRED_HEADINGS: frozenset[str] = frozenset(
    {
        "이런 분이면 더 좋아요",
        "이런 분이면 더 좋아요!",
        "우대 사항",
        "우대사항",
        "우대 조건",
        "이런 경험이 있으면 더 좋아요",
    }
)


def _extract_year_experience(text: str) -> str | None:
    """Extract experience year range/minimum from required-section text.

    Priority order:
      1. "경력 N년 이상 M년 이하" → "경력 N~M년"
      2. "N~M년" (tilde range, including HTML entity &sim;) → "경력 N~M년"
      3. "N년 이상" → "경력 N년 이상"
    Returns None when no explicit pattern found.
    """
    m = _EXP_YEAR_RANGE_RE.search(text)
    if m:
        return f"경력 {m.group(1)}~{m.group(2)}년"
    m = _EXP_TILDE_RANGE_RE.search(text)
    if m:
        return f"경력 {m.group(1)}~{m.group(2)}년"
    m = _EXP_MIN_RE.search(text)
    if m:
        return f"경력 {m.group(1)}년 이상"
    return None


# ── HTML content parsing ──────────────────────────────────────────────────────


def _html_to_description_and_exp(raw_content: str) -> tuple[str | None, str | None]:
    """Parse Greenhouse HTML-encoded content field.

    Returns (description_text, inferred_experience_level).

    The content field uses HTML entities twice — html.unescape() first,
    then BeautifulSoup strips tags.

    Experience is extracted ONLY from headings in _REQUIRED_HEADINGS,
    never from _PREFERRED_HEADINGS sections.
    """
    if not raw_content:
        return None, None

    decoded = html.unescape(raw_content)
    soup = BeautifulSoup(decoded, "html.parser")

    all_text_parts: list[str] = []
    required_text_parts: list[str] = []
    in_required = False

    # Walk top-level block elements; treat h2/h3/h4 as section boundaries.
    for el in soup.find_all(["h1", "h2", "h3", "h4", "p", "ul", "ol", "div", "hr"]):
        tag = el.name
        if tag in ("h1", "h2", "h3", "h4"):
            heading_text = el.get_text(strip=True)
            # Normalize heading: strip trailing punctuation for matching
            normalized = heading_text.rstrip("!.…")
            in_required = normalized in _REQUIRED_HEADINGS
        text = el.get_text(separator="\n", strip=True)
        if not text:
            continue
        all_text_parts.append(text)
        if in_required and text:
            required_text_parts.append(text)

    description = "\n\n".join(all_text_parts)
    description = description[:2000] if description else None

    year_exp: str | None = None
    if required_text_parts:
        combined = "\n".join(required_text_parts)
        year_exp = _extract_year_experience(combined)

    return description, year_exp


# ── Config ────────────────────────────────────────────────────────────────────


@dataclass
class _GhConfig:
    board_slug: str
    max_items: int
    career_url_tmpl: str


def _parse_config(config_json: str | None, parser_key: str) -> _GhConfig:
    """Resolve board slug, max_items, and career URL template.

    Merges: config_json → board defaults → parser_key alias defaults → hardcoded
    defaults.
    """
    data: dict = {}
    if config_json:
        try:
            data = json.loads(config_json)
        except Exception:
            pass

    # Board slug: explicit config → parser_key alias
    board_slug = str(data.get("board_slug") or "").strip()
    if not board_slug:
        board_slug = _KEY_TO_SLUG.get(parser_key, "")
    if not board_slug:
        board_slug = "unknown"

    board_defaults = _BOARD_DEFAULTS.get(board_slug, {})

    max_items = int(
        data.get("max_items") or data.get("max_discover") or _DEFAULT_MAX_ITEMS
    )

    career_url_tmpl = str(
        data.get("career_url_tmpl")
        or board_defaults.get("career_url_tmpl")
        or f"https://job-boards.greenhouse.io/{board_slug}/jobs/{{job_id}}"
    )

    return _GhConfig(
        board_slug=board_slug,
        max_items=max_items,
        career_url_tmpl=career_url_tmpl,
    )


# ── Field helpers ─────────────────────────────────────────────────────────────


def _get_meta(metadata: list[dict], name: str) -> str | None:
    """Return the value for a metadata entry by name (case-sensitive).

    Returns None when the key is absent, value is null, or value is an empty string.
    Traverses in order — board metadata IDs are stable but order may vary.
    """
    for entry in metadata:
        if entry.get("name") == name:
            v = entry.get("value")
            if v is None or v == "":
                return None
            return str(v)
    return None


def _parse_deadline(value: str | None) -> date | None:
    """Parse Valid Through metadata value → date | None.

    Empty string or None → None (상시채용).
    Accepts YYYY-MM-DD format.
    """
    if not value:
        return None
    try:
        return date.fromisoformat(value[:10])
    except (ValueError, TypeError):
        return None


def _parse_posted_at(value: str | None) -> datetime | None:
    """Parse first_published ISO 8601 string → datetime (UTC-naive)."""
    if not value:
        return None
    try:
        # Remove timezone offset for simplicity; preserve as-is in UTC-ish
        cleaned = re.sub(r"[+-]\d{2}:\d{2}$", "", value.strip())
        return datetime.fromisoformat(cleaned)
    except (ValueError, TypeError):
        return None


def _build_roles(departments: list[dict], division: str | None) -> list[str]:
    """Combine Division (broad) and department names (specific) into roles list.

    Example output: ["Tech", "Software Engineer, Backend"]
    """
    seen: set[str] = set()
    roles: list[str] = []
    for v in [division] + [d.get("name", "") for d in departments]:
        s = (v or "").strip()
        if s and s not in seen:
            seen.add(s)
            roles.append(s)
    return roles


def _normalize_location(raw: str | None) -> str | None:
    if not raw:
        return None
    return _LOCATION_NORMALIZE.get(raw, raw)


# ── Adapter ───────────────────────────────────────────────────────────────────


class GreenhouseParser(CustomParser):
    """Collects job postings from a Greenhouse job board via its public API.

    A single GET request with ?content=true retrieves all jobs and their
    full HTML content — no pagination, no per-job detail requests.
    """

    async def fetch(
        self,
        source: OfficialCompanySource,
        profile: CompanyProfile | None,
        options: CollectionOptions,
        collect_date: date,
    ) -> AdapterResult:
        # Infer parser_key from config_json (fall back to "GREENHOUSE")
        try:
            cfg_data = json.loads(source.config_json or "{}")
            parser_key = cfg_data.get("parser_key", "GREENHOUSE")
        except Exception:
            parser_key = "GREENHOUSE"

        config = _parse_config(source.config_json, parser_key)
        sid = f"company_{source.company_id}_custom_{parser_key.lower()}"
        timeout = settings.job_collection_timeout_seconds
        warnings: list[str] = []

        list_url = _GH_LIST_URL.format(board_slug=config.board_slug)

        try:
            async with AsyncClient(
                timeout=timeout,
                headers={
                    "User-Agent": (
                        "Mozilla/5.0 (compatible; Briefy-Agent/1.0; "
                        "+https://briefy.io)"
                    ),
                    "Accept": "application/json",
                },
                follow_redirects=True,
            ) as client:
                try:
                    resp = await client.get(list_url, params={"content": "true"})
                    resp.raise_for_status()
                except TimeoutException:
                    msg = (
                        f"greenhouse: list timeout board={config.board_slug}"
                        f" company_id={source.company_id}"
                    )
                    warnings.append(msg)
                    return AdapterResult(
                        warnings=warnings,
                        source_stats=AdapterSourceStats(),
                    )
                except HTTPStatusError as exc:
                    msg = (
                        f"greenhouse: list HTTP {exc.response.status_code}"
                        f" board={config.board_slug}"
                        f" company_id={source.company_id}"
                    )
                    warnings.append(msg)
                    return AdapterResult(
                        warnings=warnings,
                        source_stats=AdapterSourceStats(),
                    )

                try:
                    payload = resp.json()
                except Exception:
                    msg = (
                        f"greenhouse: list JSON parse error board={config.board_slug}"
                        f" company_id={source.company_id}"
                    )
                    warnings.append(msg)
                    return AdapterResult(
                        warnings=warnings,
                        source_stats=AdapterSourceStats(),
                    )

                raw_jobs = payload.get("jobs")
                if not isinstance(raw_jobs, list):
                    msg = (
                        f"greenhouse: unexpected schema — 'jobs' not a list"
                        f" board={config.board_slug}"
                        f" company_id={source.company_id}"
                    )
                    warnings.append(msg)
                    return AdapterResult(
                        warnings=warnings,
                        source_stats=AdapterSourceStats(),
                    )

                if not raw_jobs:
                    return AdapterResult(
                        postings=[],
                        warnings=warnings,
                        source_stats=AdapterSourceStats(
                            discovered=0, fetched=0, parsed=0, selected=0
                        ),
                    )

                postings: list[RawJobPosting] = []

                for job in raw_jobs[: config.max_items]:
                    try:
                        posting = _parse_job(
                            job,
                            sid=sid,
                            config=config,
                            profile=profile,
                            warnings=warnings,
                        )
                        if posting is not None:
                            postings.append(posting)
                    except Exception as exc:
                        job_id = job.get("id", "?")
                        warnings.append(
                            f"greenhouse: unexpected parse error job_id={job_id}: {exc}"
                        )

        except Exception as exc:
            msg = (
                f"greenhouse: unexpected error"
                f" board={config.board_slug}"
                f" company_id={source.company_id}: {exc}"
            )
            log.warning(msg)
            return AdapterResult(
                warnings=[msg],
                source_stats=AdapterSourceStats(),
            )

        stats = AdapterSourceStats(
            discovered=len(postings),
            fetched=len(postings),
            parsed=len(postings),
            selected=len(postings),
        )
        return AdapterResult(postings=postings, warnings=warnings, source_stats=stats)


def _parse_job(
    job: dict,
    *,
    sid: str,
    config: _GhConfig,
    profile: CompanyProfile | None,
    warnings: list[str],
) -> RawJobPosting | None:
    """Parse a single Greenhouse job dict into a RawJobPosting.

    Returns None on fatal field failure (missing id or title).
    Emits warnings for non-fatal issues.
    """
    job_id_raw = job.get("id")
    if not job_id_raw:
        warnings.append("greenhouse: skipped job with missing id")
        return None

    job_id = str(job_id_raw)
    title = str(job.get("title") or "").strip()
    if not title:
        warnings.append(f"greenhouse: skipped job id={job_id} with empty title")
        return None

    metadata: list[dict] = job.get("metadata") or []
    departments: list[dict] = job.get("departments") or []

    # ── company_name: metadata.Corporate preferred over profile name ──────────
    corporate = _get_meta(metadata, "Corporate")
    if corporate:
        company_name = corporate
    elif profile:
        company_name = profile.canonical_name
    else:
        company_name = "당근"  # last resort for daangn board

    # ── roles ─────────────────────────────────────────────────────────────────
    division = _get_meta(metadata, "Division")
    roles = _build_roles(departments, division)

    # ── experience_level ──────────────────────────────────────────────────────
    prior_exp_raw = _get_meta(metadata, "Prior Experience")
    base_exp = _PRIOR_EXP_MAP.get(prior_exp_raw or "", prior_exp_raw)

    # Enrich from content when base is generic "경력" or absent
    content_raw = str(job.get("content") or "")
    description, content_exp = _html_to_description_and_exp(content_raw)

    if content_exp and (base_exp == "경력" or base_exp is None):
        experience_level = content_exp
    else:
        experience_level = base_exp

    # ── employment_type ───────────────────────────────────────────────────────
    emp_raw = _get_meta(metadata, "Employment Type")
    employment_type = _EMP_TYPE_MAP.get(emp_raw or "", emp_raw)

    # ── deadline ──────────────────────────────────────────────────────────────
    valid_through = _get_meta(metadata, "Valid Through")
    deadline = _parse_deadline(valid_through) or _parse_deadline(
        str(job.get("application_deadline") or "")
    )

    # ── location ──────────────────────────────────────────────────────────────
    loc_raw = (job.get("location") or {}).get("name")
    location = _normalize_location(loc_raw)

    # ── source_url ────────────────────────────────────────────────────────────
    source_url = config.career_url_tmpl.format(job_id=job_id)

    # ── posted_at ─────────────────────────────────────────────────────────────
    posted_at = _parse_posted_at(str(job.get("first_published") or ""))

    return RawJobPosting(
        source=sid,
        source_url=source_url,
        company_name=company_name,
        title=title,
        employment_type=employment_type,
        experience_level=experience_level,
        location=location,
        deadline=deadline,
        skills=[],
        roles=roles,
        description=description,
        posted_at=posted_at,
        source_external_id=job_id,
    )


# ── Self-registration ──────────────────────────────────────────────────────────

_gh_parser = GreenhouseParser()
_CUSTOM_REGISTRY_BY_KEY["GREENHOUSE"] = _gh_parser
# DAANGN_CAREERS: alias that uses the same parser with board_slug="daangn"
_CUSTOM_REGISTRY_BY_KEY["DAANGN_CAREERS"] = _gh_parser


# ── Preflight ──────────────────────────────────────────────────────────────────


async def greenhouse_preflight(source: OfficialCompanySource) -> dict:
    """Check Greenhouse board reachability and parse a sample job.

    Returns dict: reachable, discovered_count, sample_parsed, warnings.
    """
    try:
        cfg_data = json.loads(source.config_json or "{}")
        parser_key = cfg_data.get("parser_key", "GREENHOUSE")
    except Exception:
        parser_key = "GREENHOUSE"

    config = _parse_config(source.config_json, parser_key)
    sid = f"company_{source.company_id}_custom_{parser_key.lower()}"
    list_url = _GH_LIST_URL.format(board_slug=config.board_slug)
    timeout = settings.job_collection_timeout_seconds
    warnings: list[str] = []

    try:
        async with AsyncClient(
            timeout=timeout,
            headers={
                "User-Agent": (
                    "Mozilla/5.0 (compatible; Briefy-Agent/1.0; +https://briefy.io)"
                ),
                "Accept": "application/json",
            },
            follow_redirects=True,
        ) as client:
            try:
                resp = await client.get(list_url, params={"content": "true"})
                resp.raise_for_status()
            except (TimeoutException, HTTPStatusError) as exc:
                return {
                    "reachable": False,
                    "discovered_count": 0,
                    "sample_parsed": None,
                    "warnings": [str(exc)],
                }

            try:
                payload = resp.json()
            except Exception:
                return {
                    "reachable": False,
                    "discovered_count": 0,
                    "sample_parsed": None,
                    "warnings": ["JSON parse error"],
                }

            raw_jobs = payload.get("jobs")
            if not isinstance(raw_jobs, list):
                return {
                    "reachable": False,
                    "discovered_count": 0,
                    "sample_parsed": None,
                    "warnings": ["unexpected schema: 'jobs' not a list"],
                }

            discovered_count = len(raw_jobs)
            sample_parsed = None

            if raw_jobs:
                try:
                    posting = _parse_job(
                        raw_jobs[0],
                        sid=sid,
                        config=config,
                        profile=None,
                        warnings=warnings,
                    )
                    if posting is not None:
                        sample_parsed = {
                            "title": posting.title,
                            "company_name": posting.company_name,
                            "employment_type": posting.employment_type,
                            "experience_level": posting.experience_level,
                            "location": posting.location,
                            "deadline": (
                                str(posting.deadline) if posting.deadline else None
                            ),
                            "roles": posting.roles,
                            "source_external_id": posting.source_external_id,
                        }
                except Exception as exc:
                    warnings.append(f"sample parse error: {exc}")

    except Exception as exc:
        return {
            "reachable": False,
            "discovered_count": 0,
            "sample_parsed": None,
            "warnings": [str(exc)],
        }

    return {
        "reachable": True,
        "discovered_count": discovered_count,
        "sample_parsed": sample_parsed,
        "warnings": warnings,
    }
