"""SK_CAREERS parser — SK Careers group portal (www.skcareers.com).

Registered as "SK_CAREERS".

Investigation summary (2026-08-25):
  skcareers.com runs a jQuery-based ASP.NET MVC app.
  Listing endpoint accepts form-encoded POST with no authentication.
  Corp codes are publicly enumerable via POST /Recruit/GetAutocomplete
  with type="CorpCode" — returns [{Key, Value}] array without cookies.

  Confirmed corp codes (2026-08-25):
    10004 = SK hynix   (SK하이닉스)
    10005 = SK telecom (SK텔레콤)

  List API returns ALL active postings in one call (no pagination).
  All metadata (title, company, job role, location, dates, type) is
  present in the list response; no separate detail call is required.

Endpoints:
  POST https://www.skcareers.com/Recruit/GetRecruitList
    form data: sort, searchText, corpCode, jobRole, recruitType,
               workingType, workingRegion
    → JSON: {success, totalCount, list: [{noticeID, title, corpName,
             jobRole, recruitType, workingType, workingArea,
             remainDay, scrapIdx, start, end, jobNoticeNo}]}

config_json contract:
  {
    "parser_key": "SK_CAREERS",
    "corp_code": "10005",                // corpCode POST parameter (required)
    "expected_corp_name": "SK telecom",  // corpName re-verification (optional)
    "max_fetch": 50                      // item cap
  }

Source URL per posting: https://www.skcareers.com/Recruit/Detail/{noticeID}
External ID: noticeID string (e.g. "R261849")
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
)

log = logging.getLogger(__name__)

_SOURCE = "sk_careers"
_LIST_URL = "https://www.skcareers.com/Recruit/GetRecruitList"
_DETAIL_URL_TMPL = "https://www.skcareers.com/Recruit/Detail/{}"
_TIMEOUT = 15.0
_HEADERS = {
    "User-Agent": "Briefy-Agent/1.0 (+https://briefy.io)",
    "X-Requested-With": "XMLHttpRequest",
    "Referer": "https://www.skcareers.com/Recruit",
}


# ── Config ─────────────────────────────────────────────────────────────────────


@dataclass
class _SkConfig:
    corp_code: str
    expected_corp_name: str | None
    max_fetch: int


def _parse_config(config_json: str | None) -> _SkConfig:
    data: dict[str, Any] = {}
    if config_json:
        try:
            data = json.loads(config_json)
        except json.JSONDecodeError:
            pass
    corp_code = str(data.get("corp_code") or "").strip()
    raw_name = str(data.get("expected_corp_name") or "").strip()
    expected_corp_name = raw_name or None
    max_fetch = int(data.get("max_fetch") or 50)
    return _SkConfig(
        corp_code=corp_code,
        expected_corp_name=expected_corp_name,
        max_fetch=max_fetch,
    )


# ── Pure helpers ───────────────────────────────────────────────────────────────


def _parse_date(raw: str | None) -> date | None:
    """Parse "August 30, 2026(Sun)" → date."""
    if not raw:
        return None
    clean = re.sub(r"\([A-Za-z]+\)", "", raw).strip()
    try:
        return datetime.strptime(clean, "%B %d, %Y").date()
    except ValueError:
        return None


def _experience_level(recruit_type: str) -> str | None:
    t = recruit_type.strip()
    if t == "New":
        return "신입"
    if t == "Experienced":
        return "경력"
    if t == "Irrelevant":
        return None
    return t or None


def _employment_type(working_type: str) -> str | None:
    t = working_type.strip()
    if t == "Permanent":
        return "정규직"
    if t == "Contract":
        return "계약직"
    return t or None


def _parse_roles(job_role: str) -> list[str]:
    """Split jobRole string into individual role tags.

    skcareers uses " • " to separate top-level role groups.
    Commas separate roles within a group.
    """
    if not job_role:
        return []
    roles: list[str] = []
    for segment in re.split(r"\s*•\s*", job_role):
        for part in segment.split(","):
            part = part.strip()
            if part:
                roles.append(part)
    return roles


def _build_posting(
    item: dict[str, Any],
    company_name: str,
) -> RawJobPosting:
    notice_id: str = item.get("noticeID") or ""
    title: str = (item.get("title") or "").strip()
    return RawJobPosting(
        source=_SOURCE,
        source_url=_DETAIL_URL_TMPL.format(notice_id),
        source_external_id=notice_id,
        company_name=company_name,
        title=title,
        roles=_parse_roles(item.get("jobRole") or ""),
        location=item.get("workingArea") or None,
        experience_level=_experience_level(item.get("recruitType") or ""),
        employment_type=_employment_type(item.get("workingType") or ""),
        posted_at=(_parse_posted_at(item.get("start"))),
        deadline=_parse_date(item.get("end")),
        description=None,
    )


def _parse_posted_at(raw: str | None) -> datetime | None:
    d = _parse_date(raw)
    if d is None:
        return None
    return datetime(d.year, d.month, d.day)


# ── Parser ─────────────────────────────────────────────────────────────────────


class SkCareersParser(CustomParser):
    """SK Careers group portal parser.

    corp_code / expected_corp_name in config_json distinguish companies.
    A single list call returns all active postings — no pagination needed.
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
            or config.expected_corp_name
            or "SK"
        )
        warnings: list[str] = []

        if not config.corp_code:
            warnings.append(
                f"SK_CAREERS: corp_code not configured "
                f"(company_id={source.company_id})"
            )
            return AdapterResult(
                postings=[],
                warnings=warnings,
                source_stats=AdapterSourceStats(),
            )

        # ── Stage 1: list all active postings ─────────────────────────────────
        try:
            async with httpx.AsyncClient(
                headers=_HEADERS,
                timeout=_TIMEOUT,
                follow_redirects=True,
            ) as client:
                r = await client.post(
                    _LIST_URL,
                    data={
                        "sort": "1",
                        "searchText": "",
                        "corpCode": config.corp_code,
                        "jobRole": "",
                        "recruitType": "",
                        "workingType": "",
                        "workingRegion": "",
                    },
                )
                r.raise_for_status()
        except Exception as exc:
            warnings.append(f"SK_CAREERS list failed [{config.corp_code}]: {exc}")
            return AdapterResult(
                postings=[],
                warnings=warnings,
                source_stats=AdapterSourceStats(),
            )

        try:
            body = r.json()
        except Exception as exc:
            warnings.append(
                f"SK_CAREERS list JSON parse failed [{config.corp_code}]: {exc}"
            )
            return AdapterResult(
                postings=[],
                warnings=warnings,
                source_stats=AdapterSourceStats(),
            )

        if not body.get("success"):
            warnings.append(
                f"SK_CAREERS list returned success=false [{config.corp_code}]"
            )
            return AdapterResult(
                postings=[],
                warnings=warnings,
                source_stats=AdapterSourceStats(),
            )

        items: list[dict[str, Any]] = body.get("list") or []
        discovered = len(items)
        log.info(
            "SK_CAREERS [%s]: discovered %d postings",
            config.corp_code,
            discovered,
        )

        # ── Stage 2: build postings ────────────────────────────────────────────
        seen_ids: set[str] = set()
        postings: list[RawJobPosting] = []

        for item in items[: config.max_fetch]:
            notice_id: str = item.get("noticeID") or ""
            if not notice_id:
                warnings.append(
                    f"SK_CAREERS: item missing noticeID, skipped "
                    f"[{config.corp_code}]"
                )
                continue

            if notice_id in seen_ids:
                warnings.append(
                    f"SK_CAREERS: duplicate noticeID={notice_id!r} skipped "
                    f"[{config.corp_code}]"
                )
                continue
            seen_ids.add(notice_id)

            # corpName cross-check
            if config.expected_corp_name:
                corp_name_in_item = item.get("corpName") or ""
                if corp_name_in_item != config.expected_corp_name:
                    warnings.append(
                        f"SK_CAREERS: skipped {notice_id!r} — "
                        f"corpName={corp_name_in_item!r} "
                        f"expected={config.expected_corp_name!r}"
                    )
                    continue

            if not item.get("title", "").strip():
                warnings.append(
                    f"SK_CAREERS: empty title for {notice_id!r}, skipped "
                    f"[{config.corp_code}]"
                )
                continue

            try:
                posting = _build_posting(item, company_name)
                postings.append(posting)
            except Exception as exc:
                warnings.append(
                    f"SK_CAREERS: parse failed for {notice_id!r} "
                    f"[{config.corp_code}]: {exc}"
                )

        return AdapterResult(
            postings=postings,
            warnings=warnings,
            source_stats=AdapterSourceStats(
                discovered=discovered,
                fetched=discovered,
                parsed=len(postings),
                selected=len(postings),
            ),
        )


_CUSTOM_REGISTRY_BY_KEY["SK_CAREERS"] = SkCareersParser()
