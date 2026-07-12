"""Tests for jasoseol_search — public /search HTML parsing and discovery.

All tests are offline.  Network calls are mocked via monkeypatch.
"""

from pathlib import Path

import httpx
from httpx import TimeoutException

from app.adapters.jasoseol_search import (
    SearchCandidate,
    discover_from_search,
    parse_search_html,
)

_FIXTURES = Path(__file__).parent / "fixtures"


def _read(name: str) -> str:
    return (_FIXTURES / name).read_text(encoding="utf-8")


# ── parse_search_html ─────────────────────────────────────────────────────────


def test_parse_search_html_extracts_recruit_links():
    html = _read("jasoseol_search_page.html")
    result = parse_search_html(html)
    urls = [c.url for c in result]
    assert any("/recruit/201001" in u for u in urls)
    assert any("/recruit/201002" in u for u in urls)


def test_parse_search_html_extracts_intern_links():
    html = _read("jasoseol_search_page.html")
    result = parse_search_html(html)
    urls = [c.url for c in result]
    assert any("/intern/201003" in u for u in urls)


def test_parse_search_html_ignores_company_links():
    html = _read("jasoseol_search_page.html")
    result = parse_search_html(html)
    urls = [c.url for c in result]
    assert not any("/company/" in u for u in urls)


def test_parse_search_html_deduplicates_same_id():
    """ID 201001 appears twice in the fixture; must be returned once."""
    html = _read("jasoseol_search_page.html")
    result = parse_search_html(html)
    ids = [c.source_external_id for c in result]
    assert ids.count("201001") == 1


def test_parse_search_html_source_external_id_extracted():
    html = _read("jasoseol_search_page.html")
    result = parse_search_html(html)
    for c in result:
        assert c.source_external_id is not None
        assert c.source_external_id.isdigit()


def test_parse_search_html_empty_page_returns_empty():
    html = "<html><body><p>검색 결과가 없습니다.</p></body></html>"
    assert parse_search_html(html) == []


def test_parse_search_html_malformed_next_data_falls_back_to_regex():
    html = """
    <html>
    <head>
      <script id="__NEXT_DATA__" type="application/json">{invalid json}</script>
    </head>
    <body>
      <a href="/recruit/999001">백엔드 개발자</a>
      <a href="/intern/999002">iOS 개발자</a>
    </body>
    </html>
    """
    result = parse_search_html(html)
    ids = {c.source_external_id for c in result}
    assert "999001" in ids
    assert "999002" in ids


def test_parse_search_html_no_next_data_falls_back_to_regex():
    html = """
    <html><body>
      <a href="/recruit/888001">서버 개발자</a>
    </body></html>
    """
    result = parse_search_html(html)
    assert len(result) == 1
    assert result[0].source_external_id == "888001"
    assert "/recruit/888001" in result[0].url


def test_parse_search_html_result_type_is_search_candidate():
    html = _read("jasoseol_search_page.html")
    for c in parse_search_html(html):
        assert isinstance(c, SearchCandidate)


# ── discover_from_search ──────────────────────────────────────────────────────


class _MockSearchClient:
    """Minimal httpx.AsyncClient replacement for search tests."""

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
            return httpx.Response(200, text="<html></html>", request=request)
        if isinstance(response, BaseException):
            raise response
        status, body = response
        return httpx.Response(status, text=body, request=request)


_BASE = "https://jasoseol.com"


async def test_discover_returns_candidates_from_single_page():
    html = _read("jasoseol_search_page.html")
    page_url = f"{_BASE}/search?dutyGroupIds=175,176&page=1"
    client = _MockSearchClient({page_url: (200, html)})
    result = await discover_from_search(
        client, _BASE, [175, 176], limit=10, warnings=[]
    )
    assert len(result) >= 1
    ids = {c.source_external_id for c in result}
    assert "201001" in ids


async def test_discover_respects_limit():
    html = _read("jasoseol_search_page.html")
    html_p2 = _read("jasoseol_search_page_p2.html")
    responses = {
        f"{_BASE}/search?dutyGroupIds=175,176&page=1": (200, html),
        f"{_BASE}/search?dutyGroupIds=175,176&page=2": (200, html_p2),
    }
    client = _MockSearchClient(responses)
    result = await discover_from_search(client, _BASE, [175, 176], limit=2, warnings=[])
    assert len(result) <= 2


async def test_discover_paginates_for_more_results():
    p1_html = _read("jasoseol_search_page.html")
    p2_html = _read("jasoseol_search_page_p2.html")
    responses = {
        f"{_BASE}/search?dutyGroupIds=175,176&page=1": (200, p1_html),
        f"{_BASE}/search?dutyGroupIds=175,176&page=2": (200, p2_html),
    }
    client = _MockSearchClient(responses)
    result = await discover_from_search(
        client, _BASE, [175, 176], limit=100, warnings=[]
    )
    ids = {c.source_external_id for c in result}
    assert "201001" in ids
    assert "202001" in ids


async def test_discover_timeout_adds_warning_returns_partial():
    class _TimeoutClient:
        async def get(self, url: str):
            raise TimeoutException("timeout")

    warnings: list[str] = []
    result = await discover_from_search(
        _TimeoutClient(), _BASE, [176], limit=10, warnings=warnings
    )
    assert result == []
    assert any("timeout" in w for w in warnings)


async def test_discover_http_error_adds_warning():
    class _ErrorClient:
        async def get(self, url: str):
            request = httpx.Request("GET", url)
            response = httpx.Response(503, text="Service Unavailable", request=request)
            raise httpx.HTTPStatusError(
                message="503", request=request, response=response
            )

    warnings: list[str] = []
    result = await discover_from_search(
        _ErrorClient(), _BASE, [176], limit=10, warnings=warnings
    )
    assert result == []
    assert any("503" in w for w in warnings)


async def test_discover_empty_duty_ids_returns_empty():
    class _NeverCalledClient:
        async def get(self, url: str):
            raise AssertionError("should not be called")

    result = await discover_from_search(
        _NeverCalledClient(), _BASE, [], limit=10, warnings=[]
    )
    assert result == []


async def test_discover_deduplicates_across_pages():
    """If page 2 repeats IDs from page 1, deduplicate them."""
    p1_html = _read("jasoseol_search_page.html")
    responses = {
        f"{_BASE}/search?dutyGroupIds=175,176&page=1": (200, p1_html),
        f"{_BASE}/search?dutyGroupIds=175,176&page=2": (200, p1_html),  # same content
    }
    client = _MockSearchClient(responses)
    result = await discover_from_search(
        client, _BASE, [175, 176], limit=100, warnings=[]
    )
    ids = [c.source_external_id for c in result]
    assert len(ids) == len(set(ids))  # no duplicates
