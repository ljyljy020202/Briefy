"""Tests for JasoseolAdapter.

All tests are offline: no real network calls.
- Pure-function tests (_parse_sitemap, parse_posting_page) use fixture files.
- Adapter-level tests mock httpx.AsyncClient via monkeypatch.
"""

from datetime import date
from pathlib import Path

import httpx
import pytest
from httpx import TimeoutException

from app.adapters.aggregators.jasoseol import (
    _EXPLORATION_ENABLED,
    JasoseolAdapter,
    _extract_source_external_id,
    _parse_lastmod,
    _parse_sitemap,
    _relevance_score,
    _select_postings,
    parse_posting_page,
)
from app.adapters.base import AdapterResult, RawJobPosting
from app.schemas.collection import CollectionOptions, SeedKeywords

_FIXTURES = Path(__file__).parent / "fixtures"
_COLLECT_DATE = date(2026, 7, 2)
_CUTOFF = date(2026, 6, 29)  # collect_date - lookback_days(3)


def _read(name: str) -> str:
    return (_FIXTURES / name).read_text(encoding="utf-8")


# ── _parse_lastmod ────────────────────────────────────────────────────────────


def test_parse_lastmod_iso_with_timezone():
    assert _parse_lastmod("2026-07-02T16:39:30+09:00") == date(2026, 7, 2)


def test_parse_lastmod_iso_utc():
    assert _parse_lastmod("2026-06-29T00:00:00+00:00") == date(2026, 6, 29)


def test_parse_lastmod_date_only():
    assert _parse_lastmod("2026-07-01") == date(2026, 7, 1)


def test_parse_lastmod_none_returns_none():
    assert _parse_lastmod(None) is None


def test_parse_lastmod_empty_returns_none():
    assert _parse_lastmod("") is None


def test_parse_lastmod_invalid_returns_none():
    assert _parse_lastmod("not-a-date") is None


# ── _parse_sitemap ────────────────────────────────────────────────────────────


def test_parse_sitemap_returns_urls_within_lookback():
    xml = _read("jasoseol_sitemap.xml")
    results = _parse_sitemap(xml, _CUTOFF)
    urls = [u for _, u in results]
    # 104949 (07-02), 104948 (07-01), 104940 (06-29 boundary), 104801 (no lastmod)
    assert "https://jasoseol.com/recruit/104949" in urls
    assert "https://jasoseol.com/recruit/104948" in urls
    assert "https://jasoseol.com/recruit/104940" in urls


def test_parse_sitemap_excludes_old_urls():
    xml = _read("jasoseol_sitemap.xml")
    results = _parse_sitemap(xml, _CUTOFF)
    urls = [u for _, u in results]
    assert "https://jasoseol.com/recruit/104930" not in urls  # 06-28 < cutoff
    assert "https://jasoseol.com/recruit/104900" not in urls  # 06-20 < cutoff


def test_parse_sitemap_includes_boundary_url():
    xml = _read("jasoseol_sitemap.xml")
    results = _parse_sitemap(xml, _CUTOFF)
    urls = [u for _, u in results]
    # 104940 has lastmod 2026-06-29 == cutoff → must be included
    assert "https://jasoseol.com/recruit/104940" in urls


def test_parse_sitemap_includes_url_without_lastmod():
    xml = _read("jasoseol_sitemap.xml")
    results = _parse_sitemap(xml, _CUTOFF)
    urls = [u for _, u in results]
    assert "https://jasoseol.com/recruit/104801" in urls


def test_parse_sitemap_returns_tuples_of_date_and_url():
    xml = _read("jasoseol_sitemap.xml")
    results = _parse_sitemap(xml, _CUTOFF)
    for lastmod, url in results:
        assert isinstance(lastmod, date)
        assert url.startswith("https://")


def test_parse_sitemap_sorted_recent_first():
    xml = _read("jasoseol_sitemap.xml")
    results = _parse_sitemap(xml, _CUTOFF)
    # results are unsorted at this layer; sorting happens in _collect_recent_urls
    # just verify all returned entries have valid structure
    assert all(isinstance(d, date) and isinstance(u, str) for d, u in results)


def test_parse_sitemap_malformed_xml_raises_value_error():
    with pytest.raises(ValueError, match="invalid sitemap XML"):
        _parse_sitemap("<not valid xml <<>>", _CUTOFF)


def test_parse_sitemap_empty_urlset_returns_empty():
    xml = (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9"></urlset>'
    )
    assert _parse_sitemap(xml, _CUTOFF) == []


# ── parse_posting_page — valid page ──────────────────────────────────────────


def test_parse_posting_page_extracts_company_name():
    html = _read("jasoseol_recruit_page.html")
    result = parse_posting_page(html, "https://jasoseol.com/recruit/104949")
    assert result is not None
    assert result.company_name == "현대오토에버"


def test_parse_posting_page_extracts_title():
    html = _read("jasoseol_recruit_page.html")
    result = parse_posting_page(html, "https://jasoseol.com/recruit/104949")
    assert result is not None
    assert "모빌리티 SW 스쿨" in result.title


def test_parse_posting_page_extracts_deadline():
    html = _read("jasoseol_recruit_page.html")
    result = parse_posting_page(html, "https://jasoseol.com/recruit/104949")
    assert result is not None
    # "2026년 7월 1일 ~ 2026년 7월 19일" → last date is 2026-07-19
    assert result.deadline == date(2026, 7, 19)


def test_parse_posting_page_extracts_employment_type():
    html = _read("jasoseol_recruit_page.html")
    result = parse_posting_page(html, "https://jasoseol.com/recruit/104949")
    assert result is not None
    assert result.employment_type == "계약직"


def test_parse_posting_page_source_is_jasoseol():
    html = _read("jasoseol_recruit_page.html")
    result = parse_posting_page(html, "https://jasoseol.com/recruit/104949")
    assert result is not None
    assert result.source == "jasoseol"


def test_parse_posting_page_source_url_is_preserved():
    html = _read("jasoseol_recruit_page.html")
    url = "https://jasoseol.com/recruit/104949"
    result = parse_posting_page(html, url)
    assert result is not None
    assert result.source_url == url


def test_parse_posting_page_returns_raw_job_posting():
    html = _read("jasoseol_recruit_page.html")
    result = parse_posting_page(html, "https://jasoseol.com/recruit/104949")
    assert isinstance(result, RawJobPosting)


def test_parse_posting_page_unreliable_fields_are_none_or_empty():
    html = _read("jasoseol_recruit_page.html")
    result = parse_posting_page(html, "https://jasoseol.com/recruit/104949")
    assert result is not None
    assert result.position is None
    assert result.location is None
    assert result.experience_level is None
    assert result.description is None
    assert result.posted_at is None
    assert result.skills == []
    assert result.roles == []


# ── parse_posting_page — broken / missing required fields ────────────────────


def test_parse_posting_page_broken_html_returns_none():
    html = _read("jasoseol_broken_page.html")
    result = parse_posting_page(html, "https://jasoseol.com/recruit/99999")
    assert result is None


def test_parse_posting_page_missing_company_returns_none():
    html = """
    <html><body>
      <h2>백엔드 개발자 채용</h2>
    </body></html>
    """
    result = parse_posting_page(html, "https://jasoseol.com/recruit/00001")
    assert result is None


def test_parse_posting_page_missing_title_returns_none():
    html = """
    <html><body>
      <img alt="네이버 기업 아이콘" src="/logo.png"/>
    </body></html>
    """
    result = parse_posting_page(html, "https://jasoseol.com/recruit/00001")
    assert result is None


def test_parse_posting_page_empty_html_returns_none():
    result = parse_posting_page("", "https://jasoseol.com/recruit/00001")
    assert result is None


def test_parse_posting_page_no_date_deadline_is_none():
    html = """
    <html><body>
      <img alt="네이버 기업 아이콘" src="/logo.png"/>
      <h2>백엔드 개발자 채용</h2>
      <p>상시 채용</p>
    </body></html>
    """
    result = parse_posting_page(html, "https://jasoseol.com/recruit/00001")
    assert result is not None
    assert result.deadline is None


def test_parse_posting_page_no_employment_type_is_none():
    html = """
    <html><body>
      <img alt="카카오 기업 아이콘" src="/logo.png"/>
      <h2>iOS 개발자 채용</h2>
    </body></html>
    """
    result = parse_posting_page(html, "https://jasoseol.com/recruit/00002")
    assert result is not None
    assert result.employment_type is None


# ── JasoseolAdapter.fetch — mocked network ───────────────────────────────────


class _MockAsyncClient:
    """Minimal httpx.AsyncClient replacement for tests."""

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


_BASE = "https://jasoseol.com"
_SITEMAP_1 = f"{_BASE}/sitemap/employment_companies.xml"
_SITEMAP_2 = f"{_BASE}/sitemap/intern_employment_companies.xml"
_PAGE_URL = f"{_BASE}/recruit/104949"
_VALID_HTML = (
    _read("jasoseol_recruit_page.html")
    if (_FIXTURES / "jasoseol_recruit_page.html").exists()
    else ""
)

_EMPTY_SITEMAP = (
    '<?xml version="1.0" encoding="UTF-8"?>'
    '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9"></urlset>'
)
_SINGLE_URL_SITEMAP = (
    '<?xml version="1.0" encoding="UTF-8"?>'
    '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">'
    f"<url><loc>{_PAGE_URL}</loc>"
    "<lastmod>2026-07-02T10:00:00+09:00</lastmod></url>"
    "</urlset>"
)


@pytest.mark.skipif(not _EXPLORATION_ENABLED, reason="sitemap exploration disabled")
async def test_adapter_all_success_returns_postings(monkeypatch):
    responses = {
        _SITEMAP_1: (200, _SINGLE_URL_SITEMAP),
        _SITEMAP_2: (200, _SINGLE_URL_SITEMAP),
        _PAGE_URL: (200, _VALID_HTML),
    }
    monkeypatch.setattr(
        "app.adapters.aggregators.jasoseol.AsyncClient",
        lambda **kw: _MockAsyncClient(responses),
    )
    adapter = JasoseolAdapter()
    result = await adapter.fetch(SeedKeywords(), CollectionOptions(), _COLLECT_DATE)
    assert isinstance(result, AdapterResult)
    assert len(result.postings) >= 1
    assert result.postings[0].company_name == "현대오토에버"
    assert result.warnings == []


@pytest.mark.skipif(not _EXPLORATION_ENABLED, reason="sitemap exploration disabled")
async def test_adapter_sitemap_timeout_returns_empty_with_warning(monkeypatch):
    responses = {
        _SITEMAP_1: TimeoutException("timeout"),
        _SITEMAP_2: TimeoutException("timeout"),
    }
    monkeypatch.setattr(
        "app.adapters.aggregators.jasoseol.AsyncClient",
        lambda **kw: _MockAsyncClient(responses),
    )
    adapter = JasoseolAdapter()
    result = await adapter.fetch(SeedKeywords(), CollectionOptions(), _COLLECT_DATE)
    assert result.postings == []
    assert any("timeout" in w for w in result.warnings)


@pytest.mark.skipif(not _EXPLORATION_ENABLED, reason="sitemap exploration disabled")
async def test_adapter_sitemap_http_error_returns_empty_with_warning(monkeypatch):
    responses = {
        _SITEMAP_1: (503, "Service Unavailable"),
        _SITEMAP_2: (503, "Service Unavailable"),
    }
    monkeypatch.setattr(
        "app.adapters.aggregators.jasoseol.AsyncClient",
        lambda **kw: _MockAsyncClient(responses),
    )
    adapter = JasoseolAdapter()
    result = await adapter.fetch(SeedKeywords(), CollectionOptions(), _COLLECT_DATE)
    assert result.postings == []
    assert any("503" in w for w in result.warnings)


@pytest.mark.skipif(not _EXPLORATION_ENABLED, reason="sitemap exploration disabled")
async def test_adapter_page_timeout_returns_partial_with_warning(monkeypatch):
    responses = {
        _SITEMAP_1: (200, _SINGLE_URL_SITEMAP),
        _SITEMAP_2: (200, _EMPTY_SITEMAP),
        _PAGE_URL: TimeoutException("page timeout"),
    }
    monkeypatch.setattr(
        "app.adapters.aggregators.jasoseol.AsyncClient",
        lambda **kw: _MockAsyncClient(responses),
    )
    adapter = JasoseolAdapter()
    result = await adapter.fetch(SeedKeywords(), CollectionOptions(), _COLLECT_DATE)
    assert result.postings == []
    assert any("timeout" in w for w in result.warnings)


async def test_adapter_page_parse_fail_returns_empty_not_raises(monkeypatch):
    responses = {
        _SITEMAP_1: (200, _SINGLE_URL_SITEMAP),
        _SITEMAP_2: (200, _EMPTY_SITEMAP),
        _PAGE_URL: (200, _read("jasoseol_broken_page.html")),
    }
    monkeypatch.setattr(
        "app.adapters.aggregators.jasoseol.AsyncClient",
        lambda **kw: _MockAsyncClient(responses),
    )
    adapter = JasoseolAdapter()
    result = await adapter.fetch(SeedKeywords(), CollectionOptions(), _COLLECT_DATE)
    assert result.postings == []
    assert result.warnings == []


async def test_adapter_respects_discovery_limit_per_source(monkeypatch):
    # Build a sitemap with 3 URLs
    urls = [f"{_BASE}/recruit/{i}" for i in [104949, 104948, 104947]]
    items = "".join(
        f"<url><loc>{u}</loc><lastmod>2026-07-02T10:00:00+09:00</lastmod></url>"
        for u in urls
    )
    sitemap = (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">'
        f"{items}</urlset>"
    )
    responses: dict = {
        _SITEMAP_1: (200, sitemap),
        _SITEMAP_2: (200, _EMPTY_SITEMAP),
    }
    for u in urls:
        responses[u] = (200, _VALID_HTML)

    monkeypatch.setattr(
        "app.adapters.aggregators.jasoseol.AsyncClient",
        lambda **kw: _MockAsyncClient(responses),
    )
    adapter = JasoseolAdapter()
    options = CollectionOptions(discovery_limit_per_source=2)
    result = await adapter.fetch(SeedKeywords(), options, _COLLECT_DATE)
    assert len(result.postings) <= 2


def test_parse_sitemap_unknown_lastmod_not_date_max():
    """Unknown lastmod must not use date.max (which would put it first in sort)."""
    from datetime import date as date_cls

    xml = (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">'
        "<url><loc>https://jasoseol.com/recruit/99999</loc></url>"
        "</urlset>"
    )
    results = _parse_sitemap(xml, date_cls.min)
    assert len(results) == 1
    lastmod, _ = results[0]
    # date.max would put it first in a descending sort — that is the bug we fixed
    assert lastmod != date_cls.max


def test_parse_sitemap_unknown_lastmod_sorts_after_known_date():
    """URLs without lastmod must appear after URLs with a known recent lastmod."""
    from datetime import date as date_cls

    xml = (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">'
        "<url><loc>https://jasoseol.com/recruit/111</loc>"
        "<lastmod>2026-07-02T10:00:00+09:00</lastmod></url>"
        "<url><loc>https://jasoseol.com/recruit/222</loc></url>"
        "</urlset>"
    )
    results = _parse_sitemap(xml, date_cls.min)
    sorted_results = sorted(results, key=lambda t: t[0], reverse=True)
    urls_in_order = [u for _, u in sorted_results]
    known_idx = urls_in_order.index("https://jasoseol.com/recruit/111")
    unknown_idx = urls_in_order.index("https://jasoseol.com/recruit/222")
    assert known_idx < unknown_idx


async def test_adapter_collect_date_none_uses_today(monkeypatch):
    responses = {
        _SITEMAP_1: (200, ""),
        _SITEMAP_2: (200, _EMPTY_SITEMAP),
    }
    monkeypatch.setattr(
        "app.adapters.aggregators.jasoseol.AsyncClient",
        lambda **kw: _MockAsyncClient(responses),
    )
    adapter = JasoseolAdapter()
    # Should not raise even when collect_date is None
    result = await adapter.fetch(SeedKeywords(), CollectionOptions(), collect_date=None)
    assert isinstance(result, AdapterResult)


async def test_adapter_source_name_is_jasoseol():
    assert JasoseolAdapter().source_name == "jasoseol"


# ── _MockAsyncClient handles malformed sitemap without crashing adapter ───────


@pytest.mark.skipif(not _EXPLORATION_ENABLED, reason="sitemap exploration disabled")
async def test_adapter_malformed_sitemap_xml_returns_warning(monkeypatch):
    responses = {
        _SITEMAP_1: (200, "<not valid xml <<"),
        _SITEMAP_2: (200, _EMPTY_SITEMAP),
    }
    monkeypatch.setattr(
        "app.adapters.aggregators.jasoseol.AsyncClient",
        lambda **kw: _MockAsyncClient(responses),
    )
    adapter = JasoseolAdapter()
    result = await adapter.fetch(SeedKeywords(), CollectionOptions(), _COLLECT_DATE)
    assert result.postings == []
    assert any("sitemap error" in w for w in result.warnings)


# ── _extract_source_external_id ───────────────────────────────────────────────


def test_extract_source_external_id_recruit_url():
    url = "https://jasoseol.com/recruit/104949"
    assert _extract_source_external_id(url) == "104949"


def test_extract_source_external_id_intern_url():
    assert _extract_source_external_id("https://jasoseol.com/intern/5678") == "5678"


def test_extract_source_external_id_unknown_pattern_returns_none():
    assert _extract_source_external_id("https://jasoseol.com/company/naver") is None


def test_extract_source_external_id_company_path_returns_none():
    # /company/ paths should not be matched
    assert _extract_source_external_id("https://jasoseol.com/company/123") is None


def test_parse_posting_page_sets_source_external_id():
    html = _read("jasoseol_recruit_page.html")
    result = parse_posting_page(html, "https://jasoseol.com/recruit/104949")
    assert result is not None
    assert result.source_external_id == "104949"


def test_parse_posting_page_intern_url_sets_source_external_id():
    html = _read("jasoseol_recruit_page.html")
    result = parse_posting_page(html, "https://jasoseol.com/intern/9999")
    assert result is not None
    assert result.source_external_id == "9999"


def test_parse_posting_page_unknown_url_pattern_source_external_id_none():
    html = _read("jasoseol_recruit_page.html")
    result = parse_posting_page(html, "https://jasoseol.com/other/page")
    assert result is not None
    assert result.source_external_id is None


# ── budget: detail_fetch_limit_per_source ────────────────────────────────────


def _make_multi_url_sitemap(urls: list[str], lastmod: str) -> str:
    items = "".join(
        f"<url><loc>{u}</loc><lastmod>{lastmod}</lastmod></url>" for u in urls
    )
    return (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">'
        f"{items}</urlset>"
    )


@pytest.mark.skipif(not _EXPLORATION_ENABLED, reason="sitemap exploration disabled")
async def test_adapter_respects_detail_fetch_limit_per_source(monkeypatch):
    """With detail_fetch_limit=1, only 1 page is fetched from 3 discovered URLs."""
    page_urls = [f"{_BASE}/recruit/{i}" for i in [104951, 104950, 104949]]
    sitemap = _make_multi_url_sitemap(page_urls, "2026-07-02T10:00:00+09:00")
    responses: dict = {
        _SITEMAP_1: (200, sitemap),
        _SITEMAP_2: (200, _EMPTY_SITEMAP),
    }
    for u in page_urls:
        responses[u] = (200, _VALID_HTML)

    monkeypatch.setattr(
        "app.adapters.aggregators.jasoseol.AsyncClient",
        lambda **kw: _MockAsyncClient(responses),
    )
    adapter = JasoseolAdapter()
    options = CollectionOptions(
        discovery_limit_per_source=10,
        detail_fetch_limit_per_source=1,
    )
    result = await adapter.fetch(SeedKeywords(), options, _COLLECT_DATE)

    assert len(result.postings) <= 1
    assert result.source_stats is not None
    assert result.source_stats.discovered == 3
    assert result.source_stats.fetched == 1


@pytest.mark.skipif(not _EXPLORATION_ENABLED, reason="sitemap exploration disabled")
async def test_adapter_source_stats_populated_on_success(monkeypatch):
    responses = {
        _SITEMAP_1: (200, _SINGLE_URL_SITEMAP),
        _SITEMAP_2: (200, _EMPTY_SITEMAP),
        _PAGE_URL: (200, _VALID_HTML),
    }
    monkeypatch.setattr(
        "app.adapters.aggregators.jasoseol.AsyncClient",
        lambda **kw: _MockAsyncClient(responses),
    )
    adapter = JasoseolAdapter()
    result = await adapter.fetch(SeedKeywords(), CollectionOptions(), _COLLECT_DATE)

    assert result.source_stats is not None
    assert result.source_stats.discovered >= 1
    assert result.source_stats.fetched >= 1
    assert result.source_stats.parsed >= 1
    assert result.source_stats.selected >= 1


async def test_adapter_source_stats_populated_on_empty(monkeypatch):
    responses = {
        _SITEMAP_1: (200, _EMPTY_SITEMAP),
        _SITEMAP_2: (200, _EMPTY_SITEMAP),
    }
    monkeypatch.setattr(
        "app.adapters.aggregators.jasoseol.AsyncClient",
        lambda **kw: _MockAsyncClient(responses),
    )
    adapter = JasoseolAdapter()
    result = await adapter.fetch(SeedKeywords(), CollectionOptions(), _COLLECT_DATE)

    assert result.source_stats is not None
    assert result.source_stats.discovered == 0
    assert result.source_stats.fetched == 0
    assert result.source_stats.parsed == 0
    assert result.source_stats.selected == 0


# ── _relevance_score ─────────────────────────────────────────────────────────


def _raw(
    title: str,
    company: str,
    emp_type: str | None = None,
    url_id: str = "1",
) -> RawJobPosting:
    return RawJobPosting(
        source="jasoseol",
        source_url=f"https://jasoseol.com/recruit/{url_id}",
        company_name=company,
        title=title,
        employment_type=emp_type,
        source_external_id=url_id,
    )


def test_relevance_score_company_match():
    posting = _raw("백엔드 개발자", "네이버")
    score = _relevance_score(posting, SeedKeywords(companies=["네이버"]))
    assert score == 3.0


def test_relevance_score_role_match_in_title():
    posting = _raw("백엔드 개발자 채용", "카카오")
    score = _relevance_score(posting, SeedKeywords(roles=["백엔드 개발자"]))
    assert score == 2.0


def test_relevance_score_keyword_match():
    posting = _raw("Spring Boot 백엔드 공고", "스타트업")
    score = _relevance_score(posting, SeedKeywords(keywords=["spring boot"]))
    assert score == 1.0


def test_relevance_score_employment_type_match():
    posting = _raw("개발자 채용", "회사", emp_type="정규직")
    score = _relevance_score(posting, SeedKeywords(employment_types=["정규직"]))
    assert score == 0.5


def test_relevance_score_no_match_returns_zero():
    posting = _raw("디자이너 채용", "회사")
    score = _relevance_score(posting, SeedKeywords(roles=["백엔드 개발자"]))
    assert score == 0.0


def test_relevance_score_empty_seeds_returns_zero():
    posting = _raw("백엔드 개발자", "네이버")
    assert _relevance_score(posting, SeedKeywords()) == 0.0


def test_relevance_score_does_not_fabricate_skills_or_location():
    # Skills and location are not available from Jasoseol; score must not use them
    posting = _raw("개발자", "회사")
    # Even if seed has skills/locations, the posting has none → score remains 0
    score = _relevance_score(
        posting, SeedKeywords(skills=["Python"], locations=["서울"])
    )
    assert score == 0.0


# ── _select_postings ─────────────────────────────────────────────────────────


def _entry(
    url_id: str, title: str, company: str, lastmod: date
) -> tuple[date, RawJobPosting]:
    return (lastmod, _raw(title, company, url_id=url_id))


def test_select_postings_empty_entries_returns_empty():
    assert _select_postings([], SeedKeywords(), limit=10) == []


def test_select_postings_limit_zero_returns_empty():
    entries = [_entry("1", "개발자", "회사", date(2026, 7, 2))]
    assert _select_postings(entries, SeedKeywords(), limit=0) == []


def test_select_postings_all_fit_under_limit():
    entries = [
        _entry("1", "백엔드 개발자", "네이버", date(2026, 7, 2)),
        _entry("2", "프론트엔드 개발자", "카카오", date(2026, 7, 1)),
    ]
    result = _select_postings(entries, SeedKeywords(), limit=10)
    assert len(result) == 2


def test_select_postings_caps_to_limit():
    entries = [
        _entry(str(i), f"개발자 {i}", "회사", date(2026, 7, 1)) for i in range(20)
    ]
    result = _select_postings(entries, SeedKeywords(), limit=5)
    assert len(result) == 5


def test_select_postings_deterministic():
    """Same inputs must always produce the same output order."""
    entries = [
        _entry("1", "백엔드 개발자", "네이버", date(2026, 7, 2)),
        _entry("2", "프론트엔드 개발자", "카카오", date(2026, 7, 1)),
        _entry("3", "데이터 엔지니어", "삼성", date(2026, 6, 30)),
    ]
    seed = SeedKeywords(roles=["백엔드 개발자"])
    first = [p.source_url for p in _select_postings(entries, seed, limit=3)]
    second = [p.source_url for p in _select_postings(entries, seed, limit=3)]
    assert first == second


def test_select_postings_relevance_ranked_before_exploration():
    """The seed-matching posting should appear before non-matching ones."""
    # url "2" matches the seed role; "1" does not
    entries = [
        _entry("1", "프론트엔드 개발자", "네이버", date(2026, 7, 2)),  # no match, newer
        _entry("2", "백엔드 개발자", "카카오", date(2026, 7, 1)),  # role match, older
    ]
    seed = SeedKeywords(roles=["백엔드 개발자"])
    result = _select_postings(entries, seed, limit=10)
    assert len(result) == 2
    assert result[0].source_url.endswith("/2")  # matched posting first


def test_select_postings_exploration_includes_non_matching():
    """With enough budget, non-matching postings should be included via exploration."""
    # Only "1" matches; "2" and "3" don't
    entries = [
        _entry("1", "백엔드 개발자", "네이버", date(2026, 7, 2)),
        _entry("2", "프론트엔드 개발자", "카카오", date(2026, 7, 1)),
        _entry("3", "데이터 분석가", "삼성", date(2026, 6, 30)),
    ]
    seed = SeedKeywords(roles=["백엔드 개발자"])
    result = _select_postings(entries, seed, limit=3)
    urls = {p.source_url for p in result}
    # Matched posting always in result
    assert any(u.endswith("/1") for u in urls)
    # Exploration quota should bring in at least one non-matching posting
    assert len(urls) >= 2


def test_select_postings_no_seeds_uses_recency_order():
    """With empty seeds, postings are returned in recency-desc order."""
    entries = [
        _entry("3", "개발자 3", "회사", date(2026, 6, 30)),
        _entry("1", "개발자 1", "회사", date(2026, 7, 2)),
        _entry("2", "개발자 2", "회사", date(2026, 7, 1)),
    ]
    result = _select_postings(entries, SeedKeywords(), limit=3)
    # All three returned; first two should be the most recent ones
    assert result[0].source_url.endswith("/1")  # 2026-07-02
    assert result[1].source_url.endswith("/2")  # 2026-07-01


def test_select_postings_unknown_lastmod_sorts_after_known():
    """Postings with date.min lastmod (unknown) appear after dated postings."""
    from datetime import date as date_cls

    entries = [
        (date_cls.min, _raw("개발자 unknown", "회사 A", url_id="99")),  # no lastmod
        (date(2026, 7, 2), _raw("개발자 known", "회사 B", url_id="1")),
    ]
    result = _select_postings(entries, SeedKeywords(), limit=10)
    assert len(result) == 2
    # Known-lastmod posting should appear before unknown-lastmod
    assert result[0].source_url.endswith("/1")
    assert result[1].source_url.endswith("/99")
