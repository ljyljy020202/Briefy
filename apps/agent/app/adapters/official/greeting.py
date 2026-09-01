"""GreetingParser — CUSTOM parser for Greeting-powered company career pages.

Registered in _CUSTOM_REGISTRY_BY_KEY under parser_key="GREETING".

config_json contract (all fields optional except parser_key):
  {
    "parser_key": "GREETING",
    "max_discover": 50,
    "max_fetch": 20,
    "include_paths": [],
    "exclude_paths": []
  }

Supported URL structures:

  Hosted on careers.greeting.works (central platform):
    source_url : https://careers.greeting.works/companies/{slug}
    job detail : /companies/{slug}/jobs/{id}

  Self-hosted on company's own domain (e.g. musinsacareers.com):
    source_url : https://{company-domain}/ko/apply   (or /ko/home when
                 job cards are embedded there)
    job detail : /{locale}/o/{numeric-id}   (locale = 2-5 lower-case letters)
                 /o/{numeric-id}             (no locale prefix)

Only same-origin links are accepted for the self-hosted pattern to prevent
external links from being misidentified as job postings.

Metadata extraction strategy (Greeting-powered pages):
  All three sites (무신사, 현대오토에버, CJ올리브영) embed a React Query cache in
  a <script id="__NEXT_DATA__"> tag.  The query keyed "openings" contains a list
  of all active job openings, each carrying structured metadata:

    openingJobPosition.openingJobPositions[*]
      workspaceJob.job | workspaceOccupation.occupation  → role
      workspacePlace.location                             → location
      jobPositionCareer.careerType + careerFrom/careerTo  → experience_level
      jobPositionEmployment.employmentType                → employment_type
    openDate / dueDate                                    → posted_at / deadline
    openingId                                             → source_external_id
    title                                                 → title

  When __NEXT_DATA__ is present and the "openings" query is populated we skip
  building RawJobPosting from anchor links + detail pages and instead build
  them directly from the payload.  The detail page is still fetched for the
  `description` field (trimmed to 2000 chars), but individual detail failures
  do not discard the posting — the description is just left None.

  Fallback for hosted careers.greeting.works pages (no __NEXT_DATA__ openings):
    Same HTML anchor-link discovery + detail-page fetching as before.
"""

import json
import logging
import re
from dataclasses import dataclass, field
from datetime import date, datetime
from urllib.parse import urljoin, urlparse

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

_KR_DATE_RE = re.compile(r"(\d{4})년\s*(\d{1,2})월\s*(\d{1,2})일")
_EMPLOYMENT_KEYWORDS = ["정규직", "계약직", "인턴", "파견직", "프리랜서"]
_TITLE_STRIP_SUFFIXES = [
    " | Greeting",
    " | 채용",
    " - 채용",
    " | Jobs",
    " | Careers",
]

# careers.greeting.works central-hosted job detail path
_JOB_PATH_RE = re.compile(r"/companies/[^/]+/jobs/([^/]+)$")

# Self-hosted Greeting job detail path: /{locale}/o/{numeric-id} or /o/{numeric-id}.
# Locale is 2–5 lower-case ASCII letters (e.g. ko, en, zh-tw would not match the
# simple form — only plain locale codes supported for now).
# Same-origin check is applied in _discover_job_urls; this regex is path-only.
_SELFHOSTED_JOB_PATH_RE = re.compile(r"(?:/[a-z]{2,5})?/o/(\d+)$")

_LOCATION_KEYWORDS = [
    "서울",
    "경기",
    "판교",
    "인천",
    "부산",
    "대전",
    "원격",
    "재택",
]
_DEFAULT_MAX_DISCOVER = 50
_DEFAULT_MAX_FETCH = 20

# ── Greeting-API field mappings ───────────────────────────────────────────────

# Greeting employmentType enum → Korean label
_GREETING_EMPLOYMENT_MAP: dict[str, str] = {
    "FULL_TIME_WORKER": "정규직",
    "CONTRACT_WORKER": "계약직",
    "INTERN": "인턴",
    "PART_TIME_WORKER": "파트타임",
    "FREELANCER": "프리랜서",
}

# Greeting careerType enum → experience_level conversion
# See _career_to_experience_level() for the full logic.
_GREETING_CAREER_TYPE_MAP: dict[str, str] = {
    "NEW_COMER": "신입",
    "NOT_MATTER": "경력 무관",
    "NEW_COMER_AND_EXPERIENCED": "신입/경력",
}


@dataclass
class _GreetingConfig:
    max_discover: int = _DEFAULT_MAX_DISCOVER
    max_fetch: int = _DEFAULT_MAX_FETCH
    include_paths: list[str] = field(default_factory=list)
    exclude_paths: list[str] = field(default_factory=list)


def _parse_greeting_config(config_json: str | None) -> _GreetingConfig:
    if not config_json:
        return _GreetingConfig()
    try:
        data = json.loads(config_json)
        return _GreetingConfig(
            max_discover=int(data.get("max_discover", _DEFAULT_MAX_DISCOVER)),
            max_fetch=int(data.get("max_fetch", _DEFAULT_MAX_FETCH)),
            include_paths=list(data.get("include_paths", [])),
            exclude_paths=list(data.get("exclude_paths", [])),
        )
    except Exception:
        return _GreetingConfig()


def _discover_job_urls(
    html: str,
    base_url: str,
    config: _GreetingConfig,
) -> list[str]:
    """Extract job detail URLs from a Greeting company career page.

    Supports two URL patterns:
    - /companies/{slug}/jobs/{id}  (careers.greeting.works hosted)
    - /{locale}/o/{numeric-id} or /o/{numeric-id}  (self-hosted, same-origin only)

    Relative URLs are resolved against base_url.  Duplicates (after stripping
    query and fragment) are removed, insertion order is preserved.
    """
    soup = BeautifulSoup(html, "lxml")
    base_netloc = urlparse(base_url).netloc
    seen: set[str] = set()
    urls: list[str] = []

    for tag in soup.find_all("a", href=True):
        href = str(tag["href"]).strip()
        if href.startswith("/"):
            href = urljoin(base_url, href)
        elif not href.startswith("http"):
            continue

        parsed = urlparse(href)
        path = parsed.path.rstrip("/")
        link_netloc = parsed.netloc

        if _JOB_PATH_RE.search(path):
            pass  # hosted pattern — no same-origin restriction needed
        elif link_netloc == base_netloc and _SELFHOSTED_JOB_PATH_RE.search(path):
            pass  # self-hosted pattern — same-origin enforced above
        else:
            continue

        if config.include_paths and not any(p in path for p in config.include_paths):
            continue

        if any(p in path for p in config.exclude_paths):
            continue

        normalized = f"{parsed.scheme}://{parsed.netloc}{path}"
        if normalized not in seen:
            seen.add(normalized)
            urls.append(normalized)
            if len(urls) >= config.max_discover:
                break

    return urls


def _extract_job_id(url: str) -> str | None:
    path = urlparse(url).path.rstrip("/")
    m = _JOB_PATH_RE.search(path)
    if m:
        return m.group(1)
    m = _SELFHOSTED_JOB_PATH_RE.search(path)
    if m:
        return m.group(1)
    return None


# ── __NEXT_DATA__ payload extraction ─────────────────────────────────────────

_NEXT_DATA_RE = re.compile(
    r'<script\s+id=["\']__NEXT_DATA__["\'][^>]*>(.*?)</script>',
    re.DOTALL,
)


def _extract_next_data_openings(html: str) -> list[dict] | None:
    """Return the 'openings' React Query list from __NEXT_DATA__, or None.

    Returns None when:
    - No __NEXT_DATA__ script tag found
    - JSON parse error
    - No 'openings' query key found in dehydratedState
    """
    m = _NEXT_DATA_RE.search(html)
    if not m:
        return None
    try:
        payload = json.loads(m.group(1))
    except Exception:
        return None

    try:
        page_props = payload.get("props", {}).get("pageProps", {})
        dehydrated = page_props.get("dehydratedState", {})
        for query in dehydrated.get("queries", []):
            key = query.get("queryKey", [])
            if key and key[0] == "openings":
                data = query.get("state", {}).get("data")
                if isinstance(data, list):
                    return data
    except Exception:
        pass
    return None


def _extract_next_data_detail_description(html: str) -> str | None:
    """Extract HTML description from detail page __NEXT_DATA__ jobPositionSetting.

    The detail page stores the opening's 'detail' HTML in:
      pageProps.dehydratedState.queries[?queryKey[1]=='getOpeningById']
        .state.data.data.openingsInfo.detail
    """
    m = _NEXT_DATA_RE.search(html)
    if not m:
        return None
    try:
        payload = json.loads(m.group(1))
        dehydrated = (
            payload.get("props", {}).get("pageProps", {}).get("dehydratedState", {})
        )
        for query in dehydrated.get("queries", []):
            key = query.get("queryKey", [])
            if len(key) >= 2 and key[1] == "getOpeningById":
                inner = query.get("state", {}).get("data", {}).get("data", {})
                detail_html = inner.get("openingsInfo", {}).get("detail")
                if detail_html:
                    # Strip HTML tags to plain text
                    soup = BeautifulSoup(detail_html, "html.parser")
                    text = soup.get_text(separator="\n", strip=True)
                    return text[:2000] if text else None
    except Exception:
        pass
    return None


def _career_to_experience_level(career: dict | None) -> str | None:
    """Convert a Greeting jobPositionCareer dict to an experience_level string.

    Mapping rules:
      NEW_COMER                  → "신입"
      NOT_MATTER                 → "경력 무관"
      NEW_COMER_AND_EXPERIENCED  → "신입/경력"
      EXPERIENCED + careerFrom   → "N년 이상"
      EXPERIENCED + no careerFrom→ "경력"
    """
    if not career:
        return None
    career_type = career.get("careerType", "")
    mapped = _GREETING_CAREER_TYPE_MAP.get(career_type)
    if mapped:
        return mapped
    if career_type == "EXPERIENCED":
        career_from = career.get("careerFrom")
        if career_from is not None:
            return f"{career_from}년 이상"
        return "경력"
    return None


def _extract_greeting_metadata(opening: dict) -> dict:
    """Extract structured metadata from a Greeting 'openings' list entry.

    Returns a dict with keys:
      roles            list[str]   — workspaceJob.job or workspaceOccupation.occupation
      experience_level str | None  — converted from jobPositionCareer
      employment_type  str | None  — converted from jobPositionEmployment
      location         str | None  — from workspacePlace.location
      posted_at        datetime | None
      deadline         date | None
    """
    roles: list[str] = []
    experience_level: str | None = None
    employment_type: str | None = None
    location: str | None = None

    try:
        job_position = opening.get("openingJobPosition", {})
        positions = job_position.get("openingJobPositions", [])
        if positions:
            # Use the first position for metadata (most postings have only one)
            pos = positions[0]

            # Role: prefer workspaceJob, fall back to workspaceOccupation
            ws_job = pos.get("workspaceJob")
            ws_occ = pos.get("workspaceOccupation")
            if ws_job and ws_job.get("job"):
                roles = [ws_job["job"]]
            elif ws_occ and ws_occ.get("occupation"):
                roles = [ws_occ["occupation"]]

            # Location
            ws_place = pos.get("workspacePlace") or {}
            location = ws_place.get("location") or None

            # Experience level
            experience_level = _career_to_experience_level(pos.get("jobPositionCareer"))

            # Employment type
            emp = pos.get("jobPositionEmployment") or {}
            emp_raw = emp.get("employmentType")
            if emp_raw:
                employment_type = _GREETING_EMPLOYMENT_MAP.get(emp_raw, emp_raw)
    except Exception as exc:
        log.debug("greeting: metadata extraction error: %s", exc)

    # posted_at / deadline
    posted_at: datetime | None = None
    deadline: date | None = None
    try:
        open_date_str = opening.get("openDate")
        if open_date_str:
            posted_at = datetime.fromisoformat(
                open_date_str.replace("Z", "+00:00")
            ).replace(tzinfo=None)
    except Exception:
        pass
    try:
        due_date_str = opening.get("dueDate")
        if due_date_str:
            deadline = datetime.fromisoformat(
                due_date_str.replace("Z", "+00:00")
            ).date()
    except Exception:
        pass

    return {
        "roles": roles,
        "experience_level": experience_level,
        "employment_type": employment_type,
        "location": location,
        "posted_at": posted_at,
        "deadline": deadline,
    }


_KNOWN_LOCALES: frozenset[str] = frozenset(
    {"ko", "en", "ja", "zh", "fr", "de", "es", "pt", "vi", "th", "id", "ms"}
)


def _build_self_hosted_job_url(base_url: str, opening_id: int | str) -> str:
    """Build a job detail URL for a self-hosted Greeting site.

    Derives the locale prefix from the source_url path.  For example:
      source_url = https://www.musinsacareers.com/ko/home  → /ko/o/{id}
      source_url = https://career.hyundai-autoever.com/ko/apply → /ko/o/{id}
      source_url = https://example.com/apply  → /o/{id}  (no locale)
    """
    parsed = urlparse(base_url)
    path_parts = [p for p in parsed.path.split("/") if p]
    # First path segment that is a well-known BCP-47 locale code
    locale: str | None = None
    for part in path_parts:
        if part in _KNOWN_LOCALES:
            locale = part
            break
    if locale:
        job_path = f"/{locale}/o/{opening_id}"
    else:
        job_path = f"/o/{opening_id}"
    return f"{parsed.scheme}://{parsed.netloc}{job_path}"


def _postings_from_next_data(
    openings: list[dict],
    base_url: str,
    company_name: str,
    source_id: str,
    config: _GreetingConfig,
) -> tuple[list[RawJobPosting], list[str]]:
    """Convert Greeting __NEXT_DATA__ openings list to RawJobPosting stubs.

    Returns (postings_without_description, warnings).  Description is None at
    this stage — callers should fill it in by fetching detail pages.

    URL construction:
      - careers.greeting.works hosted: uses /companies/{slug}/jobs/{id} form
        (not applicable here since those pages don't embed opening lists)
      - self-hosted: derives the job URL from base_url + opening_id
    """
    warnings: list[str] = []
    postings: list[RawJobPosting] = []
    is_self_hosted = _JOB_PATH_RE.search(urlparse(base_url).path) is None

    for opening in openings[: config.max_discover]:
        try:
            opening_id = opening.get("openingId")
            title = opening.get("title", "").strip()
            if not opening_id or not title:
                continue

            if is_self_hosted:
                job_url = _build_self_hosted_job_url(base_url, opening_id)
            else:
                # hosted pattern; slug is in the base_url path
                slug = urlparse(base_url).path.split("/")[2]
                job_url = (
                    f"{urlparse(base_url).scheme}://{urlparse(base_url).netloc}"
                    f"/companies/{slug}/jobs/{opening_id}"
                )

            meta = _extract_greeting_metadata(opening)
            postings.append(
                RawJobPosting(
                    source=source_id,
                    source_url=job_url,
                    company_name=company_name,
                    title=title,
                    employment_type=meta["employment_type"],
                    experience_level=meta["experience_level"],
                    deadline=meta["deadline"],
                    location=meta["location"],
                    description=None,  # filled later from detail page
                    source_external_id=str(opening_id),
                    skills=[],
                    roles=meta["roles"],
                    posted_at=meta["posted_at"],
                )
            )
        except Exception as exc:
            warnings.append(
                f"greeting: opening parse error id="
                f"{opening.get('openingId', '?')}: {exc}"
            )

    return postings, warnings


def _parse_greeting_job(
    html: str,
    url: str,
    company_name: str,
    source_id: str,
) -> RawJobPosting | None:
    """Parse a Greeting job detail page into a RawJobPosting.

    Metadata extraction priority:
    1. __NEXT_DATA__ jobPositionSetting (structured, from Greeting API payload)
    2. HTML page text (keyword scanning — fallback for description/employment/location)

    This function is still used for the hosted careers.greeting.works path
    (which doesn't embed an openings list in __NEXT_DATA__) and as a fallback
    when the list-page payload strategy is not available.
    """
    soup = BeautifulSoup(html, "lxml")

    title: str | None = None
    h1 = soup.find("h1")
    if h1:
        text = h1.get_text(separator=" ", strip=True)
        if text and len(text) < 200:
            title = text
    if not title:
        title_tag = soup.find("title")
        if title_tag:
            text = title_tag.get_text(strip=True)
            for suffix in _TITLE_STRIP_SUFFIXES:
                if text.endswith(suffix):
                    text = text[: -len(suffix)]
            text = text.strip()
            if text:
                title = text
    if not title:
        return None

    # ── Try to extract structured metadata from __NEXT_DATA__ ─────────────────
    roles: list[str] = []
    experience_level: str | None = None
    employment_type: str | None = None
    location: str | None = None
    deadline: date | None = None
    posted_at: datetime | None = None

    m = _NEXT_DATA_RE.search(html)
    if m:
        try:
            payload = json.loads(m.group(1))
            dehydrated = (
                payload.get("props", {}).get("pageProps", {}).get("dehydratedState", {})
            )
            for query in dehydrated.get("queries", []):
                key = query.get("queryKey", [])
                if len(key) >= 2 and key[1] == "getOpeningById":
                    inner = query.get("state", {}).get("data", {}).get("data", {})
                    # jobPositionSetting has the same structure as openingJobPositions
                    job_pos_setting = inner.get("jobPositionSetting", {})
                    positions = job_pos_setting.get("jobPositions", [])
                    opening_info = inner.get("openingsInfo", {})

                    if positions:
                        pos = positions[0]
                        ws_job = pos.get("workspaceJob")
                        ws_occ = pos.get("workspaceOccupation")
                        if ws_job and ws_job.get("job"):
                            roles = [ws_job["job"]]
                        elif ws_occ and ws_occ.get("occupation"):
                            roles = [ws_occ["occupation"]]

                        ws_place = pos.get("workspacePlace") or {}
                        location = ws_place.get("location") or None

                        experience_level = _career_to_experience_level(
                            pos.get("jobPositionCareer")
                        )

                        emp = pos.get("jobPositionEmployment") or {}
                        emp_raw = emp.get("employmentType")
                        if emp_raw:
                            employment_type = _GREETING_EMPLOYMENT_MAP.get(
                                emp_raw, emp_raw
                            )

                    # deadline / posted_at from openingsInfo
                    try:
                        open_date_str = opening_info.get("openDate")
                        if open_date_str:
                            posted_at = datetime.fromisoformat(
                                open_date_str.replace("Z", "+00:00")
                            ).replace(tzinfo=None)
                    except Exception:
                        pass
                    try:
                        due_date_str = opening_info.get("dueDate")
                        if due_date_str:
                            deadline = datetime.fromisoformat(
                                due_date_str.replace("Z", "+00:00")
                            ).date()
                    except Exception:
                        pass
                    break
        except Exception:
            pass

    # ── Fallback: keyword scan from page text ─────────────────────────────────
    page_text = soup.get_text()

    if employment_type is None:
        for kw in _EMPLOYMENT_KEYWORDS:
            if kw in page_text:
                employment_type = kw
                break

    if deadline is None:
        dm = _KR_DATE_RE.search(page_text)
        if dm:
            try:
                deadline = date(int(dm.group(1)), int(dm.group(2)), int(dm.group(3)))
            except ValueError:
                pass

    if location is None:
        for loc in _LOCATION_KEYWORDS:
            if loc in page_text:
                location = loc
                break

    # ── Description from main/article or __NEXT_DATA__ detail HTML ───────────
    description: str | None = _extract_next_data_detail_description(html)
    if description is None:
        main = (
            soup.find("main")
            or soup.find("article")
            or soup.find(attrs={"role": "main"})
        )
        if main:
            description = main.get_text(separator="\n", strip=True)[:2000]

    return RawJobPosting(
        source=source_id,
        source_url=url,
        company_name=company_name,
        title=title,
        employment_type=employment_type,
        experience_level=experience_level,
        deadline=deadline,
        location=location,
        description=description,
        source_external_id=_extract_job_id(url),
        skills=[],
        roles=roles,
        posted_at=posted_at,
    )


class GreetingParser(CustomParser):
    async def fetch(
        self,
        source: OfficialCompanySource,
        profile: CompanyProfile | None,
        options: CollectionOptions,
        collect_date: date,
    ) -> AdapterResult:
        if not source.source_url:
            return AdapterResult(
                warnings=[
                    f"greeting: no source_url" f" (company_id={source.company_id})"
                ],
                source_stats=AdapterSourceStats(),
            )

        company_name = (
            profile.canonical_name if profile else f"company_{source.company_id}"
        )
        config = _parse_greeting_config(source.config_json)
        sid = f"company_{source.company_id}_custom_greeting"
        timeout = settings.job_collection_timeout_seconds
        warnings: list[str] = []

        try:
            async with AsyncClient(
                timeout=timeout,
                headers={"User-Agent": "Briefy-Agent/1.0 (+https://briefy.io)"},
                follow_redirects=True,
            ) as client:
                try:
                    list_resp = await client.get(source.source_url)
                    list_resp.raise_for_status()
                except TimeoutException:
                    return AdapterResult(
                        warnings=[
                            f"greeting: list page timeout"
                            f" (company_id={source.company_id})"
                        ],
                        source_stats=AdapterSourceStats(),
                    )
                except HTTPStatusError as exc:
                    return AdapterResult(
                        warnings=[
                            f"greeting: list page HTTP"
                            f" {exc.response.status_code}"
                            f" (company_id={source.company_id})"
                        ],
                        source_stats=AdapterSourceStats(),
                    )

                list_html = list_resp.text

                # ── Strategy A: __NEXT_DATA__ openings payload ─────────────────
                next_data_openings = _extract_next_data_openings(list_html)
                if next_data_openings is not None:
                    if len(next_data_openings) == 0:
                        # Payload present but empty — genuine 0 openings
                        warnings.append(
                            f"greeting: __NEXT_DATA__ openings present but empty"
                            f" (company_id={source.company_id})"
                        )
                        return AdapterResult(
                            postings=[],
                            warnings=warnings,
                            source_stats=AdapterSourceStats(
                                discovered=0,
                                fetched=0,
                                parsed=0,
                                selected=0,
                            ),
                        )

                    stubs, parse_warnings = _postings_from_next_data(
                        next_data_openings,
                        source.source_url,
                        company_name,
                        sid,
                        config,
                    )
                    warnings.extend(parse_warnings)

                    discovered = len(stubs)
                    to_fetch_stubs = stubs[: config.max_fetch]
                    postings: list[RawJobPosting] = []

                    for stub in to_fetch_stubs:
                        try:
                            detail_resp = await client.get(stub.source_url)
                            detail_resp.raise_for_status()
                            desc = _extract_next_data_detail_description(
                                detail_resp.text
                            )
                            if desc is None:
                                # fallback: extract from main/article
                                detail_soup = BeautifulSoup(detail_resp.text, "lxml")
                                main = (
                                    detail_soup.find("main")
                                    or detail_soup.find("article")
                                    or detail_soup.find(attrs={"role": "main"})
                                )
                                if main:
                                    desc = main.get_text(separator="\n", strip=True)[
                                        :2000
                                    ]
                            # Attach description; keep all other metadata from stub
                            postings.append(
                                RawJobPosting(
                                    source=stub.source,
                                    source_url=stub.source_url,
                                    company_name=stub.company_name,
                                    title=stub.title,
                                    employment_type=stub.employment_type,
                                    experience_level=stub.experience_level,
                                    deadline=stub.deadline,
                                    location=stub.location,
                                    description=desc,
                                    source_external_id=stub.source_external_id,
                                    skills=stub.skills,
                                    roles=stub.roles,
                                    posted_at=stub.posted_at,
                                )
                            )
                        except TimeoutException:
                            warnings.append(
                                f"greeting: detail timeout: {stub.source_url}"
                            )
                            # Keep stub without description rather than dropping it
                            postings.append(stub)
                        except HTTPStatusError as exc:
                            warnings.append(
                                f"greeting: detail HTTP"
                                f" {exc.response.status_code}: {stub.source_url}"
                            )
                            postings.append(stub)
                        except Exception as exc:
                            warnings.append(
                                f"greeting: detail error" f" {stub.source_url}: {exc}"
                            )
                            postings.append(stub)

                    stats = AdapterSourceStats(
                        discovered=discovered,
                        fetched=len(to_fetch_stubs),
                        parsed=len(postings),
                        selected=len(postings),
                    )
                    return AdapterResult(
                        postings=postings,
                        warnings=warnings,
                        source_stats=stats,
                    )

                # ── Strategy B: anchor-link discovery + detail-page parsing ───
                # Used when __NEXT_DATA__ doesn't contain an openings list
                # (e.g. careers.greeting.works hosted pages).
                job_urls = _discover_job_urls(list_html, source.source_url, config)
                discovered = len(job_urls)
                if discovered == 0:
                    warnings.append(
                        f"greeting: no job URLs found via anchor-link discovery"
                        f" and no __NEXT_DATA__ openings"
                        f" (company_id={source.company_id},"
                        f" url={source.source_url})"
                    )
                to_fetch = job_urls[: config.max_fetch]

                postings_b: list[RawJobPosting] = []
                for job_url in to_fetch:
                    try:
                        detail_resp = await client.get(job_url)
                        detail_resp.raise_for_status()
                        posting = _parse_greeting_job(
                            detail_resp.text, job_url, company_name, sid
                        )
                        if posting is not None:
                            postings_b.append(posting)
                    except TimeoutException:
                        warnings.append(f"greeting: detail timeout: {job_url}")
                    except HTTPStatusError as exc:
                        warnings.append(
                            f"greeting: detail HTTP"
                            f" {exc.response.status_code}: {job_url}"
                        )
                    except Exception as exc:
                        warnings.append(f"greeting: detail error {job_url}: {exc}")

        except Exception as exc:
            msg = (
                f"greeting: unexpected error" f" company_id={source.company_id}: {exc}"
            )
            log.warning(msg)
            return AdapterResult(
                warnings=[msg],
                source_stats=AdapterSourceStats(),
            )

        stats = AdapterSourceStats(
            discovered=discovered,
            fetched=len(to_fetch),
            parsed=len(postings_b),
            selected=len(postings_b),
        )
        return AdapterResult(postings=postings_b, warnings=warnings, source_stats=stats)


async def greeting_preflight(source: OfficialCompanySource) -> dict:
    """Check if a Greeting company page is reachable and parse a sample job.

    Returns dict with keys:
      reachable (bool), discovered_count (int),
      sample_parsed (dict | None), warnings (list[str])
    """
    if not source.source_url:
        return {
            "reachable": False,
            "discovered_count": 0,
            "sample_parsed": None,
            "warnings": ["no source_url"],
        }

    config = _parse_greeting_config(source.config_json)
    timeout = settings.job_collection_timeout_seconds
    warnings: list[str] = []

    try:
        async with AsyncClient(
            timeout=timeout,
            headers={"User-Agent": "Briefy-Agent/1.0 (+https://briefy.io)"},
            follow_redirects=True,
        ) as client:
            try:
                resp = await client.get(source.source_url)
                resp.raise_for_status()
            except (TimeoutException, HTTPStatusError) as exc:
                return {
                    "reachable": False,
                    "discovered_count": 0,
                    "sample_parsed": None,
                    "warnings": [str(exc)],
                }

            list_html = resp.text
            company_name = f"company_{source.company_id}"
            sid = "preflight"

            # Try __NEXT_DATA__ first
            next_data_openings = _extract_next_data_openings(list_html)
            if next_data_openings is not None:
                stubs, _ = _postings_from_next_data(
                    next_data_openings, source.source_url, company_name, sid, config
                )
                discovered_count = len(stubs)
                sample_parsed: dict | None = None

                if stubs:
                    sample_stub = stubs[0]
                    try:
                        detail_resp = await client.get(sample_stub.source_url)
                        detail_resp.raise_for_status()
                        # fetch detail page so any redirect is validated;
                        # description not needed for preflight output
                        _extract_next_data_detail_description(detail_resp.text)
                        sample_parsed = {
                            "title": sample_stub.title,
                            "employment_type": sample_stub.employment_type,
                            "deadline": (
                                str(sample_stub.deadline)
                                if sample_stub.deadline
                                else None
                            ),
                            "location": sample_stub.location,
                            "experience_level": sample_stub.experience_level,
                            "roles": sample_stub.roles,
                            "source_external_id": sample_stub.source_external_id,
                        }
                    except Exception as exc:
                        warnings.append(f"sample fetch failed: {exc}")
                        sample_parsed = {
                            "title": sample_stub.title,
                            "employment_type": sample_stub.employment_type,
                            "deadline": (
                                str(sample_stub.deadline)
                                if sample_stub.deadline
                                else None
                            ),
                            "location": sample_stub.location,
                            "experience_level": sample_stub.experience_level,
                            "roles": sample_stub.roles,
                            "source_external_id": sample_stub.source_external_id,
                        }

                return {
                    "reachable": True,
                    "discovered_count": discovered_count,
                    "sample_parsed": sample_parsed,
                    "warnings": warnings,
                }

            # Fallback: anchor-link discovery
            job_urls = _discover_job_urls(list_html, source.source_url, config)
            sample_parsed = None

            if job_urls:
                try:
                    detail_resp = await client.get(job_urls[0])
                    detail_resp.raise_for_status()
                    posting = _parse_greeting_job(
                        detail_resp.text,
                        job_urls[0],
                        company_name,
                        sid,
                    )
                    if posting is not None:
                        sample_parsed = {
                            "title": posting.title,
                            "employment_type": posting.employment_type,
                            "deadline": (
                                str(posting.deadline) if posting.deadline else None
                            ),
                            "location": posting.location,
                            "source_external_id": posting.source_external_id,
                        }
                except Exception as exc:
                    warnings.append(f"sample fetch failed: {exc}")

    except Exception as exc:
        return {
            "reachable": False,
            "discovered_count": 0,
            "sample_parsed": None,
            "warnings": [str(exc)],
        }

    return {
        "reachable": True,
        "discovered_count": len(job_urls),
        "sample_parsed": sample_parsed,
        "warnings": warnings,
    }


# Self-registration: executed on first import of this module.
_CUSTOM_REGISTRY_BY_KEY["GREETING"] = GreetingParser()
