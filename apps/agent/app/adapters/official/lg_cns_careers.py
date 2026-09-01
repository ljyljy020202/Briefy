"""LG CNS 공식 채용 어댑터 (LG_CNS_CAREERS).

API: https://api.careers.lg.com/rmk  (React SPA의 axios baseURL)
  목록: POST /job/retrieveJobNoticesList  — companyCodeList: ["CNS"]
  상세: POST /job/retrieveJobNoticesDetail — jobNoticeId 단건 조회

robots.txt (careers.lg.com/robots.txt):
  User-agent: *  → Briefy-Agent/1.0 허용.
  ClaudeBot 전용 Disallow: / 는 무관.

인증·세션 불필요. 24건 전체 단일 응답(페이지네이션 없음).
listCount 필드로 0건과 네트워크 오류 구분 가능.

config_json:
  {
    "parser_key": "LG_CNS_CAREERS",
    "max_discover": 50,   // 목록 상한
    "max_fetch": 30       // 상세 조회 상한
  }

Career type codes (LG):
  A = 신입  B = 경력  C = 인턴  D = 신입·경력  E = 산학장학생

LG 관계사 혼입 방지:
  listing 시 companyCodeList: ["CNS"] 필터 적용.
  detail 응답의 companyName == "LG CNS" 이중 확인.
"""

from __future__ import annotations

import json
import logging
import re
from dataclasses import dataclass
from datetime import date, datetime
from typing import Any

import httpx

from app.adapters.base import AdapterResult, AdapterSourceStats, RawJobPosting
from app.adapters.official_company import _CUSTOM_REGISTRY_BY_KEY, CustomParser
from app.schemas.collection import (
    CollectionOptions,
    CompanyProfile,
    OfficialCompanySource,
    SeedKeywords,
)

log = logging.getLogger(__name__)

_SOURCE = "lg_cns_careers"
_LIST_EP = "https://api.careers.lg.com/rmk/job/retrieveJobNoticesList"
_DETAIL_EP = "https://api.careers.lg.com/rmk/job/retrieveJobNoticesDetail"
_POSTING_URL = "https://careers.lg.com/apply/{}"
_HEADERS = {
    "User-Agent": "Briefy-Agent/1.0 (+https://briefy.io)",
    "Content-Type": "application/json",
    "Accept": "application/json",
    "Origin": "https://careers.lg.com",
    "Referer": "https://careers.lg.com/apply",
}
_TIMEOUT = 15.0
_LIST_BODY: dict[str, Any] = {
    "lnbSearch": "",
    "hashTagText": "",
    "recDate": "CREATION_DATE",
    "order": "DESC",
    "careerList": [],
    "companyCodeList": ["CNS"],
    "desireLocList": [],
    "jobGroupList": [],
}

# LG API 대분류 레이블 — roles에 포함하지 않음
_BROAD_CATEGORIES: frozenset[str] = frozenset(
    {"IT서비스", "IT Service", "경영지원", "영업/마케팅"}
)

# careerTypeCode → experience_level
_CAREER_MAP: dict[str, str] = {
    "A": "신입",
    "B": "경력",
    "C": "인턴",
    "D": "신입·경력",
    "E": "산학장학생",
}


@dataclass
class _RecInfo:
    job_group_name: str
    location_name: str | None


# ---------------------------------------------------------------------------
# Pure helpers (no I/O — testable in isolation)
# ---------------------------------------------------------------------------


def _parse_config(config_json: str | None) -> tuple[int, int]:
    data: dict[str, Any] = {}
    if config_json:
        try:
            data = json.loads(config_json)
        except json.JSONDecodeError:
            pass
    max_discover = int(data.get("max_discover", 50))
    max_fetch = int(data.get("max_fetch", 30))
    return max_discover, max_fetch


def _parse_date(s: str | None) -> date | None:
    """Parse '2026.08.31 23:00' or '2026.08.31' → date. None on failure."""
    if not s:
        return None
    m = re.search(r"(\d{4})\.(\d{2})\.(\d{2})", s)
    if not m:
        return None
    try:
        return date(int(m.group(1)), int(m.group(2)), int(m.group(3)))
    except ValueError:
        return None


def _experience_level(code: str, name: str) -> str:
    return _CAREER_MAP.get(code, name)


def _employment_type(code: str) -> str:
    return "인턴" if code == "C" else "정규직"


def _parse_rec_list(raw: list[dict[str, Any]]) -> list[_RecInfo]:
    result = []
    for item in raw:
        jgn = (item.get("jobGroupName") or "").strip()
        loc = (item.get("locationName") or "").strip() or None
        result.append(_RecInfo(job_group_name=jgn, location_name=loc))
    return result


def _roles_from_recs(recs: list[_RecInfo]) -> list[str]:
    """Return unique, specific role names from recList (excludes broad categories)."""
    seen: set[str] = set()
    roles: list[str] = []
    for rec in recs:
        name = rec.job_group_name
        if name and name not in _BROAD_CATEGORIES and name not in seen:
            seen.add(name)
            roles.append(name)
    return roles


def _location_from_recs(recs: list[_RecInfo]) -> str | None:
    for rec in recs:
        if rec.location_name:
            return rec.location_name
    return None


def _build_posting(
    job_id: int,
    list_item: dict[str, Any],
    detail: dict[str, Any],
    recs: list[_RecInfo],
    company_profile: CompanyProfile,
) -> RawJobPosting:
    title = detail.get("jobNoticeName") or list_item.get("jobNoticeName", "")
    career_code = detail.get("careerTypeCode") or list_item.get("careerTypeCode", "")
    career_name = detail.get("careerTypeName") or list_item.get("careerTypeName", "")
    qual = (detail.get("qualForAppInfo") or "").strip()

    posted_date = _parse_date(detail.get("recStartDate"))
    posted_at: datetime | None = (
        datetime(posted_date.year, posted_date.month, posted_date.day)
        if posted_date
        else None
    )

    return RawJobPosting(
        source=_SOURCE,
        source_url=_POSTING_URL.format(job_id),
        source_external_id=str(job_id),
        company_name=company_profile.canonical_name,
        title=title,
        roles=_roles_from_recs(recs),
        location=_location_from_recs(recs),
        experience_level=_experience_level(career_code, career_name),
        employment_type=_employment_type(career_code),
        posted_at=posted_at,
        deadline=_parse_date(detail.get("recEndDate")),
        description=qual or None,
    )


# ---------------------------------------------------------------------------
# Parser
# ---------------------------------------------------------------------------


class LgCnsCareersParser(CustomParser):
    """LG CNS 공식 채용 페이지 어댑터."""

    async def fetch(
        self,
        source: OfficialCompanySource,
        company_profile: CompanyProfile,
        seed_keywords: SeedKeywords,
        options: CollectionOptions,
    ) -> AdapterResult:
        max_discover, max_fetch = _parse_config(source.config_json)
        postings: list[RawJobPosting] = []
        warnings: list[str] = []

        # Stage 1: 목록 조회
        async with httpx.AsyncClient(headers=_HEADERS, timeout=_TIMEOUT) as client:
            try:
                r = await client.post(_LIST_EP, json=_LIST_BODY)
                r.raise_for_status()
            except Exception as exc:
                warnings.append(f"LG_CNS_CAREERS list failed: {exc}")
                return AdapterResult(
                    postings=[],
                    warnings=warnings,
                    source_stats=AdapterSourceStats(),
                )

        data = r.json().get("data", {})
        jobs: list[dict[str, Any]] = data.get("jobNoticeList", [])
        discovered = len(jobs)
        log.info("LG_CNS_CAREERS: discovered %d postings", discovered)
        jobs = jobs[:max_discover]

        # Stage 2: 상세 조회
        fetched = 0
        parsed = 0
        for item in jobs[:max_fetch]:
            job_id = int(item["jobNoticeId"])
            if item.get("companyCode") != "CNS":
                warnings.append(
                    f"LG_CNS_CAREERS: skipped {job_id} — "
                    f"companyCode={item.get('companyCode')!r}"
                )
                continue

            try:
                async with httpx.AsyncClient(headers=_HEADERS, timeout=_TIMEOUT) as dc:
                    dr = await dc.post(_DETAIL_EP, json={"jobNoticeId": job_id})
                    dr.raise_for_status()
                fetched += 1
            except Exception as exc:
                warnings.append(f"LG_CNS_CAREERS: detail failed for {job_id}: {exc}")
                continue

            wrapper = dr.json().get("data", {}).get("jobNoticesDetail", {})
            detail = wrapper.get("jobNoticesDetail") or {}
            raw_recs: list[dict[str, Any]] = wrapper.get("recList") or []

            if detail.get("companyName") != "LG CNS":
                warnings.append(
                    f"LG_CNS_CAREERS: skipped {job_id} — "
                    f"companyName={detail.get('companyName')!r}"
                )
                continue

            recs = _parse_rec_list(raw_recs)
            try:
                posting = _build_posting(job_id, item, detail, recs, company_profile)
                postings.append(posting)
                parsed += 1
            except Exception as exc:
                warnings.append(f"LG_CNS_CAREERS: parse failed for {job_id}: {exc}")

        return AdapterResult(
            postings=postings,
            warnings=warnings,
            source_stats=AdapterSourceStats(
                discovered=discovered,
                fetched=fetched,
                parsed=parsed,
                selected=len(postings),
            ),
        )


_CUSTOM_REGISTRY_BY_KEY["LG_CNS_CAREERS"] = LgCnsCareersParser()


# ---------------------------------------------------------------------------
# Preflight probe
# ---------------------------------------------------------------------------


async def lg_cns_preflight(source: OfficialCompanySource) -> dict[str, Any]:
    """Bounded probe for official_source_preflight — no side effects."""
    _parse_config(source.config_json)  # validate config; raises on bad input
    result: dict[str, Any] = {
        "reachable": False,
        "discovered_count": 0,
        "sample_parsed": False,
        "warnings": [],
    }

    try:
        async with httpx.AsyncClient(headers=_HEADERS, timeout=_TIMEOUT) as client:
            r = await client.post(_LIST_EP, json=_LIST_BODY)
    except Exception as exc:
        result["warnings"].append(f"LG_CNS_CAREERS list unreachable: {exc}")
        result["failure_code"] = "NETWORK_ERROR"
        return result

    if r.status_code != 200:
        result["warnings"].append(f"LG_CNS_CAREERS list HTTP {r.status_code}")
        result["failure_code"] = f"HTTP_{r.status_code}"
        return result

    result["reachable"] = True
    jobs: list[dict[str, Any]] = r.json().get("data", {}).get("jobNoticeList", [])
    cns_jobs = [j for j in jobs if j.get("companyCode") == "CNS"]
    result["discovered_count"] = len(cns_jobs)

    if not cns_jobs:
        result["warnings"].append(
            "LG_CNS_CAREERS: 0 active postings for companyCode=CNS"
        )
        result["failure_code"] = "NO_ITEMS_DISCOVERED"
        return result

    job_id = int(cns_jobs[0]["jobNoticeId"])
    try:
        async with httpx.AsyncClient(headers=_HEADERS, timeout=_TIMEOUT) as client:
            dr = await client.post(_DETAIL_EP, json={"jobNoticeId": job_id})
    except Exception as exc:
        result["warnings"].append(f"LG_CNS_CAREERS: detail unreachable: {exc}")
        result["failure_code"] = "DETAIL_NETWORK_ERROR"
        return result

    if dr.status_code != 200:
        result["warnings"].append(f"LG_CNS_CAREERS: detail HTTP {dr.status_code}")
        result["failure_code"] = f"DETAIL_HTTP_{dr.status_code}"
        return result

    wrapper = dr.json().get("data", {}).get("jobNoticesDetail", {})
    detail = wrapper.get("jobNoticesDetail") or {}
    raw_recs: list[dict[str, Any]] = wrapper.get("recList") or []

    if not detail.get("jobNoticeId") or detail.get("companyName") != "LG CNS":
        result["warnings"].append(
            f"LG_CNS_CAREERS: sample detail validation failed — "
            f"companyName={detail.get('companyName')!r}"
        )
        result["failure_code"] = "REQUIRED_FIELD_PARSE_FAILED"
        return result

    recs = _parse_rec_list(raw_recs)
    result["sample_parsed"] = True
    result["sample_roles"] = _roles_from_recs(recs)
    result["sample_location"] = _location_from_recs(recs)
    return result
