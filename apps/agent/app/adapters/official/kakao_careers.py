"""KakaoCareersParser — custom parser for careers.kakao.com official career API.

Registered as "KAKAO_CAREERS".

Investigation summary (2026-08-17):
  The public HTML page (careers.kakao.com/jobs) is a React SPA shell that
  requires JavaScript execution and returns only a spinner div to plain HTTP
  clients.  However, the JS bundle at /static/js/main.*.js reveals a public,
  unauthenticated REST API:

    GET /public/api/job-list?company=KAKAO&page={n}
      → { jobList: [...], totalJobCount: N, totalPage: M }
      → application/json, no auth, no WAF, no Cloudflare
      → all job metadata and description fields are embedded in the list
         response — no separate detail fetch is needed
    GET /public/api/job-detail?id=P-{id}
      → identical fields to the list entry (not used in collection)

Collection flow:
  1. GET /public/api/job-list?company=KAKAO&page=1 — get totalPage.
  2. Fetch pages 2..totalPage concurrently (semaphore-limited).
  3. Filter closeFlag=False and mainCompanyJobOfferFlag=True.
  4. Safety-check realId.startswith("P-") — excludes S-{id} affiliates.
  5. Build RawJobPosting from listing fields.

company=KAKAO vs company=SUBSIDIARY:
  company=KAKAO  → P-{id} only  (카카오 본사)
  company=SUBSIDIARY → S-{id} only  (관계사: 카카오페이, 카카오모빌리티, …)
  company=ALL    → both
  This adapter always uses company=KAKAO and additionally guards on realId prefix.

config_json:
  {
    "parser_key": "KAKAO_CAREERS",
    "max_items": 200        // total posting cap (default 200)
  }

Deadline:
  endDate non-null            → parse as YYYY-MM-DD
  resumeSubmissionEndDatetime → parse datetime → date
  both null                   → None (상시 채용)

Employment type (employeeTypeName):
  "정규직" / "계약직" / "인턴" / "어시스턴트" / "경영계약직" / "전문계약직"

Experience level (combined):
  1. Title hint: "(신입/경력)", "(신입)", "(경력)" → base
  2. Qualification text → minimum year (N년 이상, N~M년 이내)
  3. base + year → "경력 N년 이상" | "경력 N~M년"
                   | "신입/경력 N년 이상" | "신입" | "경력"

Roles:
  [jobPartName] + [skillSetName for each skillSetList entry]
  jobPartName: "테크" / "서비스비즈" / "디자인" / "스태프"
  skillSetName examples: "Server", "Algorithm/ML", "iOS", "Android", "QA", …

Description:
  HTML-to-text of workContentDesc + qualification (up to 4000 chars).
  Markdown-like markup (**bold**, • bullets) is preserved as plain text.

Shell response detection:
  Content-Type must be application/json and response body must have "jobList".
  HTML response (text/html) → "CLOUDFLARE_SHELL" warning.

robots.txt / terms:
  careers.kakao.com returns 401 for /robots.txt at implementation time.
  The /public/api/ namespace is explicitly designed as a public endpoint
  (no auth, no rate-limit header, publicly exposed in the JS bundle).
"""

import asyncio
import json
import logging
import re
from dataclasses import dataclass
from datetime import date, datetime
from typing import Any

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

_LIST_BASE = "https://careers.kakao.com/public/api/job-list"
_SOURCE_URL_TMPL = "https://careers.kakao.com/jobs/{real_id}"
_SOURCE = "kakao_careers"
_COMPANY_PARAM = "KAKAO"

_DEFAULT_MAX_ITEMS = 200
_SEMAPHORE_LIMIT = 5

_HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/124.0.0.0 Safari/537.36"
    ),
    "Accept": "application/json, text/plain, */*",
    "Accept-Language": "ko-KR,ko;q=0.9",
    "Referer": "https://careers.kakao.com/jobs",
}


# ── Config ─────────────────────────────────────────────────────────────────────


@dataclass
class _KakaoConfig:
    max_items: int


def _parse_config(config_json: str | None) -> _KakaoConfig:
    data: dict = {}
    if config_json:
        try:
            data = json.loads(config_json)
        except Exception:
            pass
    max_items = int(data.get("max_items") or _DEFAULT_MAX_ITEMS)
    return _KakaoConfig(max_items=max_items)


# ── Shell / JSON response detection ────────────────────────────────────────────


def _is_shell_response(content_type: str, body: str) -> bool:
    """Return True if the response is a React SPA shell instead of API JSON."""
    ct_lower = content_type.lower()
    if "application/json" in ct_lower:
        return False
    if "text/html" in ct_lower:
        return True
    # Fallback: check if body starts with HTML doctype
    return body.lstrip()[:15].lower().startswith("<!doctype")


# ── Field extractors ───────────────────────────────────────────────────────────


def _experience_level(item: dict[str, Any]) -> str | None:
    """Derive experience_level from title hint + minimum year in qualification."""
    title: str = item.get("jobOfferTitle") or ""
    qual: str = item.get("qualification") or ""

    # Title hint — order matters: check combined first
    m = re.search(r"\((신입/경력|신입|경력)[^)]*\)", title)
    base = m.group(1) if m else None

    if base is None:
        return None
    if base == "신입":
        return "신입"

    # Extract minimum year from qualification
    # Pattern 1: "N년 이상"
    over_matches = [int(x) for x in re.findall(r"(\d+)년\s*이상", qual)]
    # Pattern 2: "N년~M년" or "N년-M년" (range — use lower bound)
    range_m = re.search(r"(\d+)년[~-](\d+)년", qual)

    min_yr: int | None = None
    max_yr: int | None = None

    if over_matches:
        min_yr = min(over_matches)
    elif range_m:
        min_yr = int(range_m.group(1))
        max_yr = int(range_m.group(2))

    if base == "경력":
        if min_yr and max_yr:
            return f"경력 {min_yr}~{max_yr}년"
        if min_yr:
            return f"경력 {min_yr}년 이상"
        return "경력"

    # base == "신입/경력"
    if min_yr:
        return f"신입/경력 {min_yr}년 이상"
    return "신입/경력"


def _employment_type(item: dict[str, Any]) -> str | None:
    return str(item.get("employeeTypeName") or "").strip() or None


def _deadline(item: dict[str, Any]) -> date | None:
    raw = item.get("endDate") or item.get("resumeSubmissionEndDatetime")
    if not raw:
        return None
    try:
        s = str(raw)[:10]
        return date.fromisoformat(s)
    except Exception:
        return None


def _location(item: dict[str, Any]) -> str | None:
    name = str(item.get("locationName") or "").strip()
    return name or None


def _roles(item: dict[str, Any]) -> list[str]:
    result: list[str] = []
    part = str(item.get("jobPartName") or "").strip()
    if part:
        result.append(part)
    for ss in item.get("skillSetList") or []:
        name = str(ss.get("skillSetName") or "").strip()
        if name and name not in result:
            result.append(name)
    return result


def _description(item: dict[str, Any]) -> str | None:
    """Combine workContentDesc + qualification as plain text (≤4000 chars)."""
    parts: list[str] = []
    for field in ("workContentDesc", "qualification"):
        html = str(item.get(field) or "").strip()
        if not html:
            continue
        try:
            text = BeautifulSoup(html, "html.parser").get_text(
                separator="\n", strip=True
            )
            if text:
                parts.append(text)
        except Exception:
            parts.append(html[:500])
    if not parts:
        return None
    combined = "\n\n".join(parts)
    return combined[:4000]


def _posted_at(item: dict[str, Any]) -> datetime | None:
    raw = item.get("regDate")
    if not raw:
        return None
    try:
        return datetime.fromisoformat(str(raw))
    except Exception:
        return None


def _is_active(item: dict[str, Any]) -> bool:
    if item.get("closeFlag"):
        return False
    if not item.get("mainCompanyJobOfferFlag", True):
        return False
    real_id = str(item.get("realId") or "")
    if not real_id.startswith("P-"):
        return False
    return True


# ── RawJobPosting builder ──────────────────────────────────────────────────────


def _build_posting(item: dict[str, Any], company_name: str) -> RawJobPosting:
    real_id: str = str(item.get("realId") or "")
    return RawJobPosting(
        source=_SOURCE,
        source_url=_SOURCE_URL_TMPL.format(real_id=real_id),
        company_name=company_name,
        title=str(item.get("jobOfferTitle") or "").strip(),
        employment_type=_employment_type(item),
        experience_level=_experience_level(item),
        location=_location(item),
        deadline=_deadline(item),
        roles=_roles(item),
        description=_description(item),
        posted_at=_posted_at(item),
        source_external_id=real_id,
    )


# ── Parser ─────────────────────────────────────────────────────────────────────


class KakaoCareersParser(CustomParser):
    PARSER_KEY = "KAKAO_CAREERS"

    async def fetch(
        self,
        source: OfficialCompanySource,
        profile: CompanyProfile | None,
        options: CollectionOptions,
        collect_date: date | None = None,
    ) -> AdapterResult:
        config = _parse_config(source.config_json)
        sid = f"company_{source.company_id}_custom_kakao_careers"
        company_name = (profile.canonical_name if profile else None) or "카카오"
        warnings: list[str] = []
        timeout = settings.job_collection_timeout_seconds

        async with AsyncClient(
            timeout=timeout,
            headers=_HEADERS,
            follow_redirects=True,
        ) as client:
            # ── Phase 1: page 1 (get totalPage) ──────────────────────────────
            try:
                r1 = await client.get(
                    _LIST_BASE, params={"company": _COMPANY_PARAM, "page": 1}
                )
                r1.raise_for_status()
            except TimeoutException:
                warnings.append(f"kakao_careers: listing timeout [{sid}]")
                return AdapterResult(
                    warnings=warnings, source_stats=AdapterSourceStats()
                )
            except HTTPStatusError as exc:
                warnings.append(
                    f"kakao_careers: listing HTTP "
                    f"{exc.response.status_code} [{sid}]"
                )
                return AdapterResult(
                    warnings=warnings, source_stats=AdapterSourceStats()
                )
            except Exception as exc:
                warnings.append(f"kakao_careers: listing error [{sid}]: {exc}")
                return AdapterResult(
                    warnings=warnings, source_stats=AdapterSourceStats()
                )

            ct = r1.headers.get("content-type", "")
            if _is_shell_response(ct, r1.text):
                warnings.append(
                    f"kakao_careers: page 1 returned HTML shell "
                    f"(expected JSON) [{sid}]"
                )
                return AdapterResult(
                    warnings=warnings, source_stats=AdapterSourceStats()
                )

            try:
                data1 = r1.json()
                all_jobs: list[dict] = list(data1.get("jobList") or [])
                total_page: int = int(data1.get("totalPage") or 1)
                total_count: int = int(data1.get("totalJobCount") or 0)
            except Exception as exc:
                warnings.append(
                    f"kakao_careers: failed to parse page 1 JSON [{sid}]: {exc}"
                )
                return AdapterResult(
                    warnings=warnings, source_stats=AdapterSourceStats()
                )

            log.info(
                "kakao_careers: page 1 OK totalJobCount=%d totalPage=%d [%s]",
                total_count,
                total_page,
                sid,
            )

            # ── Phase 2: remaining pages ───────────────────────────────────────
            if total_page > 1:
                sem = asyncio.Semaphore(_SEMAPHORE_LIMIT)

                async def _fetch_page(page: int) -> list[dict]:
                    async with sem:
                        try:
                            rp = await client.get(
                                _LIST_BASE,
                                params={"company": _COMPANY_PARAM, "page": page},
                            )
                            rp.raise_for_status()
                            ct_p = rp.headers.get("content-type", "")
                            if _is_shell_response(ct_p, rp.text):
                                warnings.append(
                                    f"kakao_careers: page {page} shell [{sid}]"
                                )
                                return []
                            return list(rp.json().get("jobList") or [])
                        except Exception as exc:
                            warnings.append(
                                f"kakao_careers: page {page} error [{sid}]: " f"{exc}"
                            )
                            return []

                pages = await asyncio.gather(
                    *[_fetch_page(p) for p in range(2, total_page + 1)]
                )
                for page_jobs in pages:
                    all_jobs.extend(page_jobs)

        # ── Phase 3: filter + build ────────────────────────────────────────────
        discovered = len(all_jobs)
        active = [j for j in all_jobs if _is_active(j)]
        capped = active[: config.max_items]

        log.info(
            "kakao_careers: discovered=%d active=%d capped=%d [%s]",
            discovered,
            len(active),
            len(capped),
            sid,
        )

        postings: list[RawJobPosting] = []
        for item in capped:
            posting = _build_posting(item, company_name)
            if not posting.title:
                warnings.append(
                    f"kakao_careers: empty title {item.get('realId')} [{sid}]"
                )
                continue
            postings.append(posting)

        stats = AdapterSourceStats(
            discovered=discovered,
            fetched=discovered,
            parsed=len(postings),
            selected=len(postings),
        )
        log.info(
            "kakao_careers: parsed=%d selected=%d [%s]",
            stats.parsed,
            stats.selected,
            sid,
        )
        return AdapterResult(postings=postings, warnings=warnings, source_stats=stats)


# ── Self-registration ──────────────────────────────────────────────────────────

_CUSTOM_REGISTRY_BY_KEY["KAKAO_CAREERS"] = KakaoCareersParser()


# ── Preflight ──────────────────────────────────────────────────────────────────


async def kakao_preflight(source: OfficialCompanySource) -> dict:
    """Bounded preflight probe for KAKAO_CAREERS."""
    timeout = settings.job_collection_timeout_seconds
    warnings: list[str] = []

    try:
        async with AsyncClient(
            timeout=timeout,
            headers=_HEADERS,
            follow_redirects=True,
        ) as client:
            resp = await client.get(
                _LIST_BASE, params={"company": _COMPANY_PARAM, "page": 1}
            )
            resp.raise_for_status()
    except Exception as exc:
        return {
            "reachable": False,
            "failure_code": "URL_UNREACHABLE",
            "failure_message": str(exc),
            "warnings": warnings,
        }

    ct = resp.headers.get("content-type", "")
    if _is_shell_response(ct, resp.text):
        return {
            "reachable": True,
            "failure_code": "CLOUDFLARE_SHELL",
            "failure_message": (
                "API returned HTML shell instead of JSON — "
                "possible WAF or Cloudflare block"
            ),
            "warnings": warnings,
        }

    try:
        data = resp.json()
        jobs = data.get("jobList") or []
        total_count = int(data.get("totalJobCount") or 0)
    except Exception as exc:
        return {
            "reachable": True,
            "failure_code": "PARSE_FAILED",
            "failure_message": str(exc),
            "warnings": warnings,
        }

    active = [j for j in jobs if _is_active(j)]
    sample_parsed: dict | None = None

    if active:
        item = active[0]
        posting = _build_posting(item, "카카오")
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
        "discovered_count": total_count,
        "sample_parsed": sample_parsed,
        "warnings": warnings,
    }
