"""TossCareersParser — custom parser for toss.im official career site.

Registered as "TOSS_CAREERS".

Collection flow:
  1. GET https://toss.im/career/sitemap.xml
     Parse <loc> entries that contain ?job_id=; extract job_id.
  2. Deduplicate job_ids; cap at max_discover.
  3. Fetch up to max_items detail pages concurrently (semaphore-limited):
       https://toss.im/career/job-detail?job_id={job_id}
  4. Parse __NEXT_DATA__ JSON → job object.
  5. Build RawJobPosting from structured fields + markdown description.

config_json:
  {
    "parser_key": "TOSS_CAREERS",
    "max_discover": 500,    // sitemap URL cap (default 500)
    "max_items": 50         // detail-fetch cap (default 50)
  }

robots.txt compliance:
  - Disallow: /career/jobs?*        → not used (we use sitemap)
  - Disallow: /career/job-detail?gh_jid=...  → not used (we use ?job_id=)
  - Sitemap: https://toss.im/career/sitemap.xml  → explicitly declared

Sitemap diff / expiry-based deactivation:
  Not implemented. The backend has no authoritative full-scan contract.

Company scope:
  toss.im publishes all Toss Group subsidiaries under one sitemap:
  토스, 토스뱅크, 토스페이먼츠, 토스플레이스, 토스인슈어런스,
  토스씨엑스, 토스증권, 토스커뮤니티, etc.
  The `company` field is preserved verbatim in company_name.
  Backend alias table maps each name to company_id=9 (Toss Group).
"""

import asyncio
import json
import logging
import re
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from datetime import date
from urllib.parse import parse_qs, urlparse

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

_SITEMAP_URL = "https://toss.im/career/sitemap.xml"
_DETAIL_URL_TMPL = "https://toss.im/career/job-detail?job_id={job_id}"

_DEFAULT_MAX_DISCOVER = 500
_DEFAULT_MAX_ITEMS = 50
_SEMAPHORE_LIMIT = 5

# ── Location normalisation ────────────────────────────────────────────────────

_LOCATION_MAP: dict[str, str] = {
    "Seoul": "서울",
    "seoul": "서울",
    "SEOUL": "서울",
    "서울": "서울",
    "Busan": "부산",
    "busan": "부산",
    "Remote": "원격",
    "remote": "원격",
}

# ── Employment type pass-through map ─────────────────────────────────────────

_EMP_TYPE_MAP: dict[str, str] = {
    "정규직": "정규직",
    "계약직": "계약직",
    "단기계약직": "계약직",
    "무기계약직": "정규직",
    "인턴": "인턴",
}

# ── Experience section headings ───────────────────────────────────────────────

_REQUIRED_HEADINGS: frozenset[str] = frozenset({
    "이런 분과 함께하고 싶어요",
    "이런 분을 찾고 있어요",
    "이런 분이에요",
    "지원 자격을 꼭 확인해주세요",
    "지원 자격",
    "자격 요건",
    "필수 요건",
    "필수 자격",
})

_PREFERRED_HEADINGS: frozenset[str] = frozenset({
    "이런 경험이 있다면 더 좋아요",
    "이런 경험이 있으면 더 좋아요",
    "우대사항",
    "우대 사항",
    "우대 조건",
})

# Regex patterns for experience year extraction
_EXP_YEAR_RANGE_LABELED_RE = re.compile(r"경력\s*(\d+)\s*년\s*이상\s*(\d+)\s*년\s*이하")
_EXP_YEAR_RANGE_BARE_RE = re.compile(r"(\d+)\s*년\s*이상\s*(\d+)\s*년\s*이하")
_EXP_TILDE_RE = re.compile(r"(\d+)\s*[~～]\s*(\d+)\s*년")
_EXP_MIN_WITH_PREFIX_RE = re.compile(r"경력\s*(\d+)\s*년\s*이상")
_EXP_MIN_RE = re.compile(r"(\d+)\s*년\s*이상")


# ── Config ────────────────────────────────────────────────────────────────────


@dataclass
class _TossConfig:
    max_discover: int
    max_items: int


def _parse_config(config_json: str | None) -> _TossConfig:
    data: dict = {}
    if config_json:
        try:
            data = json.loads(config_json)
        except Exception:
            pass
    max_discover = int(data.get("max_discover") or _DEFAULT_MAX_DISCOVER)
    max_items = int(data.get("max_items") or _DEFAULT_MAX_ITEMS)
    # max_items should not exceed max_discover
    max_items = min(max_items, max_discover)
    return _TossConfig(max_discover=max_discover, max_items=max_items)


# ── Sitemap parsing ───────────────────────────────────────────────────────────


def _parse_sitemap(xml_text: str) -> list[str]:
    """Extract deduplicated job_ids from sitemap XML.

    Returns empty list for valid-but-empty sitemaps.
    Raises ValueError for malformed XML.
    """
    try:
        root = ET.fromstring(xml_text.encode())
    except ET.ParseError as exc:
        raise ValueError(f"malformed sitemap XML: {exc}") from exc

    # ElementTree strips namespace prefix; handle with/without ns
    ns = ""
    tag = root.tag
    if tag.startswith("{"):
        ns = tag[: tag.index("}") + 1]

    seen: set[str] = set()
    job_ids: list[str] = []

    for url_el in root.iter(f"{ns}url"):
        loc_el = url_el.find(f"{ns}loc")
        if loc_el is None or not loc_el.text:
            continue
        loc = loc_el.text.strip()
        qs = parse_qs(urlparse(loc).query)
        ids = qs.get("job_id", [])
        if not ids:
            continue
        jid = ids[0]
        if jid not in seen:
            seen.add(jid)
            job_ids.append(jid)

    return job_ids


# ── Detail page parsing ───────────────────────────────────────────────────────


def _parse_job_detail_html(html: str) -> dict | None:
    """Extract job dict from __NEXT_DATA__ in a Toss career detail page.

    Returns None when __NEXT_DATA__ is absent or the job-detail query is missing.
    """
    m = re.search(
        r'<script[^>]+id=["\']__NEXT_DATA__["\'][^>]*>(.*?)</script>',
        html,
        re.DOTALL,
    )
    if not m:
        return None
    try:
        data = json.loads(m.group(1))
    except json.JSONDecodeError:
        return None

    try:
        queries = (
            data["props"]["pageProps"]["prefetchResult"]["dehydratedState"]["queries"]
        )
    except (KeyError, TypeError):
        return None

    for q in queries:
        key = q.get("queryKey") or []
        if isinstance(key, list) and len(key) >= 2 and key[1] == "job-detail":
            raw = (q.get("state") or {}).get("data")
            if not raw:
                return None
            try:
                payload = json.loads(raw)
            except json.JSONDecodeError:
                return None
            return payload.get("job")

    return None


# ── Experience extraction ─────────────────────────────────────────────────────


def _extract_experience(
    description: str,
    employment_type: str | None,
    title: str,
) -> str | None:
    """Infer experience_level from description markdown.

    Priority:
      1. 인턴 in employment_type or title → "인턴"
      2. Required-section text:
         a. 신입 patterns → "신입" or "경력 무관"
         b. Year range (N년 이상 M년 이하, N~M년) → "경력 N~M년"
         c. Year minimum (N년 이상) → "경력 N년 이상"
         d. Qualifications present but no year → "경력"
      3. No required section content → None
    """
    if employment_type == "인턴" or "인턴십" in title or "인턴" in title:
        return "인턴"

    # Collect lines from required sections only
    required_lines: list[str] = []
    in_required = False

    for line in description.split("\n"):
        stripped = line.strip()
        if stripped.startswith("#"):
            heading_text = stripped.lstrip("#").strip().rstrip(".!。 ")
            is_req = any(h in heading_text for h in _REQUIRED_HEADINGS)
            is_pref = any(h in heading_text for h in _PREFERRED_HEADINGS)
            in_required = is_req and not is_pref
        elif in_required:
            required_lines.append(line)

    if not required_lines:
        return None

    text = "\n".join(required_lines)

    # 신입 / 경력 무관 patterns (check before year patterns)
    if "경력 무관" in text or "경력무관" in text:
        return "경력 무관"
    if re.search(r"신입\s*(지원\s*가능|또는|도\s*가능|부터|~)", text):
        return "신입"
    if re.search(r"(신입|freshman|junior).*가능", text, re.IGNORECASE):
        return "신입"
    # "신입 또는 N년 미만" → 신입
    if re.search(r"신입\s*(또는|/)", text):
        return "신입"

    # Year range: "경력 N년 이상 M년 이하" (with label)
    m = _EXP_YEAR_RANGE_LABELED_RE.search(text)
    if m:
        return f"경력 {m.group(1)}~{m.group(2)}년"

    # Year range: "N년 이상 M년 이하" (without label)
    m = _EXP_YEAR_RANGE_BARE_RE.search(text)
    if m:
        return f"경력 {m.group(1)}~{m.group(2)}년"

    # Tilde range: "N~M년"
    m = _EXP_TILDE_RE.search(text)
    if m:
        return f"경력 {m.group(1)}~{m.group(2)}년"

    # Minimum with prefix: "경력 N년 이상" (includes "N년 이상에 준하는")
    m = _EXP_MIN_WITH_PREFIX_RE.search(text)
    if m:
        return f"경력 {m.group(1)}년 이상"

    # Minimum without prefix: "N년 이상 경험"
    m = _EXP_MIN_RE.search(text)
    if m:
        return f"경력 {m.group(1)}년 이상"

    # Required section has content but no year → 경력 연수 불명
    if text.strip():
        return "경력"

    return None


# ── Roles builder ─────────────────────────────────────────────────────────────

# "전 직군" 의미의 범용 role 값 — eligibility 필터에서 role mismatch로 잘못 제외되는
# 것을 막기 위해 빈 리스트로 정규화한다. posting.roles=[]이면
# _is_clear_role_mismatch가 "Ambiguous → keep"으로 처리한다.
_UNIVERSAL_ROLES: frozenset[str] = frozenset(
    {"전체", "All", "all", "전 직군", "전직군"}
)


def _build_roles(main_category: str | None, sub_category: str | None) -> list[str]:
    """Combine mainCategory (broad) and subCategory (specific) into roles list."""
    seen: set[str] = set()
    roles: list[str] = []
    for v in [main_category, sub_category]:
        s = (v or "").strip()
        if s and s not in seen:
            seen.add(s)
            roles.append(s)
    return [r for r in roles if r not in _UNIVERSAL_ROLES]


# ── Deadline parser ───────────────────────────────────────────────────────────


def _parse_deadline(expiry_date: str | None, expiry_time: str | None) -> date | None:
    """Parse expiryDate + expiryTime → date | None.

    Empty string → None (상시채용).
    """
    if not expiry_date:
        return None
    try:
        return date.fromisoformat(expiry_date[:10])
    except (ValueError, TypeError):
        return None


# ── Single-job mapper ─────────────────────────────────────────────────────────


def _job_to_posting(
    job: dict,
    *,
    sid: str,
    profile: CompanyProfile | None,
    warnings: list[str],
) -> RawJobPosting | None:
    """Convert a Toss job dict (from __NEXT_DATA__) to RawJobPosting.

    Returns None on fatal field failure.
    """
    job_id_raw = job.get("id")
    if not job_id_raw:
        warnings.append("toss_careers: skipped job with missing id")
        return None

    job_id = str(job_id_raw)
    title = str(job.get("title") or "").strip()
    if not title:
        warnings.append(f"toss_careers: skipped job id={job_id} with empty title")
        return None

    # ── company_name ──────────────────────────────────────────────────────────
    company_name = str(job.get("company") or "").strip() or (
        profile.canonical_name if profile else "토스"
    )

    # ── employment_type ───────────────────────────────────────────────────────
    emp_raw = str(job.get("employmentType") or "").strip()
    employment_type = _EMP_TYPE_MAP.get(emp_raw, emp_raw) or None

    # ── roles ─────────────────────────────────────────────────────────────────
    main_cat = job.get("mainCategory")
    sub_cat = job.get("subCategory")
    roles = _build_roles(main_cat, sub_cat)

    # ── location ──────────────────────────────────────────────────────────────
    loc_raw = str(job.get("location") or "").strip()
    location = _LOCATION_MAP.get(loc_raw, loc_raw) or None

    # ── deadline ──────────────────────────────────────────────────────────────
    deadline = _parse_deadline(job.get("expiryDate"), job.get("expiryTime"))

    # ── description (use clean markdown; fallback to empty) ───────────────────
    description_full = str(job.get("description") or "").strip() or None

    # Extract experience from FULL description before 2000-char truncation,
    # since required sections may appear late in longer job descriptions.
    experience_level = _extract_experience(
        description_full or "",
        employment_type,
        title,
    )

    description = description_full[:2000] if description_full else None

    # ── source_url ────────────────────────────────────────────────────────────
    source_url = _DETAIL_URL_TMPL.format(job_id=job_id)

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
        posted_at=None,
        source_external_id=job_id,
    )


# ── Parser ────────────────────────────────────────────────────────────────────


class TossCareersParser(CustomParser):
    """Collects job postings from toss.im via sitemap + __NEXT_DATA__ extraction.

    Two-phase:
      1. Sitemap fetch  — discovers job_ids (fast, ~1 request)
      2. Detail fetches — retrieves full job data (semaphore-limited concurrency)
    """

    async def fetch(
        self,
        source: OfficialCompanySource,
        profile: CompanyProfile | None,
        options: CollectionOptions,
        collect_date: date,
    ) -> AdapterResult:
        config = _parse_config(source.config_json)
        sid = f"company_{source.company_id}_custom_toss_careers"
        timeout = settings.job_collection_timeout_seconds
        warnings: list[str] = []

        async with AsyncClient(
            timeout=timeout,
            headers={
                "User-Agent": (
                    "Mozilla/5.0 (compatible; Briefy-Agent/1.0; +https://briefy.io)"
                ),
                "Accept": (
                    "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                ),
                "Accept-Language": "ko-KR,ko;q=0.9",
            },
            follow_redirects=True,
        ) as client:
            # ── Phase 1: Sitemap ──────────────────────────────────────────────
            try:
                sitemap_resp = await client.get(_SITEMAP_URL)
                sitemap_resp.raise_for_status()
            except TimeoutException:
                msg = f"toss_careers: sitemap timeout company_id={source.company_id}"
                warnings.append(msg)
                return AdapterResult(
                    warnings=warnings, source_stats=AdapterSourceStats()
                )
            except HTTPStatusError as exc:
                msg = (
                    f"toss_careers: sitemap HTTP {exc.response.status_code}"
                    f" company_id={source.company_id}"
                )
                warnings.append(msg)
                return AdapterResult(
                    warnings=warnings, source_stats=AdapterSourceStats()
                )
            except Exception as exc:
                msg = f"toss_careers: sitemap unexpected error: {exc}"
                warnings.append(msg)
                return AdapterResult(
                    warnings=warnings, source_stats=AdapterSourceStats()
                )

            try:
                job_ids = _parse_sitemap(sitemap_resp.text)
            except ValueError as exc:
                warnings.append(f"toss_careers: {exc}")
                return AdapterResult(
                    warnings=warnings, source_stats=AdapterSourceStats()
                )

            if not job_ids:
                return AdapterResult(
                    postings=[],
                    warnings=warnings,
                    source_stats=AdapterSourceStats(
                        discovered=0, fetched=0, parsed=0, selected=0
                    ),
                )

            discovered = min(len(job_ids), config.max_discover)
            job_ids_to_fetch = job_ids[:config.max_items]
            log.info(
                "toss_careers: sitemap discovered=%d, will_fetch=%d",
                discovered,
                len(job_ids_to_fetch),
            )

            # ── Phase 2: Detail fetches ───────────────────────────────────────
            sem = asyncio.Semaphore(_SEMAPHORE_LIMIT)

            async def _fetch_one(jid: str) -> RawJobPosting | None:
                url = _DETAIL_URL_TMPL.format(job_id=jid)
                async with sem:
                    try:
                        resp = await client.get(url)
                        resp.raise_for_status()
                    except TimeoutException:
                        warnings.append(f"toss_careers: detail timeout job_id={jid}")
                        return None
                    except HTTPStatusError as exc:
                        warnings.append(
                            f"toss_careers: detail HTTP {exc.response.status_code}"
                            f" job_id={jid}"
                        )
                        return None
                    except Exception as exc:
                        warnings.append(
                            f"toss_careers: detail unexpected error job_id={jid}: {exc}"
                        )
                        return None

                    try:
                        job = _parse_job_detail_html(resp.text)
                    except Exception as exc:
                        warnings.append(
                            f"toss_careers: detail parse error job_id={jid}: {exc}"
                        )
                        return None

                    if job is None:
                        warnings.append(
                            f"toss_careers: __NEXT_DATA__ missing or job-detail"
                            f" query absent job_id={jid}"
                        )
                        return None

                    try:
                        return _job_to_posting(
                            job, sid=sid, profile=profile, warnings=warnings
                        )
                    except Exception as exc:
                        warnings.append(
                            f"toss_careers: posting build error job_id={jid}: {exc}"
                        )
                        return None

            results = await asyncio.gather(
                *[_fetch_one(jid) for jid in job_ids_to_fetch]
            )
            postings = [p for p in results if p is not None]

        stats = AdapterSourceStats(
            discovered=discovered,
            fetched=len(job_ids_to_fetch),
            parsed=len(postings),
            selected=len(postings),
        )
        return AdapterResult(postings=postings, warnings=warnings, source_stats=stats)


# ── Self-registration ──────────────────────────────────────────────────────────

_CUSTOM_REGISTRY_BY_KEY["TOSS_CAREERS"] = TossCareersParser()


# ── Preflight ──────────────────────────────────────────────────────────────────


async def toss_preflight(source: OfficialCompanySource) -> dict:
    """Check Toss sitemap reachability and parse a sample job.

    Returns dict: reachable, discovered_count, sample_parsed, warnings.
    """
    sid = f"company_{source.company_id}_custom_toss_careers"
    timeout = settings.job_collection_timeout_seconds
    warnings: list[str] = []

    try:
        async with AsyncClient(
            timeout=timeout,
            headers={
                "User-Agent": (
                    "Mozilla/5.0 (compatible; Briefy-Agent/1.0; +https://briefy.io)"
                ),
                "Accept": "text/html,application/xhtml+xml,*/*",
            },
            follow_redirects=True,
        ) as client:
            try:
                sitemap_resp = await client.get(_SITEMAP_URL)
                sitemap_resp.raise_for_status()
            except (TimeoutException, HTTPStatusError) as exc:
                return {
                    "reachable": False,
                    "discovered_count": 0,
                    "sample_parsed": None,
                    "warnings": [str(exc)],
                }

            try:
                job_ids = _parse_sitemap(sitemap_resp.text)
            except ValueError as exc:
                return {
                    "reachable": False,
                    "discovered_count": 0,
                    "sample_parsed": None,
                    "warnings": [str(exc)],
                }

            discovered_count = len(job_ids)
            sample_parsed = None

            if job_ids:
                sample_id = job_ids[0]
                url = _DETAIL_URL_TMPL.format(job_id=sample_id)
                try:
                    detail_resp = await client.get(url)
                    detail_resp.raise_for_status()
                    job = _parse_job_detail_html(detail_resp.text)
                    if job is not None:
                        posting = _job_to_posting(
                            job, sid=sid, profile=None, warnings=warnings
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
                    else:
                        warnings.append(
                            f"sample __NEXT_DATA__ missing for job_id={sample_id}"
                        )
                except Exception as exc:
                    warnings.append(f"sample fetch error job_id={sample_id}: {exc}")

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
