"""Tests for official-source preflight service and API endpoint.

All tests are offline: no real HTTP calls.
Fixture-based; live-network dependency = none.
"""


import httpx

from app.schemas.official_sources import OfficialSourcePreflightResponse
from app.services.official_source_preflight import run_preflight

_BASE = "https://careers.example.com"


# ── shared helpers ────────────────────────────────────────────────────────────


def _sm_xml(entries: list[tuple[str | None, str]]) -> str:
    items = ""
    for lastmod, url in entries:
        lm = f"<lastmod>{lastmod}</lastmod>" if lastmod else ""
        items += f"<url><loc>{url}</loc>{lm}</url>"
    return (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">'
        f"{items}</urlset>"
    )


def _rss_xml(items: list[tuple[str, str]]) -> str:
    item_xml = ""
    for title, link in items:
        item_xml += f"<item><title>{title}</title><link>{link}</link></item>"
    return (
        f'<?xml version="1.0"?><rss version="2.0"><channel>{item_xml}</channel></rss>'
    )


class _MockClient:
    def __init__(self, responses: list):
        self._responses = list(responses)
        self._index = 0

    async def __aenter__(self):
        return self

    async def __aexit__(self, *a):
        pass

    async def get(self, url: str, **kwargs) -> httpx.Response:
        req = httpx.Request("GET", url)
        if self._index >= len(self._responses):
            return httpx.Response(404, text="Not Found", request=req)
        item = self._responses[self._index]
        self._index += 1
        if isinstance(item, BaseException):
            raise item
        status, body = item
        return httpx.Response(status, text=body, request=req)


# ── SITEMAP preflight ─────────────────────────────────────────────────────────


async def test_sitemap_preflight_success(monkeypatch):
    sitemap = _sm_xml([("2026-07-05", f"{_BASE}/jobs/1")])
    page = "<html><body><h1>백엔드 개발자</h1></body></html>"
    mock = _MockClient([(200, sitemap), (200, page)])
    monkeypatch.setattr(
        "app.services.official_source_preflight.AsyncClient", lambda **kw: mock
    )

    result = await run_preflight(
        source_id=1,
        company_id=10,
        company_name="카카오",
        source_type="OFFICIAL_CAREER",
        source_url=f"{_BASE}/sitemap.xml",
        adapter_type="SITEMAP",
        config_json=None,
    )

    assert result.reachable is True
    assert result.adapter_supported is True
    assert result.discovered_count >= 1
    assert result.sample_parsed_count == 1
    assert result.required_fields_valid is True
    assert result.failure_code is None
    assert result.warnings == []


async def test_sitemap_preflight_url_unreachable(monkeypatch):
    mock = _MockClient([(404, "Not Found")])
    monkeypatch.setattr(
        "app.services.official_source_preflight.AsyncClient", lambda **kw: mock
    )

    result = await run_preflight(
        source_id=1,
        company_id=10,
        company_name="카카오",
        source_type="OFFICIAL_CAREER",
        source_url=f"{_BASE}/sitemap.xml",
        adapter_type="SITEMAP",
        config_json=None,
    )

    assert result.reachable is False
    assert result.failure_code == "URL_UNREACHABLE"
    assert "404" in (result.failure_message or "")


async def test_sitemap_preflight_timeout(monkeypatch):
    from httpx import TimeoutException

    mock = _MockClient([TimeoutException("timed out")])
    monkeypatch.setattr(
        "app.services.official_source_preflight.AsyncClient", lambda **kw: mock
    )

    result = await run_preflight(
        source_id=1,
        company_id=10,
        company_name="카카오",
        source_type="OFFICIAL_CAREER",
        source_url=f"{_BASE}/sitemap.xml",
        adapter_type="SITEMAP",
        config_json=None,
    )

    assert result.failure_code == "TIMEOUT"


async def test_sitemap_preflight_no_items_discovered(monkeypatch):
    mock = _MockClient([(200, _sm_xml([]))])
    monkeypatch.setattr(
        "app.services.official_source_preflight.AsyncClient", lambda **kw: mock
    )

    result = await run_preflight(
        source_id=1,
        company_id=10,
        company_name="카카오",
        source_type="OFFICIAL_CAREER",
        source_url=f"{_BASE}/sitemap.xml",
        adapter_type="SITEMAP",
        config_json=None,
    )

    assert result.reachable is True
    assert result.failure_code == "NO_ITEMS_DISCOVERED"


async def test_sitemap_preflight_title_missing(monkeypatch):
    sitemap = _sm_xml([("2026-07-05", f"{_BASE}/jobs/1")])
    page = "<html><body><p>내용만 있고 제목 없음</p></body></html>"
    mock = _MockClient([(200, sitemap), (200, page)])
    monkeypatch.setattr(
        "app.services.official_source_preflight.AsyncClient", lambda **kw: mock
    )

    result = await run_preflight(
        source_id=1,
        company_id=10,
        company_name="카카오",
        source_type="OFFICIAL_CAREER",
        source_url=f"{_BASE}/sitemap.xml",
        adapter_type="SITEMAP",
        config_json=None,
    )

    assert result.reachable is True
    assert result.failure_code == "REQUIRED_FIELD_PARSE_FAILED"


async def test_sitemap_preflight_warning_but_required_fields_valid(monkeypatch):
    """Sample page times out but sitemap itself was reachable with items."""
    from httpx import TimeoutException

    sitemap = _sm_xml([("2026-07-05", f"{_BASE}/jobs/1")])
    mock = _MockClient([(200, sitemap), TimeoutException("sample timeout")])
    monkeypatch.setattr(
        "app.services.official_source_preflight.AsyncClient", lambda **kw: mock
    )

    result = await run_preflight(
        source_id=1,
        company_id=10,
        company_name="카카오",
        source_type="OFFICIAL_CAREER",
        source_url=f"{_BASE}/sitemap.xml",
        adapter_type="SITEMAP",
        config_json=None,
    )

    # sample page timeout → warning but sitemap was reachable & had items
    assert result.reachable is True
    assert result.discovered_count >= 1
    assert any(
        "timed out" in w.lower() or "timeout" in w.lower() for w in result.warnings
    )


async def test_sitemap_preflight_no_source_url():
    result = await run_preflight(
        source_id=1,
        company_id=10,
        company_name="카카오",
        source_type="OFFICIAL_CAREER",
        source_url=None,
        adapter_type="SITEMAP",
        config_json=None,
    )
    assert result.failure_code == "INVALID_CONFIG"


# ── RSS preflight ─────────────────────────────────────────────────────────────


async def test_rss_preflight_success(monkeypatch):
    feed = _rss_xml([("백엔드 개발자", f"{_BASE}/jobs/1")])
    mock = _MockClient([(200, feed)])
    monkeypatch.setattr(
        "app.services.official_source_preflight.AsyncClient", lambda **kw: mock
    )

    result = await run_preflight(
        source_id=2,
        company_id=20,
        company_name="네이버",
        source_type="OFFICIAL_CAREER",
        source_url=f"{_BASE}/feed.xml",
        adapter_type="RSS",
        config_json=None,
    )

    assert result.reachable is True
    assert result.adapter_supported is True
    assert result.discovered_count >= 1
    assert result.required_fields_valid is True
    assert result.failure_code is None


async def test_rss_preflight_url_unreachable(monkeypatch):
    mock = _MockClient([(500, "Server Error")])
    monkeypatch.setattr(
        "app.services.official_source_preflight.AsyncClient", lambda **kw: mock
    )

    result = await run_preflight(
        source_id=2,
        company_id=20,
        company_name="네이버",
        source_type="OFFICIAL_CAREER",
        source_url=f"{_BASE}/feed.xml",
        adapter_type="RSS",
        config_json=None,
    )

    assert result.failure_code == "URL_UNREACHABLE"


async def test_rss_preflight_no_items(monkeypatch):
    empty_feed = '<?xml version="1.0"?><rss version="2.0"><channel></channel></rss>'
    mock = _MockClient([(200, empty_feed)])
    monkeypatch.setattr(
        "app.services.official_source_preflight.AsyncClient", lambda **kw: mock
    )

    result = await run_preflight(
        source_id=2,
        company_id=20,
        company_name="네이버",
        source_type="OFFICIAL_CAREER",
        source_url=f"{_BASE}/feed.xml",
        adapter_type="RSS",
        config_json=None,
    )

    assert result.reachable is True
    assert result.failure_code == "NO_ITEMS_DISCOVERED"


# ── CUSTOM preflight ──────────────────────────────────────────────────────────


async def test_custom_preflight_missing_parser_key():
    result = await run_preflight(
        source_id=3,
        company_id=30,
        company_name="토스",
        source_type="OFFICIAL_CAREER",
        source_url="https://toss.im/careers",
        adapter_type="CUSTOM",
        config_json=None,
    )
    assert result.failure_code == "MISSING_PARSER_KEY"


async def test_custom_preflight_unknown_parser():
    result = await run_preflight(
        source_id=3,
        company_id=30,
        company_name="토스",
        source_type="OFFICIAL_CAREER",
        source_url="https://toss.im/careers",
        adapter_type="CUSTOM",
        config_json='{"parser_key": "UNKNOWN_XYZ"}',
    )
    assert result.adapter_supported is True
    assert result.parser_registered is False
    assert result.failure_code == "UNKNOWN_PARSER"


async def test_custom_preflight_greeting_success(monkeypatch):
    """GREETING parser reuses greeting_preflight(); mock it for isolation."""
    monkeypatch.setattr(
        "app.services.official_source_preflight._greeting_preflight",
        lambda *a, **kw: _async_return(
            OfficialSourcePreflightResponse(
                reachable=True,
                adapter_supported=True,
                parser_registered=True,
                discovered_count=5,
                sample_parsed_count=1,
                required_fields_valid=True,
            )
        ),
    )

    result = await run_preflight(
        source_id=4,
        company_id=40,
        company_name="Greeting Inc",
        source_type="OFFICIAL_CAREER",
        source_url="https://careers.greeting.works/companies/test",
        adapter_type="CUSTOM",
        config_json='{"parser_key": "GREETING"}',
    )

    assert result.reachable is True
    assert result.parser_registered is True
    assert result.failure_code is None


async def test_custom_preflight_invalid_config_json():
    result = await run_preflight(
        source_id=3,
        company_id=30,
        company_name="토스",
        source_type="OFFICIAL_CAREER",
        source_url="https://toss.im/careers",
        adapter_type="CUSTOM",
        config_json="{{not json",
    )
    assert result.failure_code == "INVALID_CONFIG"


# ── unsupported adapter ───────────────────────────────────────────────────────


async def test_unsupported_adapter():
    result = await run_preflight(
        source_id=9,
        company_id=90,
        company_name="Test",
        source_type="OFFICIAL_CAREER",
        source_url="https://example.com",
        adapter_type="SCRAPER",
        config_json=None,
    )
    assert result.adapter_supported is False
    assert result.failure_code == "UNSUPPORTED_ADAPTER"


# ── no DB write guarantee ─────────────────────────────────────────────────────


async def test_preflight_does_not_import_db_session():
    """Preflight service must not import any DB session / repository."""
    import inspect

    import app.services.official_source_preflight as mod

    src = inspect.getsource(mod)
    assert "Session" not in src
    assert "repository" not in src.lower()
    assert "EntityManager" not in src


# ── API endpoint (integration with ASGI) ─────────────────────────────────────


async def test_preflight_endpoint_sitemap_success(client, monkeypatch):
    from app.schemas.official_sources import OfficialSourcePreflightResponse as R

    monkeypatch.setattr(
        "app.api.official_sources.run_preflight",
        lambda **kw: _async_return(
            R(
                reachable=True,
                adapter_supported=True,
                discovered_count=3,
                sample_parsed_count=1,
                required_fields_valid=True,
            )
        ),
    )

    resp = await client.post(
        "/official-sources/preflight",
        json={
            "sourceId": 1,
            "companyId": 10,
            "companyName": "카카오",
            "sourceType": "OFFICIAL_CAREER",
            "sourceUrl": "https://careers.kakao.com/sitemap.xml",
            "adapterType": "SITEMAP",
            "configJson": None,
        },
    )

    assert resp.status_code == 200
    body = resp.json()
    assert body["reachable"] is True
    assert body["adapterSupported"] is True
    assert body["discoveredCount"] == 3
    assert body["failureCode"] is None


async def test_preflight_endpoint_failure(client, monkeypatch):
    from app.schemas.official_sources import OfficialSourcePreflightResponse as R

    monkeypatch.setattr(
        "app.api.official_sources.run_preflight",
        lambda **kw: _async_return(
            R(
                adapter_supported=True,
                failure_code="URL_UNREACHABLE",
                failure_message="HTTP 404",
            )
        ),
    )

    resp = await client.post(
        "/official-sources/preflight",
        json={
            "sourceId": 2,
            "companyId": 20,
            "companyName": "네이버",
            "sourceType": "OFFICIAL_CAREER",
            "sourceUrl": "https://example.com/gone",
            "adapterType": "SITEMAP",
            "configJson": None,
        },
    )

    assert resp.status_code == 200
    body = resp.json()
    assert body["reachable"] is False
    assert body["failureCode"] == "URL_UNREACHABLE"


# ── helpers ───────────────────────────────────────────────────────────────────


async def _async_return(value):
    return value
