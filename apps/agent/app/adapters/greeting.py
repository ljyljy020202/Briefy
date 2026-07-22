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
"""

import json
import logging
import re
from dataclasses import dataclass, field
from datetime import date
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

        if config.include_paths and not any(
            p in path for p in config.include_paths
        ):
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


def _parse_greeting_job(
    html: str,
    url: str,
    company_name: str,
    source_id: str,
) -> RawJobPosting | None:
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

    page_text = soup.get_text()

    employment_type: str | None = None
    for kw in _EMPLOYMENT_KEYWORDS:
        if kw in page_text:
            employment_type = kw
            break

    deadline: date | None = None
    dm = _KR_DATE_RE.search(page_text)
    if dm:
        try:
            deadline = date(int(dm.group(1)), int(dm.group(2)), int(dm.group(3)))
        except ValueError:
            pass

    location: str | None = None
    for loc in _LOCATION_KEYWORDS:
        if loc in page_text:
            location = loc
            break

    description: str | None = None
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
        deadline=deadline,
        location=location,
        description=description,
        source_external_id=_extract_job_id(url),
        skills=[],
        roles=[],
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
                    f"greeting: no source_url"
                    f" (company_id={source.company_id})"
                ],
                source_stats=AdapterSourceStats(),
            )

        company_name = (
            profile.canonical_name
            if profile
            else f"company_{source.company_id}"
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

                job_urls = _discover_job_urls(
                    list_resp.text, source.source_url, config
                )
                discovered = len(job_urls)
                to_fetch = job_urls[: config.max_fetch]

                postings: list[RawJobPosting] = []
                for job_url in to_fetch:
                    try:
                        detail_resp = await client.get(job_url)
                        detail_resp.raise_for_status()
                        posting = _parse_greeting_job(
                            detail_resp.text, job_url, company_name, sid
                        )
                        if posting is not None:
                            postings.append(posting)
                    except TimeoutException:
                        warnings.append(
                            f"greeting: detail timeout: {job_url}"
                        )
                    except HTTPStatusError as exc:
                        warnings.append(
                            f"greeting: detail HTTP"
                            f" {exc.response.status_code}: {job_url}"
                        )
                    except Exception as exc:
                        warnings.append(
                            f"greeting: detail error {job_url}: {exc}"
                        )

        except Exception as exc:
            msg = (
                f"greeting: unexpected error"
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
        return AdapterResult(
            postings=postings, warnings=warnings, source_stats=stats
        )


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

            job_urls = _discover_job_urls(resp.text, source.source_url, config)

            sample_parsed: dict | None = None
            if job_urls:
                try:
                    detail_resp = await client.get(job_urls[0])
                    detail_resp.raise_for_status()
                    posting = _parse_greeting_job(
                        detail_resp.text,
                        job_urls[0],
                        f"company_{source.company_id}",
                        "preflight",
                    )
                    if posting is not None:
                        sample_parsed = {
                            "title": posting.title,
                            "employment_type": posting.employment_type,
                            "deadline": (
                                str(posting.deadline)
                                if posting.deadline
                                else None
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
