"""Tests for JasoseolAdapter hybrid (Targeted + Exploration) discovery.

All tests are offline.  HTML fixtures are used; no real network calls.

Key distinction:
  PRE-FETCH:  TARGETED (search) vs EXPLORATION (sitemap) budget allocation.
  POST-PARSE: _select_postings() relevance vs recency selection.
  These are two separate steps and must not be confused.
"""

from datetime import date
from pathlib import Path

import httpx
from httpx import TimeoutException

from app.adapters.base import AdapterResult
import pytest

from app.adapters.jasoseol import (
    JasoseolAdapter,
    _EXPLORATION_ENABLED,
    _allocate_fetch_budget,
    _CandidateEntry,
    _merge_candidates,
)
from app.adapters.jasoseol_search import SearchCandidate
from app.schemas.collection import CollectionOptions, SeedKeywords

_FIXTURES = Path(__file__).parent / "fixtures"


def _read(name: str) -> str:
    return (_FIXTURES / name).read_text(encoding="utf-8")


_BASE = "https://jasoseol.com"
_COLLECT_DATE = date(2026, 7, 9)
_VALID_HTML = _read("jasoseol_recruit_page.html")
_EMPTY_SITEMAP = (
    '<?xml version="1.0" encoding="UTF-8"?>'
    '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9"></urlset>'
)
_SITEMAP_1 = f"{_BASE}/sitemap/employment_companies.xml"
_SITEMAP_2 = f"{_BASE}/sitemap/intern_employment_companies.xml"


def _sitemap_xml(entries: list[tuple[str, str]]) -> str:
    """Build sitemap XML from [(url, lastmod)] pairs."""
    items = "".join(
        f"<url><loc>{url}</loc><lastmod>{lastmod}</lastmod></url>"
        for url, lastmod in entries
    )
    return (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">'
        f"{items}</urlset>"
    )


def _candidate(url_id: str, provenance: str, lastmod: date = date.min) -> _CandidateEntry:  # noqa: E501
    return _CandidateEntry(
        url=f"{_BASE}/recruit/{url_id}",
        source_external_id=url_id,
        lastmod=lastmod,
        provenance=provenance,
    )


class _MockHybridClient:
    def __init__(self, url_responses: dict):
        self._responses = url_responses

    async def __aenter__(self):
        return self

    async def __aexit__(self, *args):
        pass

    async def get(self, url: str):
        request = httpx.Request("GET", url)
        response = self._responses.get(str(url))
        if response is None:
            return httpx.Response(404, text="Not Found", request=request)
        if isinstance(response, BaseException):
            raise response
        status, body = response
        return httpx.Response(status, text=body, request=request)


# ── _merge_candidates ─────────────────────────────────────────────────────────


def test_merge_targeted_only():
    targeted = [SearchCandidate(url=f"{_BASE}/recruit/1", source_external_id="1")]
    result = _merge_candidates(targeted, [], discovery_limit=100)
    assert len(result) == 1
    assert result[0].provenance == "TARGETED"


def test_merge_exploration_only():
    exploration = [(date(2026, 7, 9), f"{_BASE}/recruit/2")]
    result = _merge_candidates([], exploration, discovery_limit=100)
    assert len(result) == 1
    assert result[0].provenance == "EXPLORATION"


def test_merge_both_when_same_id():
    targeted = [SearchCandidate(url=f"{_BASE}/recruit/10", source_external_id="10")]
    exploration = [(date(2026, 7, 9), f"{_BASE}/recruit/10")]
    result = _merge_candidates(targeted, exploration, discovery_limit=100)
    assert len(result) == 1
    assert result[0].provenance == "BOTH"


def test_merge_both_preserves_sitemap_lastmod():
    targeted = [SearchCandidate(url=f"{_BASE}/recruit/10", source_external_id="10")]
    exploration = [(date(2026, 7, 9), f"{_BASE}/recruit/10")]
    result = _merge_candidates(targeted, exploration, discovery_limit=100)
    assert result[0].lastmod == date(2026, 7, 9)


def test_merge_respects_discovery_limit():
    targeted = [
        SearchCandidate(url=f"{_BASE}/recruit/{i}", source_external_id=str(i))
        for i in range(5)
    ]
    exploration = [(date(2026, 7, 9), f"{_BASE}/recruit/{i}") for i in range(5, 10)]
    result = _merge_candidates(targeted, exploration, discovery_limit=7)
    assert len(result) <= 7


def test_merge_counts_unique_candidates():
    targeted = [
        SearchCandidate(url=f"{_BASE}/recruit/1", source_external_id="1"),
        SearchCandidate(url=f"{_BASE}/recruit/2", source_external_id="2"),
    ]
    exploration = [
        (date(2026, 7, 9), f"{_BASE}/recruit/1"),  # overlap with targeted
        (date(2026, 7, 8), f"{_BASE}/recruit/3"),
    ]
    result = _merge_candidates(targeted, exploration, discovery_limit=100)
    ids = {c.source_external_id for c in result}
    assert ids == {"1", "2", "3"}


def test_merge_same_posting_both_paths_fetched_once():
    targeted = [SearchCandidate(url=f"{_BASE}/recruit/42", source_external_id="42")]
    exploration = [(date(2026, 7, 9), f"{_BASE}/recruit/42")]
    result = _merge_candidates(targeted, exploration, discovery_limit=100)
    assert sum(1 for c in result if c.source_external_id == "42") == 1


# ── _allocate_fetch_budget ────────────────────────────────────────────────────


def test_allocate_prefers_targeted_80pct():
    candidates = (
        [_candidate(str(i), "TARGETED") for i in range(100, 180)]
        + [_candidate(str(i), "EXPLORATION", date(2026, 7, 9)) for i in range(200, 230)]
    )
    result = _allocate_fetch_budget(
        candidates, detail_fetch_limit=100, targeted_ratio=0.8
    )
    targeted_count = sum(1 for c in result if c.provenance in ("TARGETED", "BOTH"))
    exploration_count = sum(1 for c in result if c.provenance == "EXPLORATION")
    assert targeted_count == 80
    assert exploration_count == 20


def test_allocate_targeted_shortfall_backfills_exploration():
    # Only 30 targeted candidates; remainder should come from exploration
    candidates = (
        [_candidate(str(i), "TARGETED") for i in range(100, 130)]
        + [_candidate(str(i), "EXPLORATION", date(2026, 7, 9)) for i in range(200, 280)]
    )
    result = _allocate_fetch_budget(
        candidates, detail_fetch_limit=100, targeted_ratio=0.8
    )
    targeted_count = sum(1 for c in result if c.provenance in ("TARGETED", "BOTH"))
    assert targeted_count == 30
    assert len(result) == 100


def test_allocate_exploration_shortfall_backfills_targeted():
    # Only 5 exploration candidates; remainder should come from targeted
    candidates = (
        [_candidate(str(i), "TARGETED") for i in range(100, 195)]
        + [_candidate(str(i), "EXPLORATION", date(2026, 7, 9)) for i in range(200, 205)]
    )
    result = _allocate_fetch_budget(
        candidates, detail_fetch_limit=100, targeted_ratio=0.8
    )
    exploration_count = sum(1 for c in result if c.provenance == "EXPLORATION")
    assert exploration_count == 5
    assert len(result) == 100


def test_allocate_never_exceeds_detail_fetch_limit():
    candidates = (
        [_candidate(str(i), "TARGETED") for i in range(200)]
        + [
            _candidate(str(i + 200), "EXPLORATION", date(2026, 7, 9))
            for i in range(200)
        ]
    )
    result = _allocate_fetch_budget(
        candidates, detail_fetch_limit=100, targeted_ratio=0.8
    )
    assert len(result) <= 100


def test_allocate_deterministic():
    candidates = (
        [_candidate(str(i), "TARGETED") for i in range(50)]
        + [_candidate(str(i + 50), "EXPLORATION", date(2026, 7, 9)) for i in range(50)]
    )
    r1 = _allocate_fetch_budget(
        candidates, detail_fetch_limit=50, targeted_ratio=0.8
    )
    r2 = _allocate_fetch_budget(
        candidates, detail_fetch_limit=50, targeted_ratio=0.8
    )
    assert [c.url for c in r1] == [c.url for c in r2]


def test_allocate_both_provenance_counts_as_targeted():
    candidates = (
        [_candidate(str(i), "BOTH", date(2026, 7, 9)) for i in range(50)]
        + [_candidate(str(i + 50), "EXPLORATION", date(2026, 7, 9)) for i in range(50)]
    )
    result = _allocate_fetch_budget(
        candidates, detail_fetch_limit=100, targeted_ratio=0.8
    )
    targeted_count = sum(1 for c in result if c.provenance in ("TARGETED", "BOTH"))
    assert targeted_count == 50  # all BOTH candidates consumed
    assert len(result) == 100


def test_allocate_few_total_candidates():
    candidates = (
        [_candidate("1", "TARGETED")]
        + [_candidate("2", "EXPLORATION", date(2026, 7, 9))]
    )
    result = _allocate_fetch_budget(
        candidates, detail_fetch_limit=100, targeted_ratio=0.8
    )
    assert len(result) == 2


def test_allocate_targeted_sorted_by_id_desc():
    candidates = [_candidate(str(i), "TARGETED") for i in [100, 300, 200]]
    result = _allocate_fetch_budget(
        candidates, detail_fetch_limit=2, targeted_ratio=1.0
    )
    assert result[0].source_external_id == "300"  # highest id first
    assert result[1].source_external_id == "200"


def test_allocate_exploration_sorted_by_lastmod_desc():
    candidates = [
        _candidate("1", "EXPLORATION", date(2026, 7, 1)),
        _candidate("2", "EXPLORATION", date(2026, 7, 9)),
        _candidate("3", "EXPLORATION", date(2026, 7, 5)),
    ]
    result = _allocate_fetch_budget(
        candidates, detail_fetch_limit=2, targeted_ratio=0.0
    )
    assert result[0].source_external_id == "2"  # most recent first
    assert result[1].source_external_id == "3"


# ── JasoseolAdapter.fetch — hybrid integration ────────────────────────────────


async def test_adapter_with_backend_role_performs_targeted_discovery(monkeypatch):
    """Adapter must call /search when roles map to known duty groups."""
    search_url = f"{_BASE}/search?dutyGroupIds=176&page=1"
    search_html = _read("jasoseol_search_page.html")
    sitemap_item = (
        f"<url><loc>{_BASE}/recruit/201001</loc>"
        "<lastmod>2026-07-09T10:00:00+09:00</lastmod></url>"
    )
    sitemap_body = (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">'
        f"{sitemap_item}</urlset>"
    )
    responses = {
        search_url: (200, search_html),
        _SITEMAP_1: (200, sitemap_body),
        _SITEMAP_2: (200, _EMPTY_SITEMAP),
        f"{_BASE}/recruit/201001": (200, _VALID_HTML),
        f"{_BASE}/recruit/201002": (200, _VALID_HTML),
        f"{_BASE}/intern/201003": (200, _VALID_HTML),
    }
    monkeypatch.setattr(
        "app.adapters.jasoseol.AsyncClient",
        lambda **kw: _MockHybridClient(responses),
    )
    seed = SeedKeywords(roles=["백엔드 개발자"])
    result = await JasoseolAdapter().fetch(seed, CollectionOptions(), _COLLECT_DATE)
    assert isinstance(result, AdapterResult)
    assert result.source_stats is not None
    assert result.source_stats.discovered >= 1


async def test_adapter_empty_roles_skips_targeted_discovery(monkeypatch):
    """Empty roles → no search requests, only sitemap."""
    called_urls: list[str] = []

    class _TrackingClient:
        async def __aenter__(self):
            return self

        async def __aexit__(self, *args):
            pass

        async def get(self, url: str):
            called_urls.append(str(url))
            request = httpx.Request("GET", url)
            if "sitemap" in url:
                return httpx.Response(200, text=_EMPTY_SITEMAP, request=request)
            return httpx.Response(404, text="Not Found", request=request)

    monkeypatch.setattr(
        "app.adapters.jasoseol.AsyncClient",
        lambda **kw: _TrackingClient(),
    )
    await JasoseolAdapter().fetch(SeedKeywords(), CollectionOptions(), _COLLECT_DATE)
    assert not any("/search" in u for u in called_urls)


async def test_adapter_unknown_roles_skips_targeted_discovery(monkeypatch):
    """Unknown roles (e.g., nurse) → no search, sitemap only."""
    called_urls: list[str] = []

    class _TrackingClient:
        async def __aenter__(self):
            return self

        async def __aexit__(self, *args):
            pass

        async def get(self, url: str):
            called_urls.append(str(url))
            request = httpx.Request("GET", url)
            if "sitemap" in url:
                return httpx.Response(200, text=_EMPTY_SITEMAP, request=request)
            return httpx.Response(404, text="", request=request)

    monkeypatch.setattr(
        "app.adapters.jasoseol.AsyncClient",
        lambda **kw: _TrackingClient(),
    )
    seed = SeedKeywords(roles=["간호사", "영업직"])
    await JasoseolAdapter().fetch(seed, CollectionOptions(), _COLLECT_DATE)
    assert not any("/search" in u for u in called_urls)


@pytest.mark.skipif(not _EXPLORATION_ENABLED, reason="sitemap exploration disabled")
async def test_adapter_targeted_search_timeout_falls_back_to_sitemap(monkeypatch):
    """Search timeout must not fail collection; sitemap results are preserved."""
    sitemap_xml = _sitemap_xml(
        [(f"{_BASE}/recruit/104949", "2026-07-09T10:00:00+09:00")]
    )
    responses = {
        f"{_BASE}/search?dutyGroupIds=176&page=1": TimeoutException("timeout"),
        _SITEMAP_1: (200, sitemap_xml),
        _SITEMAP_2: (200, _EMPTY_SITEMAP),
        f"{_BASE}/recruit/104949": (200, _VALID_HTML),
    }
    monkeypatch.setattr(
        "app.adapters.jasoseol.AsyncClient",
        lambda **kw: _MockHybridClient(responses),
    )
    seed = SeedKeywords(roles=["백엔드 개발자"])
    result = await JasoseolAdapter().fetch(seed, CollectionOptions(), _COLLECT_DATE)
    assert len(result.postings) >= 1
    assert any("timeout" in w or "targeted" in w.lower() for w in result.warnings)


@pytest.mark.skipif(not _EXPLORATION_ENABLED, reason="sitemap exploration disabled")
async def test_adapter_stats_discovered_is_unique_merged_count(monkeypatch):
    search_html = _read("jasoseol_search_page.html")  # IDs: 201001, 201002, 201003
    sitemap_xml = _sitemap_xml([
        (f"{_BASE}/recruit/201001", "2026-07-09T10:00:00+09:00"),  # overlap
        (f"{_BASE}/recruit/104949", "2026-07-09T10:00:00+09:00"),  # unique
    ])
    responses = {
        f"{_BASE}/search?dutyGroupIds=176&page=1": (200, search_html),
        _SITEMAP_1: (200, sitemap_xml),
        _SITEMAP_2: (200, _EMPTY_SITEMAP),
        f"{_BASE}/recruit/201001": (200, _VALID_HTML),
        f"{_BASE}/recruit/201002": (200, _VALID_HTML),
        f"{_BASE}/intern/201003": (200, _VALID_HTML),
        f"{_BASE}/recruit/104949": (200, _VALID_HTML),
    }
    monkeypatch.setattr(
        "app.adapters.jasoseol.AsyncClient",
        lambda **kw: _MockHybridClient(responses),
    )
    seed = SeedKeywords(roles=["백엔드 개발자"])
    result = await JasoseolAdapter().fetch(seed, CollectionOptions(), _COLLECT_DATE)
    assert result.source_stats is not None
    # 3 from search + 1 unique from sitemap (201001 is BOTH, not counted twice)
    assert result.source_stats.discovered == 4


async def test_adapter_fetch_count_equals_actual_detail_fetches(monkeypatch):
    search_html = _read("jasoseol_search_page.html")  # 3 unique candidates
    responses = {
        f"{_BASE}/search?dutyGroupIds=176&page=1": (200, search_html),
        _SITEMAP_1: (200, _EMPTY_SITEMAP),
        _SITEMAP_2: (200, _EMPTY_SITEMAP),
        f"{_BASE}/recruit/201001": (200, _VALID_HTML),
        f"{_BASE}/recruit/201002": (200, _VALID_HTML),
        f"{_BASE}/intern/201003": (200, _VALID_HTML),
    }
    monkeypatch.setattr(
        "app.adapters.jasoseol.AsyncClient",
        lambda **kw: _MockHybridClient(responses),
    )
    seed = SeedKeywords(roles=["백엔드 개발자"])
    result = await JasoseolAdapter().fetch(seed, CollectionOptions(), _COLLECT_DATE)
    assert result.source_stats is not None
    assert result.source_stats.fetched == 3  # exactly 3 unique fetches
    assert result.source_stats.parsed == 3


async def test_adapter_total_discovery_respects_limit(monkeypatch):
    search_html = _read("jasoseol_search_page.html")  # 3 candidates
    sitemap_xml = _sitemap_xml([
        (f"{_BASE}/recruit/{i}", "2026-07-09T10:00:00+09:00")
        for i in range(90000, 90010)
    ])
    responses = {
        f"{_BASE}/search?dutyGroupIds=176&page=1": (200, search_html),
        _SITEMAP_1: (200, sitemap_xml),
        _SITEMAP_2: (200, _EMPTY_SITEMAP),
    }
    for i in range(90000, 90010):
        responses[f"{_BASE}/recruit/{i}"] = (200, _VALID_HTML)
    for uid in ["201001", "201002", "201003"]:
        responses[f"{_BASE}/recruit/{uid}"] = (200, _VALID_HTML)
        responses[f"{_BASE}/intern/{uid}"] = (200, _VALID_HTML)

    monkeypatch.setattr(
        "app.adapters.jasoseol.AsyncClient",
        lambda **kw: _MockHybridClient(responses),
    )
    seed = SeedKeywords(roles=["백엔드 개발자"])
    options = CollectionOptions(discovery_limit_per_source=5)
    result = await JasoseolAdapter().fetch(seed, options, _COLLECT_DATE)
    assert result.source_stats is not None
    assert result.source_stats.discovered <= 5


async def test_adapter_total_fetch_respects_limit(monkeypatch):
    search_html = _read("jasoseol_search_page.html")  # 3 candidates
    sitemap_xml = _sitemap_xml([
        (f"{_BASE}/recruit/{i}", "2026-07-09T10:00:00+09:00")
        for i in range(90000, 90005)
    ])
    responses = {
        f"{_BASE}/search?dutyGroupIds=176&page=1": (200, search_html),
        _SITEMAP_1: (200, sitemap_xml),
        _SITEMAP_2: (200, _EMPTY_SITEMAP),
    }
    for i in range(90000, 90005):
        responses[f"{_BASE}/recruit/{i}"] = (200, _VALID_HTML)
    for uid in ["201001", "201002"]:
        responses[f"{_BASE}/recruit/{uid}"] = (200, _VALID_HTML)
    responses[f"{_BASE}/intern/201003"] = (200, _VALID_HTML)

    monkeypatch.setattr(
        "app.adapters.jasoseol.AsyncClient",
        lambda **kw: _MockHybridClient(responses),
    )
    seed = SeedKeywords(roles=["백엔드 개발자"])
    options = CollectionOptions(
        discovery_limit_per_source=100, detail_fetch_limit_per_source=2
    )
    result = await JasoseolAdapter().fetch(seed, options, _COLLECT_DATE)
    assert result.source_stats is not None
    assert result.source_stats.fetched <= 2


async def test_adapter_final_count_equals_returned_postings(monkeypatch):
    search_html = _read("jasoseol_search_page.html")
    responses = {
        f"{_BASE}/search?dutyGroupIds=176&page=1": (200, search_html),
        _SITEMAP_1: (200, _EMPTY_SITEMAP),
        _SITEMAP_2: (200, _EMPTY_SITEMAP),
        f"{_BASE}/recruit/201001": (200, _VALID_HTML),
        f"{_BASE}/recruit/201002": (200, _VALID_HTML),
        f"{_BASE}/intern/201003": (200, _VALID_HTML),
    }
    monkeypatch.setattr(
        "app.adapters.jasoseol.AsyncClient",
        lambda **kw: _MockHybridClient(responses),
    )
    seed = SeedKeywords(roles=["백엔드 개발자"])
    result = await JasoseolAdapter().fetch(seed, CollectionOptions(), _COLLECT_DATE)
    assert result.source_stats is not None
    assert result.source_stats.selected == len(result.postings)


# ── KEY REGRESSION: developer postings must survive when sitemap is non-dev ──


async def test_developer_postings_survive_non_dev_sitemap(monkeypatch):
    """
    Regression test: the old sitemap-only behavior would miss developer jobs
    when the sitemap is dominated by non-developer postings.

    EXPLORATION sitemap: nurse, marketing, sales, production (4 non-dev)
    TARGETED search:     backend developer, server engineer, DevOps (3 dev)

    With developer-focused seedKeywords.roles=['백엔드 개발자']:
      - Targeted discovery must supply developer candidates.
      - Final postings must include at least one developer posting.
      - This test must FAIL under old sitemap-only behavior.
    """
    # Build non-developer sitemap
    non_dev_sitemap = _sitemap_xml([
        (f"{_BASE}/recruit/50001", "2026-07-09T10:00:00+09:00"),  # 간호사
        (f"{_BASE}/recruit/50002", "2026-07-09T10:00:00+09:00"),  # 마케팅
        (f"{_BASE}/recruit/50003", "2026-07-09T10:00:00+09:00"),  # 영업
        (f"{_BASE}/recruit/50004", "2026-07-09T10:00:00+09:00"),  # 생산직
    ])

    def _make_non_dev_html(title: str, company: str) -> str:
        return f"""<html><body>
        <img alt="{company} 기업 아이콘" src="/logo.png"/>
        <h2>{title}</h2>
        <p>정규직</p>
        </body></html>"""

    def _make_dev_html(title: str, company: str) -> str:
        return f"""<html><body>
        <img alt="{company} 기업 아이콘" src="/logo.png"/>
        <h2>{title}</h2>
        <p>정규직</p>
        </body></html>"""

    # Search returns developer postings (IDs 60001, 60002, 60003)
    search_html = """<!DOCTYPE html>
<html>
<head>
<script id="__NEXT_DATA__" type="application/json">
{"props":{"pageProps":{"dehydratedState":{"mutations":[],"queries":[
  {"queryKey":["jobSearch",{"page":1,"dutyGroupIds":["176"]}],"state":{"data":{"data":[
    {"id":60001,"name":"라인","title":"서버 백엔드 개발자","recruit_type":0},
    {"id":60002,"name":"토스","title":"백엔드 엔지니어","recruit_type":0},
    {"id":60003,"name":"쿠팡","title":"DevOps 엔지니어","recruit_type":0}
  ]}}}
]}}}}
</script></head><body></body></html>"""

    responses = {
        f"{_BASE}/search?dutyGroupIds=176&page=1": (200, search_html),
        _SITEMAP_1: (200, non_dev_sitemap),
        _SITEMAP_2: (200, _EMPTY_SITEMAP),
        # Non-dev sitemap pages
        f"{_BASE}/recruit/50001": (
            200, _make_non_dev_html("병원 간호사 채용", "서울대병원")),
        f"{_BASE}/recruit/50002": (
            200, _make_non_dev_html("마케팅 매니저", "현대자동차")),
        f"{_BASE}/recruit/50003": (
            200, _make_non_dev_html("영업직 사원", "삼성생명")),
        f"{_BASE}/recruit/50004": (
            200, _make_non_dev_html("생산직 사원", "포스코")),
        # Developer detail pages from targeted search
        f"{_BASE}/recruit/60001": (
            200, _make_dev_html("서버 백엔드 개발자", "라인")),
        f"{_BASE}/recruit/60002": (
            200, _make_dev_html("백엔드 엔지니어", "토스")),
        f"{_BASE}/recruit/60003": (
            200, _make_dev_html("DevOps 엔지니어", "쿠팡")),
    }

    monkeypatch.setattr(
        "app.adapters.jasoseol.AsyncClient",
        lambda **kw: _MockHybridClient(responses),
    )

    seed = SeedKeywords(roles=["백엔드 개발자"])
    options = CollectionOptions(
        detail_fetch_limit_per_source=100,
        max_results_per_source=100,
    )
    result = await JasoseolAdapter().fetch(seed, options, _COLLECT_DATE)

    dev_keywords = {"백엔드", "서버", "devops", "backend"}
    dev_postings = [
        p for p in result.postings
        if any(kw in p.title.lower() for kw in dev_keywords)
    ]
    assert len(dev_postings) >= 1, (
        "Developer postings must appear in result even when sitemap is "
        "dominated by non-developer postings. Old sitemap-only behavior "
        "would fail this test."
    )
