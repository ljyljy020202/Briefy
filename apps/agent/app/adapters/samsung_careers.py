"""SamsungCareersParser — custom parser for www.samsungcareers.com.

Registered as "SAMSUNG_CAREERS".

Investigation summary (2026-08-17):
  samsungcareers.com is a traditional SSR site with jQuery (not a React SPA).
  Job listing is loaded via a form POST that returns an HTML fragment; there is
  no WAF, no Cloudflare, and no authentication required for the listing or
  detail endpoints.

  Confirmed company code mapping (from /subsid/detail/{code} hrefs):
    C10CAA = 삼성전자 DX부문
    C10CAH = 삼성전자 DS부문
    C90    = 삼성디스플레이
    C31    = 삼성SDI
    C40    = 삼성전기
    C60    = 삼성SDS

  At implementation time (2026-08-17):
    C10CAA, C10CAH, C60 → 0 active postings.
    API path is stable and confirmed working via C31 (SDI) filter.
    Sources seeded as PENDING; activate when postings appear.

Endpoints:
  POST https://www.samsungcareers.com/hr/list.data
    form params: currentPageNo, strCompany[], strType[], ...
    → HTML fragment containing <li> job cards

  GET  https://www.samsungcareers.com/recruit/detail.data?seqno={seq}
    → JSON: {"success": true, "data": {"result": {...}}}

Collection flow:
  1. For each com_code in config com_codes: POST list.data with
     strCompany[]={com_code}, collect all pages.
  2. Deduplicate by seq across com_codes.
  3. Cap at max_discover; fetch detail for up to max_fetch.
  4. Verify compCd in detail matches one of the configured com_codes.
  5. Build RawJobPosting.

config_json:
  {
    "parser_key": "SAMSUNG_CAREERS",
    "com_codes": ["C10CAA", "C10CAH"],   // company codes to collect
    "max_discover": 50,                  // listing cap
    "max_fetch": 20                      // detail fetch cap
  }

Source URL per posting: https://www.samsungcareers.com/hr/?no={seq}
External ID: str(seq)

Card parse fields:
  button.btnShare[data-value] → seq (Korean comma-formatted integer)
  .company                    → company name text
  .title (h3)                 → posting title
  .info span (first non-period) → recruit type text (경력/신입/인턴/신입·경력)
  .period                     → date range "YYYY.MM.DD ~ YYYY.MM.DD"
  .flag.grey                  → 모집직무 role tag labels

Detail JSON fields used:
  result.compCd       → company code (verified against com_codes)
  result.cmpNameKr    → canonical Korean company name
  result.title        → posting title (redundant; card title used)
  result.recruitType  → "A"=신입 / "B"=경력 / "C"=인턴
  result.startdate    → post open time "YYYYMMDDHHmm"
  result.enddate      → deadline "YYYYMMDDHHmm" (None if absent)
  result.introKr      → company intro text
  result.qlfctKr      → qualification requirements
  result.etcKr        → additional info
"""

import asyncio
import json
import logging
import re
from dataclasses import dataclass, field
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

_LIST_URL = "https://www.samsungcareers.com/hr/list.data"
_DETAIL_URL = "https://www.samsungcareers.com/recruit/detail.data"
_SOURCE_URL_TMPL = "https://www.samsungcareers.com/hr/?no={seq}"
_SOURCE = "samsung_careers"

_DEFAULT_MAX_DISCOVER = 50
_DEFAULT_MAX_FETCH = 20
_SEMAPHORE_LIMIT = 4

_HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
        "AppleWebKit/537.36 (KHTML, like Gecko) "
        "Chrome/124.0.0.0 Safari/537.36"
    ),
    "Accept": "text/html,application/xhtml+xml,*/*",
    "Accept-Language": "ko-KR,ko;q=0.9",
    "Referer": "https://www.samsungcareers.com/hr/",
}

_DETAIL_HEADERS = {
    **_HEADERS,
    "Accept": "application/json, text/plain, */*",
}


# ── Config ─────────────────────────────────────────────────────────────────────


@dataclass
class _SamsungConfig:
    com_codes: list[str]
    max_discover: int
    max_fetch: int


def _parse_config(config_json: str | None) -> _SamsungConfig:
    data: dict = {}
    if config_json:
        try:
            data = json.loads(config_json)
        except Exception:
            pass
    com_codes = data.get("com_codes") or []
    if isinstance(com_codes, str):
        com_codes = [com_codes]
    com_codes = [str(c).strip() for c in com_codes]
    com_codes = [c for c in com_codes if c]
    max_discover = int(data.get("max_discover") or _DEFAULT_MAX_DISCOVER)
    max_fetch = int(data.get("max_fetch") or _DEFAULT_MAX_FETCH)
    return _SamsungConfig(
        com_codes=com_codes,
        max_discover=max_discover,
        max_fetch=max_fetch,
    )


# ── Card data ──────────────────────────────────────────────────────────────────


@dataclass
class _CardInfo:
    seq: int
    company_name: str
    title: str
    type_text: str       # "경력" / "신입" / "인턴" / "신입·경력"
    period_text: str     # "YYYY.MM.DD ~ YYYY.MM.DD" or ""
    role_flags: list[str] = field(default_factory=list)


# ── List HTML parsing ──────────────────────────────────────────────────────────


def _parse_list_html(html: str) -> tuple[list[_CardInfo], int]:
    """Parse the HTML fragment from list.data.

    Returns (cards, total_count).
    total_count comes from <input class='divCnt' data-value='{N}' data-max='{M}'>.
    """
    soup = BeautifulSoup(html, "html.parser")

    cnt_el = soup.find("input", class_="divCnt")
    total_count = 0
    if cnt_el and cnt_el.get("data-value"):
        try:
            total_count = int(str(cnt_el["data-value"]).replace(",", ""))
        except ValueError:
            pass

    cards: list[_CardInfo] = []
    for li in soup.find_all("li"):
        share_btn = li.find("button", class_="btnShare")
        if not share_btn or not share_btn.get("data-value"):
            continue
        raw_val = str(share_btn["data-value"]).replace(",", "").strip()
        if not raw_val.isdigit():
            continue
        seq = int(raw_val)

        company_el = li.find(class_="company")
        company_name = company_el.get_text(strip=True) if company_el else ""

        title_el = li.find(class_="title")
        title = title_el.get_text(strip=True) if title_el else ""

        info_el = li.find(class_="info")
        type_text = ""
        period_text = ""
        if info_el:
            period_el = info_el.find(class_="period")
            period_text = period_el.get_text(strip=True) if period_el else ""
            for span in info_el.find_all("span"):
                cls = span.get("class") or []
                if "period" not in cls:
                    txt = span.get_text(strip=True)
                    if txt:
                        type_text = txt
                        break

        role_flags: list[str] = []
        for flag_el in li.find_all(class_=lambda x: x and "flag" in x if x else False):
            cls = flag_el.get("class") or []
            if "grey" in cls:
                label = flag_el.get_text(strip=True)
                if label:
                    role_flags.append(label)

        if seq and title:
            cards.append(
                _CardInfo(
                    seq=seq,
                    company_name=company_name,
                    title=title,
                    type_text=type_text,
                    period_text=period_text,
                    role_flags=role_flags,
                )
            )

    return cards, total_count


# ── Field extractors ───────────────────────────────────────────────────────────


def _parse_deadline(period_text: str) -> date | None:
    """Extract deadline from 'YYYY.MM.DD ~ YYYY.MM.DD'."""
    text = period_text.strip()
    if "~" not in text:
        return None
    m = re.search(r"(\d{4})\.(\d{2})\.(\d{2})\s*$", text)
    if not m:
        return None
    try:
        return date(int(m.group(1)), int(m.group(2)), int(m.group(3)))
    except ValueError:
        return None


def _parse_date_field(raw: str | None) -> date | None:
    """Parse 'YYYYMMDDHHmm' → date."""
    if not raw or len(raw) < 8:
        return None
    try:
        return datetime.strptime(raw[:8], "%Y%m%d").date()
    except ValueError:
        return None


def _experience_level(type_text: str) -> str | None:
    """Map card type span text to a normalised experience level string."""
    t = type_text.strip()
    if not t:
        return None
    # Normalise half-width middle dot variants
    t = t.replace("·", "/").replace("ㆍ", "/")
    if t in ("신입/경력", "신입·경력"):
        return "신입/경력"
    if t == "신입":
        return "신입"
    if t == "경력":
        return "경력"
    if t == "인턴":
        return "인턴"
    return t or None


def _employment_type(title: str) -> str | None:
    """Infer employment type from posting title keywords."""
    t = title.strip()
    if "인턴" in t:
        return "인턴"
    if "계약직" in t:
        return "계약직"
    return "정규직"


def _description(detail: dict[str, Any]) -> str | None:
    """Combine qualification + extra info as plain text (≤4000 chars)."""
    parts: list[str] = []
    for field_name in ("qlfctKr", "etcKr", "introKr"):
        text = str(detail.get(field_name) or "").strip()
        if text:
            parts.append(text)
    if not parts:
        return None
    return "\n\n".join(parts)[:4000]


# ── RawJobPosting builder ──────────────────────────────────────────────────────


def _build_posting(
    card: _CardInfo,
    detail: dict[str, Any],
    company_name: str,
) -> RawJobPosting:
    seq = card.seq
    title = detail.get("title") or card.title
    deadline = _parse_deadline(card.period_text)
    if deadline is None:
        enddate = detail.get("enddate") or ""
        deadline = _parse_date_field(str(enddate))

    posted_at_date = _parse_date_field(str(detail.get("startdate") or ""))
    posted_at: datetime | None = (
        datetime(posted_at_date.year, posted_at_date.month, posted_at_date.day)
        if posted_at_date
        else None
    )

    return RawJobPosting(
        source=_SOURCE,
        source_url=_SOURCE_URL_TMPL.format(seq=seq),
        company_name=company_name,
        title=title,
        employment_type=_employment_type(card.title),
        experience_level=_experience_level(card.type_text),
        location=None,
        deadline=deadline,
        roles=list(card.role_flags),
        description=_description(detail),
        posted_at=posted_at,
        source_external_id=str(seq),
    )


# ── Parser ─────────────────────────────────────────────────────────────────────


class SamsungCareersParser(CustomParser):
    PARSER_KEY = "SAMSUNG_CAREERS"

    async def fetch(
        self,
        source: OfficialCompanySource,
        profile: CompanyProfile | None,
        options: CollectionOptions,
        collect_date: date | None = None,
    ) -> AdapterResult:
        config = _parse_config(source.config_json)
        sid = f"company_{source.company_id}_custom_samsung_careers"
        company_name = (profile.canonical_name if profile else None) or "삼성"
        warnings: list[str] = []
        timeout = settings.job_collection_timeout_seconds

        if not config.com_codes:
            warnings.append(f"samsung_careers: no com_codes configured [{sid}]")
            return AdapterResult(warnings=warnings, source_stats=AdapterSourceStats())

        all_cards: dict[int, _CardInfo] = {}  # seq → CardInfo (dedup)

        async with AsyncClient(
            timeout=timeout,
            headers=_HEADERS,
            follow_redirects=True,
        ) as client:
            # ── Phase 1: collect cards for each company code ───────────────────
            for com_code in config.com_codes:
                page = 1
                max_page = 1
                while page <= max_page:
                    try:
                        r = await client.post(
                            _LIST_URL,
                            data={
                                "currentPageNo": str(page),
                                "strVal": "",
                                "strTxt": "",
                                "strKey": "",
                                "strType[]": "",
                                "strCompany[]": com_code,
                                "strOrderBy": "",
                                "strEntity": "",
                                "intNo": "0",
                            },
                            headers={
                                "Content-Type": "application/x-www-form-urlencoded"
                            },
                        )
                        r.raise_for_status()
                    except TimeoutException:
                        warnings.append(
                            f"samsung_careers: listing timeout "
                            f"com_code={com_code} page={page} [{sid}]"
                        )
                        break
                    except HTTPStatusError as exc:
                        warnings.append(
                            f"samsung_careers: listing HTTP "
                            f"{exc.response.status_code} "
                            f"com_code={com_code} page={page} [{sid}]"
                        )
                        break
                    except Exception as exc:
                        warnings.append(
                            f"samsung_careers: listing error "
                            f"com_code={com_code} page={page} [{sid}]: {exc}"
                        )
                        break

                    cards, total_count = _parse_list_html(r.text)

                    if page == 1:
                        # divCnt data-max is 0-indexed page count; recompute
                        cnt_el = BeautifulSoup(r.text, "html.parser").find(
                            "input", class_="divCnt"
                        )
                        if cnt_el and cnt_el.get("data-max"):
                            try:
                                max_page = int(str(cnt_el["data-max"]))
                            except ValueError:
                                max_page = 1
                        log.info(
                            "samsung_careers: com_code=%s total=%d max_page=%d [%s]",
                            com_code,
                            total_count,
                            max_page,
                            sid,
                        )

                    for card in cards:
                        if card.seq not in all_cards:
                            all_cards[card.seq] = card

                    page += 1

        if not all_cards:
            log.info("samsung_careers: 0 postings discovered [%s]", sid)
            return AdapterResult(
                warnings=warnings,
                source_stats=AdapterSourceStats(
                    discovered=0, fetched=0, parsed=0, selected=0
                ),
            )

        discovered_cards = list(all_cards.values())[: config.max_discover]
        fetch_cards = discovered_cards[: config.max_fetch]
        discovered_count = len(all_cards)

        log.info(
            "samsung_careers: discovered=%d capped_discover=%d fetch=%d [%s]",
            discovered_count,
            len(discovered_cards),
            len(fetch_cards),
            sid,
        )

        # ── Phase 2: fetch detail for each seq ────────────────────────────────
        sem = asyncio.Semaphore(_SEMAPHORE_LIMIT)
        postings: list[RawJobPosting] = []
        com_code_set = set(config.com_codes)

        async def _fetch_detail(card: _CardInfo) -> None:
            async with sem:
                try:
                    async with AsyncClient(
                        timeout=timeout,
                        headers=_DETAIL_HEADERS,
                        follow_redirects=True,
                    ) as dc:
                        rd = await dc.get(
                            _DETAIL_URL, params={"seqno": str(card.seq)}
                        )
                        rd.raise_for_status()
                except TimeoutException:
                    warnings.append(
                        f"samsung_careers: detail timeout seq={card.seq} [{sid}]"
                    )
                    return
                except HTTPStatusError as exc:
                    warnings.append(
                        f"samsung_careers: detail HTTP "
                        f"{exc.response.status_code} seq={card.seq} [{sid}]"
                    )
                    return
                except Exception as exc:
                    warnings.append(
                        f"samsung_careers: detail error seq={card.seq} [{sid}]: {exc}"
                    )
                    return

                try:
                    body = rd.json()
                    result: dict = body["data"]["result"]
                except Exception as exc:
                    warnings.append(
                        f"samsung_careers: detail parse error seq={card.seq} "
                        f"[{sid}]: {exc}"
                    )
                    return

                comp_cd = result.get("compCd") or ""
                if comp_cd not in com_code_set:
                    warnings.append(
                        f"samsung_careers: compCd mismatch seq={card.seq} "
                        f"compCd={comp_cd!r} expected={sorted(com_code_set)} [{sid}]"
                    )
                    return

                cmp_name = result.get("cmpNameKr") or company_name
                posting = _build_posting(card, result, cmp_name)
                if not posting.title:
                    warnings.append(
                        f"samsung_careers: empty title seq={card.seq} [{sid}]"
                    )
                    return
                postings.append(posting)

        await asyncio.gather(*[_fetch_detail(c) for c in fetch_cards])

        stats = AdapterSourceStats(
            discovered=discovered_count,
            fetched=len(fetch_cards),
            parsed=len(postings),
            selected=len(postings),
        )
        log.info(
            "samsung_careers: parsed=%d selected=%d [%s]",
            stats.parsed,
            stats.selected,
            sid,
        )
        return AdapterResult(postings=postings, warnings=warnings, source_stats=stats)


# ── Self-registration ──────────────────────────────────────────────────────────

_CUSTOM_REGISTRY_BY_KEY["SAMSUNG_CAREERS"] = SamsungCareersParser()


# ── Preflight ──────────────────────────────────────────────────────────────────


async def samsung_preflight(source: OfficialCompanySource) -> dict:
    """Bounded preflight probe for SAMSUNG_CAREERS."""
    config = _parse_config(source.config_json)
    timeout = settings.job_collection_timeout_seconds
    warnings: list[str] = []

    if not config.com_codes:
        return {
            "reachable": False,
            "failure_code": "MISSING_COM_CODES",
            "failure_message": "com_codes is empty in config_json",
            "warnings": warnings,
        }

    probe_code = config.com_codes[0]
    try:
        async with AsyncClient(
            timeout=timeout,
            headers=_HEADERS,
            follow_redirects=True,
        ) as client:
            r = await client.post(
                _LIST_URL,
                data={
                    "currentPageNo": "1",
                    "strVal": "",
                    "strTxt": "",
                    "strKey": "",
                    "strType[]": "",
                    "strCompany[]": probe_code,
                    "strOrderBy": "",
                    "strEntity": "",
                    "intNo": "0",
                },
                headers={"Content-Type": "application/x-www-form-urlencoded"},
            )
            r.raise_for_status()
    except Exception as exc:
        return {
            "reachable": False,
            "failure_code": "URL_UNREACHABLE",
            "failure_message": str(exc),
            "warnings": warnings,
        }

    try:
        cards, total_count = _parse_list_html(r.text)
    except Exception as exc:
        return {
            "reachable": True,
            "failure_code": "PARSE_FAILED",
            "failure_message": str(exc),
            "warnings": warnings,
        }

    sample_parsed: dict | None = None
    if cards:
        card = cards[0]
        try:
            async with AsyncClient(
                timeout=timeout,
                headers=_DETAIL_HEADERS,
                follow_redirects=True,
            ) as dc:
                rd = await dc.get(_DETAIL_URL, params={"seqno": str(card.seq)})
                rd.raise_for_status()
                body = rd.json()
                result: dict = body["data"]["result"]
            cmp_name = result.get("cmpNameKr") or "삼성"
            posting = _build_posting(card, result, cmp_name)
            sample_parsed = {
                "title": posting.title,
                "roles": posting.roles,
                "experience_level": posting.experience_level,
                "employment_type": posting.employment_type,
                "location": posting.location,
                "source_external_id": posting.source_external_id,
            }
        except Exception as exc:
            warnings.append(f"samsung_careers: preflight detail error: {exc}")

    return {
        "reachable": True,
        "discovered_count": total_count,
        "sample_parsed": sample_parsed,
        "warnings": warnings,
    }
