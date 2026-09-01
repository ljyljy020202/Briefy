"""HYUNDAI_MOTOR_CAREERS parser — talent.hyundai.com.

Registered as "HYUNDAI_MOTOR_CAREERS".

Investigation summary (2026-08-25):
  talent.hyundai.com is a jQuery/Babel app with a REST JSON API at /api/.
  The main listing page (/apply/applyList.hc) is protected by NetFUNNEL.
  However, the Career Theme Hall (/theme/hall.hc) and its backend APIs are
  NOT protected by NetFUNNEL and return data with no auth required.

  The theme hall covers two channels:
    jdRecuCate=01 — 월간 경력 채용 (monthly experienced hire)
    jdRecuCate=02 — 집중 상시 채용 (always-open experienced hire)

  Company filter: logoNm == "현대" — excludes Genesis (로고 "01") if any
  appear. Hyundai Autoever and other group affiliates do NOT appear in the
  theme hall listing (hgrCd=1, theme hall is HMC-only).

  Confirmed public (2026-08-25):
    22 active postings (20 tab01 + 2 tab02), all logoNm=현대
    No auth, cookies, or NetFUNNEL bypass required

API (no auth required):
  List:   GET /api/rec/AP-HM-FO-02730
          params: hgrCd=1, lang=ko, secCode=, jdRecuCate={01|02}, secLoad=Y
          → JSON: {status, data: {applyList, cnt, themaInfo, fldList, secList}}

  Detail: GET /api/rec/AP-HM-FO-02800
          params: hgrCd=1, lang=ko, recuYy, recuType, recuCls
          → JSON: {status, data: {applyInfo: {..., privJdDtl, privMustReq}}}

config_json contract:
  {
    "parser_key": "HYUNDAI_MOTOR_CAREERS",
    "max_fetch": 50      // posting cap across both tabs (default 50)
  }

source_external_id: "{recuYy}_{recuType}_{recuCls}" (e.g. "2026_N2_295")
source_url: https://talent.hyundai.com/apply/applyView.hc?recuYy=...&recuType=...&recuCls=...
"""

from __future__ import annotations

import json
import logging
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
)

log = logging.getLogger(__name__)

_SOURCE = "hyundai_motor_careers"
_LIST_URL = "https://talent.hyundai.com/api/rec/AP-HM-FO-02730"
_DETAIL_URL = "https://talent.hyundai.com/api/rec/AP-HM-FO-02800"
_SOURCE_URL_TMPL = (
    "https://talent.hyundai.com/apply/applyView.hc"
    "?recuYy={recuYy}&recuType={recuType}&recuCls={recuCls}"
)
_TIMEOUT = 15.0
_HEADERS = {
    "User-Agent": "Briefy-Agent/1.0 (+https://briefy.io)",
    "Accept": "application/json, text/plain, */*",
    "X-HKMC-SERVICE": "HM",
    "X-HKMC-TOKEN": "",
    "Referer": "https://talent.hyundai.com/theme/hall.hc",
    "Content-Type": "application/x-www-form-urlencoded",
}
_HGR_CD = "1"
_LANG = "ko"
_HYUNDAI_LOGO_NM = "현대"
_TABS = ("01", "02")


# ── Config ─────────────────────────────────────────────────────────────────────


@dataclass
class _HyundaiConfig:
    max_fetch: int


def _parse_config(config_json: str | None) -> _HyundaiConfig:
    data: dict[str, Any] = {}
    if config_json:
        try:
            data = json.loads(config_json)
        except json.JSONDecodeError:
            pass
    return _HyundaiConfig(max_fetch=int(data.get("max_fetch") or 50))


# ── Pure helpers ───────────────────────────────────────────────────────────────


def _source_external_id(item: dict[str, Any]) -> str:
    """Stable composite key: recuYy_recuType_recuCls."""
    return (
        f"{item.get('recuYy', '')}_{item.get('recuType', '')}_{item.get('recuCls', '')}"
    )


def _source_url(item: dict[str, Any]) -> str:
    return _SOURCE_URL_TMPL.format(
        recuYy=item.get("recuYy", ""),
        recuType=item.get("recuType", ""),
        recuCls=item.get("recuCls", ""),
    )


def _parse_date_yyyymmdd(raw: str | None) -> date | None:
    if not raw or len(str(raw)) < 8:
        return None
    s = str(raw).strip()
    try:
        return datetime.strptime(s[:8], "%Y%m%d").date()
    except ValueError:
        return None


def _experience_level(channel_code_nm: str | None) -> str | None:
    t = (channel_code_nm or "").strip()
    mapping = {
        "경력": "경력",
        "신입": "신입",
        "신입/경력": "신입/경력",
        "경력/신입": "신입/경력",
        "인턴": "인턴",
    }
    return mapping.get(t) or (t or None)


def _roles(item: dict[str, Any]) -> list[str]:
    """Collect non-empty role strings from fldCodeNm."""
    roles: list[str] = []
    fld = (item.get("fldCodeNm") or "").strip()
    if fld:
        roles.append(fld)
    return roles


def _description(detail_info: dict[str, Any]) -> str | None:
    """Combine job description and requirements (≤4000 chars)."""
    parts: list[str] = []
    for field in ("privJdDtl", "privMustReq", "etc"):
        text = str(detail_info.get(field) or "").strip()
        if text:
            parts.append(text)
    if not parts:
        return None
    return "\n\n".join(parts)[:4000]


def _build_posting(
    item: dict[str, Any],
    company_name: str,
    detail_info: dict[str, Any] | None = None,
) -> RawJobPosting:
    deadline = _parse_date_yyyymmdd(item.get("appDispEdDt"))

    posted_at_date = None
    if detail_info:
        posted_at_date = _parse_date_yyyymmdd(detail_info.get("applyStartDt"))
    posted_at: datetime | None = (
        datetime(posted_at_date.year, posted_at_date.month, posted_at_date.day)
        if posted_at_date
        else None
    )

    desc: str | None = None
    if detail_info:
        desc = _description(detail_info)

    return RawJobPosting(
        source=_SOURCE,
        source_url=_source_url(item),
        source_external_id=_source_external_id(item),
        company_name=company_name,
        title=(item.get("recuNoticeNm") or "").strip(),
        roles=_roles(item),
        location=(item.get("workPlaceCodeNm") or "").strip() or None,
        experience_level=_experience_level(item.get("channelCodeNm")),
        employment_type="정규직",
        deadline=deadline,
        posted_at=posted_at,
        description=desc,
    )


# ── Parser ─────────────────────────────────────────────────────────────────────


class HyundaiMotorCareersParser(CustomParser):
    """현대자동차 채용 테마관 파서.

    /api/rec/AP-HM-FO-02730 (목록) +
    /api/rec/AP-HM-FO-02800 (상세) 를 인증 없이 사용한다.
    logoNm == "현대" 필터로 현대자동차 본사 공고만 수집한다.
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
            profile.canonical_name if profile else None
        ) or "현대자동차"
        warnings: list[str] = []

        # ── Stage 1: 두 탭 목록 수집 + 중복 제거 ───────────────────────────────
        seen_keys: dict[str, dict[str, Any]] = {}  # ext_id → item
        discovered_total = 0

        async with httpx.AsyncClient(
            headers=_HEADERS, timeout=_TIMEOUT, follow_redirects=True
        ) as client:
            for tab in _TABS:
                try:
                    r = await client.get(
                        _LIST_URL,
                        params={
                            "hgrCd": _HGR_CD,
                            "lang": _LANG,
                            "secCode": "",
                            "jdRecuCate": tab,
                            "secLoad": "Y",
                        },
                    )
                    r.raise_for_status()
                except Exception as exc:
                    warnings.append(
                        f"HYUNDAI_MOTOR_CAREERS list failed tab={tab}: {exc}"
                    )
                    continue

                try:
                    body = r.json()
                    items: list[dict[str, Any]] = (
                        body.get("data", {}).get("applyList") or []
                    )
                except Exception as exc:
                    warnings.append(
                        f"HYUNDAI_MOTOR_CAREERS list JSON parse failed "
                        f"tab={tab}: {exc}"
                    )
                    continue

                discovered_total += len(items)

                for item in items:
                    # Company verification: Hyundai Motor only
                    if item.get("logoNm") != _HYUNDAI_LOGO_NM:
                        warnings.append(
                            f"HYUNDAI_MOTOR_CAREERS: skipped "
                            f"recuCls={item.get('recuCls')} — "
                            f"logoNm={item.get('logoNm')!r} "
                            f"(expected '현대')"
                        )
                        continue

                    ext_id = _source_external_id(item)
                    if not ext_id.strip("_"):
                        warnings.append(
                            "HYUNDAI_MOTOR_CAREERS: item missing recuCls, skipped"
                        )
                        continue

                    if ext_id not in seen_keys:
                        seen_keys[ext_id] = item
                    else:
                        warnings.append(
                            f"HYUNDAI_MOTOR_CAREERS: duplicate ext_id={ext_id!r} "
                            f"tab={tab} skipped"
                        )

        if not seen_keys:
            log.info(
                "HYUNDAI_MOTOR_CAREERS: 0 postings discovered "
                "(theme hall may be inactive)"
            )
            return AdapterResult(
                postings=[],
                warnings=warnings,
                source_stats=AdapterSourceStats(
                    discovered=discovered_total,
                    fetched=0,
                    parsed=0,
                    selected=0,
                ),
            )

        log.info(
            "HYUNDAI_MOTOR_CAREERS: discovered=%d unique=%d",
            discovered_total,
            len(seen_keys),
        )

        # ── Stage 2: 상세 조회 (description 수집) ──────────────────────────────
        fetch_items = list(seen_keys.values())[: config.max_fetch]
        postings: list[RawJobPosting] = []
        fetched = 0

        async with httpx.AsyncClient(
            headers=_HEADERS, timeout=_TIMEOUT, follow_redirects=True
        ) as client:
            for item in fetch_items:
                ext_id = _source_external_id(item)
                title = (item.get("recuNoticeNm") or "").strip()
                if not title:
                    warnings.append(
                        f"HYUNDAI_MOTOR_CAREERS: empty title "
                        f"ext_id={ext_id!r}, skipped"
                    )
                    continue

                detail_info: dict[str, Any] | None = None
                try:
                    rd = await client.get(
                        _DETAIL_URL,
                        params={
                            "hgrCd": _HGR_CD,
                            "lang": _LANG,
                            "recuYy": item.get("recuYy", ""),
                            "recuType": item.get("recuType", ""),
                            "recuCls": str(item.get("recuCls", "")),
                        },
                    )
                    rd.raise_for_status()
                    fetched += 1
                    detail_body = rd.json()
                    detail_info = detail_body.get("data", {}).get("applyInfo") or {}
                except Exception as exc:
                    warnings.append(
                        f"HYUNDAI_MOTOR_CAREERS: detail failed "
                        f"ext_id={ext_id!r}: {exc}"
                    )
                    fetched += 1  # attempted

                try:
                    posting = _build_posting(item, company_name, detail_info)
                    postings.append(posting)
                except Exception as exc:
                    warnings.append(
                        f"HYUNDAI_MOTOR_CAREERS: parse failed "
                        f"ext_id={ext_id!r}: {exc}"
                    )

        return AdapterResult(
            postings=postings,
            warnings=warnings,
            source_stats=AdapterSourceStats(
                discovered=discovered_total,
                fetched=fetched,
                parsed=len(postings),
                selected=len(postings),
            ),
        )


_CUSTOM_REGISTRY_BY_KEY["HYUNDAI_MOTOR_CAREERS"] = HyundaiMotorCareersParser()
