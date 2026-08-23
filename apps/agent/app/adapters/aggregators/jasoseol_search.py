"""Jasoseol public /search HTML discovery.

Fetches jasoseol.com/search?dutyGroupIds=...&page=N and extracts posting
candidate URLs.  Used only for Targeted Discovery (pre-detail-fetch URL
collection), not as an authoritative data source.

All network calls are public, unauthenticated GET requests.
No browser automation, no login, no private/internal API calls.
No CAPTCHA bypass.
"""

import json
import logging
import re
from dataclasses import dataclass

from httpx import AsyncClient, HTTPStatusError, TimeoutException

log = logging.getLogger(__name__)

_NEXT_DATA_RE = re.compile(
    r'<script\s+id="__NEXT_DATA__"[^>]*>(.*?)</script>',
    re.DOTALL,
)
# Fallback: bare /recruit/<id> or /intern/<id> links in HTML
_LINK_RE = re.compile(r'href=["\']/(recruit|intern)/(\d+)["\']')


@dataclass(frozen=True)
class SearchCandidate:
    url: str
    source_external_id: str


def parse_search_html(
    html: str, base_url: str = "https://jasoseol.com"
) -> list[SearchCandidate]:
    """Extract posting candidate URLs from a Jasoseol /search HTML page.

    Primary strategy: parse __NEXT_DATA__ JSON for structured posting list.
    Fallback: regex scan for /recruit/<id> and /intern/<id> href attributes.

    Returns deduplicated SearchCandidate list preserving result-page order.
    """
    candidates: list[SearchCandidate] = []
    seen: set[str] = set()

    def _add(path_type: str, pid: str) -> None:
        url = f"{base_url}/{path_type}/{pid}"
        if pid not in seen:
            seen.add(pid)
            candidates.append(SearchCandidate(url=url, source_external_id=pid))

    # Primary: structured __NEXT_DATA__
    nd_match = _NEXT_DATA_RE.search(html)
    if nd_match:
        try:
            data = json.loads(nd_match.group(1))
            queries = (
                data.get("props", {})
                .get("pageProps", {})
                .get("dehydratedState", {})
                .get("queries", [])
            )
            for q in queries:
                key_str = str(q.get("queryKey", ""))
                if "jobSearch" not in key_str:
                    continue
                postings = (
                    q.get("state", {}).get("data", {}).get("data", [])
                )
                for p in postings:
                    pid = p.get("id")
                    if not pid:
                        continue
                    recruit_type = p.get("recruit_type", 0)
                    path_type = "recruit" if recruit_type == 0 else "intern"
                    _add(path_type, str(pid))
                if candidates:
                    return candidates  # found via primary path
        except (KeyError, TypeError, json.JSONDecodeError) as exc:
            log.debug("jasoseol_search: __NEXT_DATA__ parse failed: %s", exc)

    # Fallback: regex on raw HTML
    for path_type, pid in _LINK_RE.findall(html):
        _add(path_type, pid)

    return candidates


async def discover_from_search(
    client: AsyncClient,
    base_url: str,
    duty_ids: list[int],
    limit: int,
    warnings: list[str],
) -> list[SearchCandidate]:
    """Fetch paginated Jasoseol /search pages and collect posting candidates.

    Stops when `limit` unique candidates have been collected or there are no
    more results.  Pages are fetched sequentially to respect the site.

    Returns a deduplicated list of SearchCandidate, order preserved from the
    search result pages (most recent first per Jasoseol default sort).
    """
    if not duty_ids or limit <= 0:
        return []

    ids_param = ",".join(str(i) for i in sorted(duty_ids))
    all_candidates: list[SearchCandidate] = []
    seen_ids: set[str] = set()
    page = 1

    while len(all_candidates) < limit:
        url = f"{base_url}/search?dutyGroupIds={ids_param}&page={page}"
        try:
            resp = await client.get(url)
            resp.raise_for_status()
        except TimeoutException:
            msg = f"jasoseol_search: timeout fetching page {page}"
            log.warning(msg)
            warnings.append(msg)
            break
        except HTTPStatusError as exc:
            msg = (
                f"jasoseol_search: HTTP {exc.response.status_code} "
                f"fetching page {page}"
            )
            log.warning(msg)
            warnings.append(msg)
            break
        except Exception as exc:
            msg = f"jasoseol_search: error fetching page {page}: {exc}"
            log.warning(msg)
            warnings.append(msg)
            break

        page_candidates = parse_search_html(resp.text, base_url)
        if not page_candidates:
            break  # no more results

        new_on_page = 0
        for c in page_candidates:
            if c.source_external_id not in seen_ids:
                seen_ids.add(c.source_external_id)
                all_candidates.append(c)
                new_on_page += 1
            if len(all_candidates) >= limit:
                break

        if new_on_page == 0:
            break  # all results on this page were already seen (de-paged)

        page += 1

    log.info(
        "jasoseol_search: duty_ids=%s pages_fetched=%d candidates=%d",
        ids_param,
        page - 1,
        len(all_candidates),
    )
    return all_candidates[:limit]
