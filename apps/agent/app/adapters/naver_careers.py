"""NaverCareersParser — Custom parser for recruit.navercorp.com.

Registered in _CUSTOM_REGISTRY_BY_KEY under parser_key="NAVER_CAREERS".

API contract (verified 2026-08-15):
  GET https://recruit.navercorp.com/rcrt/loadJobList.do
  Query: page (1-based). pageSize parameter is accepted but IGNORED by the
  server — it always returns exactly 10 items per page.
  Response: { "result": "Y", "totalSize": N, "list": [...10 items] }

  GET https://recruit.navercorp.com/rcrt/view.do?annoId={id}
  HTML detail page. Job metadata in <dl class="card_info"> DT/DD pairs.
  Job content sections in <div class="detail_wrap">.

config_json contract:
  {
    "parser_key": "NAVER_CAREERS",
    "max_discover": 50,   # max postings to collect (default 50)
    "max_fetch": 20       # max detail-page fetches for enrichment (default 20)
  }

Company scope note:
  recruit.navercorp.com hosts jobs for NAVER and its subsidiaries (NAVER Cloud,
  NAVER WEBTOON, SNOW, etc.). All are collected under this source; company_name
  is preserved as-is from sysCompanyCdNm so the backend can map each posting
  to the correct company_id via the company alias table.

workAreaCd location mapping:
  Codes are rendered client-side and not returned as names in the API.
  Known mapping (from UI filter labels): 0010→분당, 0020→서울, 0030→춘천,
  0040→세종, 0050→글로벌. Unknown codes produce None location.
"""

import json
import logging
import re
from dataclasses import dataclass
from datetime import date, datetime
from urllib.parse import urljoin

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

_LIST_URL = "https://recruit.navercorp.com/rcrt/loadJobList.do"
_DETAIL_URL_TMPL = "https://recruit.navercorp.com/rcrt/view.do?annoId={}"

# Server always returns 10 items per page regardless of pageSize param.
_PAGE_SIZE = 10
_DEFAULT_MAX_DISCOVER = 50
_DEFAULT_MAX_FETCH = 20

# ── Code → label mappings ─────────────────────────────────────────────────────

# entTypeCd → normalized experience_level string
_ENTRY_TYPE_MAP: dict[str, str] = {
    "0010": "신입",
    "0020": "경력",
    "0030": "경력 무관",
}

# empTypeCdNm → normalized employment_type string
_EMP_TYPE_MAP: dict[str, str] = {
    "정규": "정규직",
    "계약": "계약직",
    "인턴": "인턴",
    "파견": "파견직",
    "프리랜서": "프리랜서",
}

# workAreaCd → location string (client-side rendered; map known values)
_WORK_AREA_MAP: dict[str, str] = {
    "0010": "분당",
    "0020": "서울",
    "0030": "춘천",
    "0040": "세종",
    "0050": "글로벌",
}

# ── Date parsing ──────────────────────────────────────────────────────────────

_DATE_RE = re.compile(r"(\d{4})\.(\d{2})\.(\d{2})")
_DATETIME_RE = re.compile(r"(\d{4})\.(\d{2})\.(\d{2})\s+(\d{2}):(\d{2}):(\d{2})")
_YMD_RE = re.compile(r"^(\d{4})(\d{2})(\d{2})$")

# ── Experience year patterns (for detail page enrichment) ─────────────────────
# Matches explicit year requirements in required-qualification text only.
# Examples: "5년 이상", "3년 이상 경력", "경력 3년 이상", "3~5년"
_EXP_YEAR_RANGE_RE = re.compile(r"(\d+)\s*[~～]\s*(\d+)\s*년")
_EXP_MIN_RE = re.compile(r"(\d+)\s*년\s*이상")


def _parse_datetime(value: str | None) -> datetime | None:
    """Parse 'YYYY.MM.DD HH:MM:SS' → datetime."""
    if not value:
        return None
    m = _DATETIME_RE.search(value)
    if m:
        try:
            return datetime(
                int(m.group(1)), int(m.group(2)), int(m.group(3)),
                int(m.group(4)), int(m.group(5)), int(m.group(6)),
            )
        except ValueError:
            pass
    return None


def _parse_date_from_ymd(value: str | None) -> date | None:
    """Parse 'YYYYMMDD' → date."""
    if not value:
        return None
    m = _YMD_RE.match(value.strip())
    if m:
        try:
            return date(int(m.group(1)), int(m.group(2)), int(m.group(3)))
        except ValueError:
            pass
    return None


def _parse_date_from_string(value: str | None) -> date | None:
    """Parse 'YYYY.MM.DD HH:MM:SS' or 'YYYY.MM.DD' → date."""
    if not value:
        return None
    m = _DATE_RE.search(value)
    if m:
        try:
            return date(int(m.group(1)), int(m.group(2)), int(m.group(3)))
        except ValueError:
            pass
    return None


# ── Config ────────────────────────────────────────────────────────────────────


@dataclass
class _NaverConfig:
    max_discover: int = _DEFAULT_MAX_DISCOVER
    max_fetch: int = _DEFAULT_MAX_FETCH


def _parse_config(config_json: str | None) -> _NaverConfig:
    if not config_json:
        return _NaverConfig()
    try:
        data = json.loads(config_json)
        return _NaverConfig(
            max_discover=int(data.get("max_discover", _DEFAULT_MAX_DISCOVER)),
            max_fetch=int(data.get("max_fetch", _DEFAULT_MAX_FETCH)),
        )
    except Exception:
        return _NaverConfig()


# ── List item parsing ─────────────────────────────────────────────────────────

def _parse_roles(class_cd: str | None, sub_job_cd: str | None) -> list[str]:
    """Return [classCdNm, subJobCdNm] with duplicates and blanks removed.

    Both values are preserved when they differ (e.g., ["Tech", "Backend"]).
    Neither value is a department or company name — those come from sysCompanyCdNm
    and mojibjubu which are intentionally excluded here.
    """
    seen: set[str] = set()
    roles: list[str] = []
    for v in (class_cd, sub_job_cd):
        s = (v or "").strip()
        if s and s not in seen:
            seen.add(s)
            roles.append(s)
    return roles


@dataclass
class _ListItem:
    anno_id: int
    title: str
    company_name: str
    roles: list[str]
    experience_level: str | None
    employment_type: str | None
    posted_at: datetime | None
    deadline: date | None
    location: str | None
    detail_url: str
    status_code: str


def _parse_list_item(item: dict) -> _ListItem | None:
    """Parse one JSON object from the list API response.

    Returns None if required fields are missing or unparseable.
    """
    try:
        anno_id = int(item["annoId"])
        title = str(item.get("annoSubject", "")).strip()
        if not title:
            return None

        company_name = str(item.get("sysCompanyCdNm", "")).strip() or "NAVER"

        # roles: preserve both class and sub-class when different
        roles = _parse_roles(
            item.get("classCdNm"),
            item.get("subJobCdNm"),
        )

        # experience_level from entTypeCd (more reliable than entTypeCdNm text)
        entry_cd = str(item.get("entTypeCd", "")).strip()
        experience_level = _ENTRY_TYPE_MAP.get(entry_cd)
        if not experience_level:
            # fallback to text label
            raw_exp = str(item.get("entTypeCdNm", "")).strip()
            if raw_exp == "무관":
                experience_level = "경력 무관"
            elif raw_exp:
                experience_level = raw_exp

        # employment_type from empTypeCdNm
        raw_emp = str(item.get("empTypeCdNm", "")).strip()
        employment_type = _EMP_TYPE_MAP.get(raw_emp, raw_emp or None)

        # dates
        posted_at = _parse_datetime(item.get("staYmdTime"))
        deadline = (
            _parse_date_from_string(item.get("endYmdTime"))
            or _parse_date_from_ymd(item.get("endYmd"))
        )

        # location from workAreaCd (code, not name — map via lookup table)
        area_cd = str(item.get("workAreaCd", "")).strip()
        location = _WORK_AREA_MAP.get(area_cd)

        # detail URL: prefer jobDetailLink; construct from annoId as fallback
        job_link = str(item.get("jobDetailLink", "")).strip()
        if not job_link:
            job_link = _DETAIL_URL_TMPL.format(anno_id)

        status_code = str(item.get("stateCd", "")).strip()

        return _ListItem(
            anno_id=anno_id,
            title=title,
            company_name=company_name,
            roles=roles,
            experience_level=experience_level,
            employment_type=employment_type,
            posted_at=posted_at,
            deadline=deadline,
            location=location,
            detail_url=job_link,
            status_code=status_code,
        )
    except (KeyError, ValueError, TypeError) as exc:
        log.debug("naver_careers: list item parse error: %s (item=%s)", exc, item)
        return None


# ── Detail page parsing ───────────────────────────────────────────────────────

# Section headings that indicate required qualifications (not preferred/optional)
_REQUIRED_SECTION_HEADINGS: frozenset[str] = frozenset({
    "Required Skills",
    "필요 역량",
    "자격 요건",
    "필수 요건",
    "지원 자격",
    "What You'll Do",  # contains tasks — may include year mentions
})

_PREFERRED_SECTION_HEADINGS: frozenset[str] = frozenset({
    "Preferred Skills",
    "우대 사항",
    "우대사항",
    "우대 조건",
})


@dataclass
class _DetailResult:
    description: str | None = None
    experience_level: str | None = None  # enriched from detail page
    employment_type: str | None = None  # enriched from detail page


def _extract_experience_from_required_text(text: str) -> str | None:
    """Extract specific year requirements from required-qualifications text only.

    Applies patterns in order of specificity:
      1. N~M년  (range)
      2. N년 이상 (minimum)

    Returns None when no explicit year pattern found.
    """
    m = _EXP_YEAR_RANGE_RE.search(text)
    if m:
        return f"{m.group(1)}~{m.group(2)}년"
    m = _EXP_MIN_RE.search(text)
    if m:
        return f"{m.group(1)}년 이상"
    return None


def _parse_detail_page(html: str) -> _DetailResult:
    """Extract description, experience_level, and employment_type from detail page.

    Parsing strategy:
      - DL card_info → 모집 경력 (experience), 근로 조건 (employment type)
      - detail_wrap div → section content for description (capped at 2000 chars)
      - Required Skills section text → year experience extraction
    """
    soup = BeautifulSoup(html, "html.parser")
    result = _DetailResult()

    # ── Structured metadata from card_info DL ─────────────────────────────────
    for dl in soup.find_all("dl", class_="card_info"):
        dts = dl.find_all("dt")
        for dt in dts:
            label = dt.get_text(strip=True)
            dd = dt.find_next_sibling("dd")
            if not dd:
                continue
            value = dd.get_text(strip=True)
            if "모집 경력" in label:
                if value == "무관":
                    result.experience_level = "경력 무관"
                elif value:
                    result.experience_level = value
            elif "근로 조건" in label:
                result.employment_type = _EMP_TYPE_MAP.get(value, value or None)

    # ── Content from detail_wrap ───────────────────────────────────────────────
    detail_wrap = soup.find("div", class_="detail_wrap")
    if not detail_wrap:
        # Fallback to main/article content
        detail_wrap = soup.find("main") or soup.find("article")

    if detail_wrap:
        # Collect section boxes, accumulating required-section text separately
        required_text_parts: list[str] = []
        all_text_parts: list[str] = []
        in_required = False
        in_preferred = False

        for box in detail_wrap.find_all("div", class_="detail_box"):
            heading_tag = box.find(["h3", "h4", "h2", "p", "strong"])
            heading = heading_tag.get_text(strip=True) if heading_tag else ""

            box_text = box.get_text(separator="\n", strip=True)
            if not box_text:
                continue

            all_text_parts.append(box_text)

            in_required = heading in _REQUIRED_SECTION_HEADINGS
            in_preferred = heading in _PREFERRED_SECTION_HEADINGS

            if in_required and not in_preferred:
                required_text_parts.append(box_text)

        description = "\n\n".join(all_text_parts)
        result.description = description[:2000] if description else None

        # Year extraction only from required sections
        if required_text_parts and result.experience_level in ("경력", "경력 무관", None):
            combined = "\n".join(required_text_parts)
            inferred = _extract_experience_from_required_text(combined)
            if inferred:
                result.experience_level = inferred

    return result


# ── Adapter ───────────────────────────────────────────────────────────────────


class NaverCareersParser(CustomParser):
    """Collects job postings from recruit.navercorp.com via the public list API.

    Pagination:
      The API ignores pageSize — it always returns 10 items per page.
      We iterate page=1,2,... until we have collected max_discover items or
      accumulated >= totalSize.

    Detail fetch:
      Up to max_fetch detail pages are fetched for description and experience
      enrichment.  Detail failures add warnings but do not drop the posting.
    """

    async def fetch(
        self,
        source: OfficialCompanySource,
        profile: CompanyProfile | None,
        options: CollectionOptions,
        collect_date: date,
    ) -> AdapterResult:
        config = _parse_config(source.config_json)
        sid = f"company_{source.company_id}_custom_naver_careers"
        timeout = settings.job_collection_timeout_seconds
        warnings: list[str] = []

        try:
            async with AsyncClient(
                timeout=timeout,
                headers={
                    "User-Agent": (
                        "Mozilla/5.0 (compatible; Briefy-Agent/1.0; "
                        "+https://briefy.io)"
                    ),
                    "Accept": "application/json, text/javascript, */*; q=0.01",
                    "Referer": "https://recruit.navercorp.com/rcrt/list.do",
                },
                follow_redirects=True,
            ) as client:
                # ── Stage 1: paginate list API ─────────────────────────────────
                items: list[_ListItem] = []
                total_size: int | None = None
                page = 1
                # Guard against malformed totalSize causing runaway loops.
                # Maximum pages = ceil(max_discover / _PAGE_SIZE) + 1 safety page.
                max_pages = (config.max_discover // _PAGE_SIZE) + 2

                while len(items) < config.max_discover and page <= max_pages:
                    try:
                        resp = await client.get(
                            _LIST_URL, params={"page": page, "pageSize": _PAGE_SIZE}
                        )
                        resp.raise_for_status()
                    except TimeoutException:
                        warnings.append(
                            f"naver_careers: list page {page} timeout"
                            f" (company_id={source.company_id})"
                        )
                        break
                    except HTTPStatusError as exc:
                        warnings.append(
                            f"naver_careers: list page {page} HTTP"
                            f" {exc.response.status_code}"
                            f" (company_id={source.company_id})"
                        )
                        break

                    try:
                        payload = resp.json()
                    except Exception:
                        warnings.append(
                            f"naver_careers: list page {page} JSON parse error"
                            f" (company_id={source.company_id})"
                        )
                        break

                    if payload.get("result") != "Y":
                        warnings.append(
                            f"naver_careers: list page {page} result!='Y'"
                            f" (company_id={source.company_id})"
                        )
                        break

                    if total_size is None:
                        try:
                            total_size = int(payload["totalSize"])
                        except (KeyError, ValueError, TypeError):
                            warnings.append(
                                f"naver_careers: missing totalSize"
                                f" (company_id={source.company_id})"
                            )
                            break

                    raw_list = payload.get("list")
                    if not isinstance(raw_list, list):
                        warnings.append(
                            f"naver_careers: list field not an array page={page}"
                            f" (company_id={source.company_id})"
                        )
                        break

                    if not raw_list:
                        break  # no more pages

                    parsed_count_before = len(items)
                    for raw_item in raw_list:
                        if len(items) >= config.max_discover:
                            break
                        parsed = _parse_list_item(raw_item)
                        if parsed is not None:
                            items.append(parsed)
                        else:
                            warnings.append(
                                f"naver_careers: skipped malformed item"
                                f" annoId={raw_item.get('annoId', '?')}"
                            )

                    # Stop when we've seen all available postings
                    if total_size is not None and len(items) >= total_size:
                        break
                    # Stop if this page returned fewer than _PAGE_SIZE
                    # (last page signal)
                    if len(raw_list) < _PAGE_SIZE:
                        break

                    page += 1

                discovered = len(items)

                if discovered == 0:
                    if total_size == 0:
                        # Genuine empty response — not a failure
                        return AdapterResult(
                            postings=[],
                            warnings=warnings,
                            source_stats=AdapterSourceStats(
                                discovered=0, fetched=0, parsed=0, selected=0
                            ),
                        )
                    # Could be a parse failure — emit warning
                    if not warnings:
                        warnings.append(
                            f"naver_careers: 0 items parsed"
                            f" (company_id={source.company_id},"
                            f" total_size={total_size})"
                        )

                # ── Stage 2: detail page fetch for enrichment ──────────────────
                to_fetch = items[: config.max_fetch]
                postings: list[RawJobPosting] = []

                for list_item in items:
                    enrich: _DetailResult | None = None

                    if list_item in to_fetch:
                        try:
                            detail_resp = await client.get(list_item.detail_url)
                            detail_resp.raise_for_status()
                            enrich = _parse_detail_page(detail_resp.text)
                        except TimeoutException:
                            warnings.append(
                                f"naver_careers: detail timeout:"
                                f" {list_item.detail_url}"
                            )
                        except HTTPStatusError as exc:
                            warnings.append(
                                f"naver_careers: detail HTTP"
                                f" {exc.response.status_code}:"
                                f" {list_item.detail_url}"
                            )
                        except Exception as exc:
                            warnings.append(
                                f"naver_careers: detail error"
                                f" {list_item.detail_url}: {exc}"
                            )

                    # Experience enrichment: detail value wins when more specific
                    experience_level = list_item.experience_level
                    if enrich and enrich.experience_level:
                        detail_exp = enrich.experience_level
                        # Detail is more specific when it contains a year count
                        if re.search(r"\d+", detail_exp):
                            experience_level = detail_exp
                        elif detail_exp not in ("경력", None):
                            # prefer more informative non-generic value
                            experience_level = detail_exp

                    employment_type = list_item.employment_type
                    if enrich and enrich.employment_type:
                        employment_type = enrich.employment_type

                    description = enrich.description if enrich else None

                    postings.append(
                        RawJobPosting(
                            source=sid,
                            source_url=list_item.detail_url,
                            company_name=list_item.company_name,
                            title=list_item.title,
                            employment_type=employment_type,
                            experience_level=experience_level,
                            location=list_item.location,
                            deadline=list_item.deadline,
                            skills=[],
                            roles=list_item.roles,
                            description=description,
                            posted_at=list_item.posted_at,
                            source_external_id=str(list_item.anno_id),
                        )
                    )

        except Exception as exc:
            msg = (
                f"naver_careers: unexpected error"
                f" company_id={source.company_id}: {exc}"
            )
            log.warning(msg)
            return AdapterResult(
                warnings=[msg],
                source_stats=AdapterSourceStats(),
            )

        stats = AdapterSourceStats(
            discovered=discovered,
            fetched=len(to_fetch),
            parsed=len(postings),
            selected=len(postings),
        )
        return AdapterResult(postings=postings, warnings=warnings, source_stats=stats)


# Self-registration on first import.
_CUSTOM_REGISTRY_BY_KEY["NAVER_CAREERS"] = NaverCareersParser()


# ── Preflight ──────────────────────────────────────────────────────────────────


async def naver_preflight(source: OfficialCompanySource) -> dict:
    """Check Naver recruit API reachability and parse a sample job.

    Returns dict: reachable, discovered_count, sample_parsed, warnings.
    """
    timeout = settings.job_collection_timeout_seconds
    warnings: list[str] = []

    try:
        async with AsyncClient(
            timeout=timeout,
            headers={
                "User-Agent": (
                    "Mozilla/5.0 (compatible; Briefy-Agent/1.0; +https://briefy.io)"
                ),
                "Accept": "application/json, text/javascript, */*; q=0.01",
                "Referer": "https://recruit.navercorp.com/rcrt/list.do",
            },
            follow_redirects=True,
        ) as client:
            try:
                resp = await client.get(
                    _LIST_URL, params={"page": 1, "pageSize": _PAGE_SIZE}
                )
                resp.raise_for_status()
            except (TimeoutException, HTTPStatusError) as exc:
                return {
                    "reachable": False,
                    "discovered_count": 0,
                    "sample_parsed": None,
                    "warnings": [str(exc)],
                }

            try:
                data = resp.json()
            except Exception:
                return {
                    "reachable": False,
                    "discovered_count": 0,
                    "sample_parsed": None,
                    "warnings": ["JSON parse error"],
                }

            if data.get("result") != "Y":
                return {
                    "reachable": False,
                    "discovered_count": 0,
                    "sample_parsed": None,
                    "warnings": [f"unexpected result value: {data.get('result')!r}"],
                }
            total_size = int(data.get("totalSize") or 0)
            raw_list = data.get("list") or []

            discovered_count = total_size
            sample_parsed = None

            if raw_list:
                item = _parse_list_item(raw_list[0])
                if item is not None:
                    sample_parsed = {
                        "title": item.title,
                        "company_name": item.company_name,
                        "employment_type": item.employment_type,
                        "experience_level": item.experience_level,
                        "location": item.location,
                        "deadline": str(item.deadline) if item.deadline else None,
                        "roles": item.roles,
                        "source_external_id": str(item.anno_id),
                    }
                else:
                    warnings.append("first list item failed to parse")

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
