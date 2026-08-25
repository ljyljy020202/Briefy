"""LG 공식 채용 어댑터 (LG_CAREERS).

LG Careers API를 공용으로 사용하는 어댑터.
config_json의 company_code로 LG전자(LGE), LG유플러스(LGU),
LG에너지솔루션(LGES), LG이노텍(LGIT) 등 관계사를 구분한다.

API (기존 LG_CNS_CAREERS 와 동일):
  목록: POST https://api.careers.lg.com/rmk/job/retrieveJobNoticesList
  상세: POST https://api.careers.lg.com/rmk/job/retrieveJobNoticesDetail

config_json 계약:
  {
    "parser_key": "LG_CAREERS",
    "company_code": "LGE",               // companyCodeList 필터값 (필수)
    "expected_company_name": "LG전자",    // detail companyName 재검증 (선택)
    "max_discover": 50,
    "max_fetch": 30
  }

혼입 방지:
  1. 목록 항목의 companyCode != company_code → skip + warning
  2. 상세 응답의 companyName != expected_company_name (설정된 경우) → skip + warning

기존 LG_CNS_CAREERS 호환:
  이 파서는 "LG_CAREERS" 키로만 등록된다.
  기존 "LG_CNS_CAREERS" 파서와 등록 키가 다르므로 충돌 없음.

Pure helper functions 공유:
  _RecInfo, _parse_date, _experience_level, _employment_type,
  _parse_rec_list, _roles_from_recs, _location_from_recs 를
  lg_cns_careers 에서 가져온다.  이 함수들은 이미 독립 단위 테스트로
  검증되어 있으며 LG Careers API 응답 구조가 동일하므로 안전하게 재사용한다.
"""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass
from datetime import date, datetime
from typing import Any

import httpx

from app.adapters.base import AdapterResult, AdapterSourceStats, RawJobPosting
from app.adapters.official.lg_cns_careers import (
    _employment_type,
    _experience_level,
    _location_from_recs,
    _parse_date,
    _parse_rec_list,
    _RecInfo,
    _roles_from_recs,
)
from app.adapters.official_company import _CUSTOM_REGISTRY_BY_KEY, CustomParser
from app.schemas.collection import (
    CollectionOptions,
    CompanyProfile,
    OfficialCompanySource,
)

log = logging.getLogger(__name__)

_SOURCE = "lg_careers"
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


# ---------------------------------------------------------------------------
# Config
# ---------------------------------------------------------------------------


@dataclass
class _LgCareersConfig:
    company_code: str
    expected_company_name: str | None
    max_discover: int
    max_fetch: int


def _parse_config(config_json: str | None) -> _LgCareersConfig:
    data: dict[str, Any] = {}
    if config_json:
        try:
            data = json.loads(config_json)
        except json.JSONDecodeError:
            pass
    company_code = (data.get("company_code") or "").strip().upper()
    raw_expected = (data.get("expected_company_name") or "").strip()
    expected_company_name = raw_expected or None
    max_discover = int(data.get("max_discover", 50))
    max_fetch = int(data.get("max_fetch", 30))
    return _LgCareersConfig(
        company_code=company_code,
        expected_company_name=expected_company_name,
        max_discover=max_discover,
        max_fetch=max_fetch,
    )


# ---------------------------------------------------------------------------
# Pure helpers
# ---------------------------------------------------------------------------


def _build_posting(
    job_id: int,
    list_item: dict[str, Any],
    detail: dict[str, Any],
    recs: list[_RecInfo],
    company_name: str,
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
        company_name=company_name,
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


class LgCareersParser(CustomParser):
    """LG Careers 공식 채용 공용 파서.

    company_code / expected_company_name 으로 회사를 구분한다.
    레지스트리에 단일 인스턴스로 등록되고 source config_json 으로 파라미터를 받는다.
    """

    async def fetch(
        self,
        source: OfficialCompanySource,
        profile: CompanyProfile | None,
        options: CollectionOptions,
        collect_date: date,
    ) -> AdapterResult:
        config = _parse_config(source.config_json)
        company_name: str = (
            (profile.canonical_name if profile else None)
            or config.expected_company_name
            or "LG"
        )
        postings: list[RawJobPosting] = []
        warnings: list[str] = []

        if not config.company_code:
            warnings.append(
                f"LG_CAREERS: company_code not configured "
                f"(company_id={source.company_id})"
            )
            return AdapterResult(
                postings=[],
                warnings=warnings,
                source_stats=AdapterSourceStats(),
            )

        list_body: dict[str, Any] = {
            "lnbSearch": "",
            "hashTagText": "",
            "recDate": "CREATION_DATE",
            "order": "DESC",
            "careerList": [],
            "companyCodeList": [config.company_code],
            "desireLocList": [],
            "jobGroupList": [],
        }

        # ── Stage 1: 목록 조회 ────────────────────────────────────────────────
        async with httpx.AsyncClient(headers=_HEADERS, timeout=_TIMEOUT) as client:
            try:
                r = await client.post(_LIST_EP, json=list_body)
                r.raise_for_status()
            except Exception as exc:
                warnings.append(
                    f"LG_CAREERS list failed [{config.company_code}]: {exc}"
                )
                return AdapterResult(
                    postings=[],
                    warnings=warnings,
                    source_stats=AdapterSourceStats(),
                )

        try:
            resp_data = r.json().get("data", {})
            jobs: list[dict[str, Any]] = resp_data.get("jobNoticeList", [])
        except Exception as exc:
            warnings.append(
                f"LG_CAREERS list JSON parse failed [{config.company_code}]: {exc}"
            )
            return AdapterResult(
                postings=[],
                warnings=warnings,
                source_stats=AdapterSourceStats(),
            )

        discovered = len(jobs)
        log.info(
            "LG_CAREERS [%s]: discovered %d postings",
            config.company_code,
            discovered,
        )
        jobs = jobs[: config.max_discover]

        # ── Stage 2: 상세 조회 ────────────────────────────────────────────────
        seen_ids: set[int] = set()
        fetched = 0
        parsed = 0

        for item in jobs[: config.max_fetch]:
            job_id = int(item["jobNoticeId"])

            # 중복 jobNoticeId 제거
            if job_id in seen_ids:
                warnings.append(
                    f"LG_CAREERS: duplicate jobNoticeId={job_id} skipped "
                    f"[{config.company_code}]"
                )
                continue
            seen_ids.add(job_id)

            # 목록 단계의 companyCode 검증
            if item.get("companyCode") != config.company_code:
                warnings.append(
                    f"LG_CAREERS: skipped {job_id} — "
                    f"companyCode={item.get('companyCode')!r} "
                    f"expected={config.company_code!r}"
                )
                continue

            # 상세 조회
            try:
                async with httpx.AsyncClient(
                    headers=_HEADERS, timeout=_TIMEOUT
                ) as dc:
                    dr = await dc.post(_DETAIL_EP, json={"jobNoticeId": job_id})
                    dr.raise_for_status()
                fetched += 1
            except Exception as exc:
                warnings.append(
                    f"LG_CAREERS: detail failed for {job_id} "
                    f"[{config.company_code}]: {exc}"
                )
                continue

            # 상세 JSON 파싱
            try:
                wrapper = dr.json().get("data", {}).get("jobNoticesDetail", {})
            except Exception as exc:
                warnings.append(
                    f"LG_CAREERS: detail JSON parse failed for {job_id} "
                    f"[{config.company_code}]: {exc}"
                )
                continue

            detail = wrapper.get("jobNoticesDetail") or {}
            raw_recs: list[dict[str, Any]] = wrapper.get("recList") or []

            # 상세 단계의 companyName 재검증 (expected_company_name 설정 시)
            if config.expected_company_name:
                detail_company = detail.get("companyName") or ""
                if detail_company != config.expected_company_name:
                    warnings.append(
                        f"LG_CAREERS: skipped {job_id} — "
                        f"companyName={detail_company!r} "
                        f"expected={config.expected_company_name!r}"
                    )
                    continue

            recs = _parse_rec_list(raw_recs)
            try:
                posting = _build_posting(job_id, item, detail, recs, company_name)
                postings.append(posting)
                parsed += 1
            except Exception as exc:
                warnings.append(
                    f"LG_CAREERS: parse failed for {job_id} "
                    f"[{config.company_code}]: {exc}"
                )

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


_CUSTOM_REGISTRY_BY_KEY["LG_CAREERS"] = LgCareersParser()


# ---------------------------------------------------------------------------
# Preflight probe
# ---------------------------------------------------------------------------


async def lg_careers_preflight(
    source: OfficialCompanySource,
) -> dict[str, Any]:
    """Bounded preflight probe for LG_CAREERS — no side effects."""
    config = _parse_config(source.config_json)
    result: dict[str, Any] = {
        "company_code": config.company_code,
        "reachable": False,
        "discovered_count": 0,
        "sample_parsed": False,
        "warnings": [],
    }

    if not config.company_code:
        result["warnings"].append("LG_CAREERS: company_code not set in config_json")
        result["failure_code"] = "MISSING_COMPANY_CODE"
        return result

    list_body: dict[str, Any] = {
        "lnbSearch": "",
        "hashTagText": "",
        "recDate": "CREATION_DATE",
        "order": "DESC",
        "careerList": [],
        "companyCodeList": [config.company_code],
        "desireLocList": [],
        "jobGroupList": [],
    }

    try:
        async with httpx.AsyncClient(headers=_HEADERS, timeout=_TIMEOUT) as client:
            r = await client.post(_LIST_EP, json=list_body)
    except Exception as exc:
        result["warnings"].append(
            f"LG_CAREERS list unreachable [{config.company_code}]: {exc}"
        )
        result["failure_code"] = "NETWORK_ERROR"
        return result

    if r.status_code != 200:
        result["warnings"].append(
            f"LG_CAREERS list HTTP {r.status_code} [{config.company_code}]"
        )
        result["failure_code"] = f"HTTP_{r.status_code}"
        return result

    result["reachable"] = True

    try:
        jobs: list[dict[str, Any]] = (
            r.json().get("data", {}).get("jobNoticeList", [])
        )
    except Exception as exc:
        result["warnings"].append(f"LG_CAREERS list JSON parse error: {exc}")
        result["failure_code"] = "JSON_PARSE_ERROR"
        return result

    filtered = [j for j in jobs if j.get("companyCode") == config.company_code]
    result["discovered_count"] = len(filtered)

    if not filtered:
        result["warnings"].append(
            f"LG_CAREERS: 0 active postings for companyCode={config.company_code}"
        )
        result["failure_code"] = "NO_ITEMS_DISCOVERED"
        return result

    job_id = int(filtered[0]["jobNoticeId"])
    try:
        async with httpx.AsyncClient(headers=_HEADERS, timeout=_TIMEOUT) as client:
            dr = await client.post(_DETAIL_EP, json={"jobNoticeId": job_id})
    except Exception as exc:
        result["warnings"].append(
            f"LG_CAREERS: detail unreachable [{config.company_code}]: {exc}"
        )
        result["failure_code"] = "DETAIL_NETWORK_ERROR"
        return result

    if dr.status_code != 200:
        result["warnings"].append(
            f"LG_CAREERS: detail HTTP {dr.status_code} [{config.company_code}]"
        )
        result["failure_code"] = f"DETAIL_HTTP_{dr.status_code}"
        return result

    try:
        wrapper = dr.json().get("data", {}).get("jobNoticesDetail", {})
        detail = wrapper.get("jobNoticesDetail") or {}
        raw_recs: list[dict[str, Any]] = wrapper.get("recList") or []
    except Exception as exc:
        result["warnings"].append(
            f"LG_CAREERS: detail JSON parse error [{config.company_code}]: {exc}"
        )
        result["failure_code"] = "DETAIL_JSON_PARSE_ERROR"
        return result

    if config.expected_company_name:
        detail_company = detail.get("companyName") or ""
        if detail_company != config.expected_company_name:
            result["warnings"].append(
                f"LG_CAREERS: sample companyName mismatch — "
                f"got={detail_company!r} expected={config.expected_company_name!r}"
            )
            result["failure_code"] = "COMPANY_NAME_MISMATCH"
            return result

    recs = _parse_rec_list(raw_recs)
    result["sample_parsed"] = True
    result["sample_roles"] = _roles_from_recs(recs)
    result["sample_location"] = _location_from_recs(recs)
    result["sample_title"] = (
        detail.get("jobNoticeName") or filtered[0].get("jobNoticeName")
    )
    return result
