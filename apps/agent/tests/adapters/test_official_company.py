"""Tests for OfficialCompanyAdapter.

All tests are offline: no real HTTP calls.
- Pure-function tests: parse_sitemap_xml, parse_page_generic, parse_rss_feed
- Async adapter tests: mock httpx.AsyncClient via monkeypatch
- Uses fake CompanySource/CompanyProfile definitions
"""

import json
from datetime import date

import httpx
import pytest
from httpx import TimeoutException

from app.adapters.base import AdapterResult, RawJobPosting
from app.adapters.official_company import (
    _CUSTOM_REGISTRY,
    _CUSTOM_REGISTRY_BY_KEY,
    CustomParser,
    OfficialCompanyAdapter,
    _extract_parser_key,
    _parse_rss_config,
    _parse_sitemap_config,
    _source_id,
    parse_page_generic,
    parse_rss_feed,
    parse_sitemap_xml,
)
from app.schemas.collection import (
    CollectionOptions,
    CompanyProfile,
    OfficialCompanySource,
    SeedKeywords,
)

_COLLECT_DATE = date(2026, 7, 6)
_CUTOFF = date(2026, 7, 3)  # collect_date - lookback_days(3)
_BASE = "https://careers.example.com"


# ── helpers ───────────────────────────────────────────────────────────────────


def _profile(company_id: int = 1, name: str = "카카오") -> CompanyProfile:
    return CompanyProfile(
        id=company_id,
        canonical_name=name,
        normalized_name=name.lower(),
    )


def _source(
    company_id: int = 1,
    adapter_type: str = "SITEMAP",
    source_url: str = f"{_BASE}/sitemap.xml",
    config_json: str | None = None,
) -> OfficialCompanySource:
    return OfficialCompanySource(
        company_id=company_id,
        source_type="CAREERS_PAGE",
        source_url=source_url,
        adapter_type=adapter_type,
        config_json=config_json,
    )


def _options(**kw) -> CollectionOptions:
    return CollectionOptions(**kw)


def _sm_xml(entries: list[tuple[str | None, str]]) -> str:
    """Build a minimal sitemap XML from (lastmod, url) pairs."""
    items = ""
    for lastmod, url in entries:
        lm = f"<lastmod>{lastmod}</lastmod>" if lastmod else ""
        items += f"<url><loc>{url}</loc>{lm}</url>"
    return (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">'
        f"{items}</urlset>"
    )


def _rss_xml(items: list[tuple[str, str, str | None]]) -> str:
    """Build RSS 2.0 from (title, link, pubDate) tuples."""
    item_xml = ""
    for title, link, pub in items:
        pub_tag = f"<pubDate>{pub}</pubDate>" if pub else ""
        item_xml += (
            f"<item><title>{title}</title><link>{link}</link>{pub_tag}</item>"
        )
    return (
        f'<?xml version="1.0"?>'
        f'<rss version="2.0"><channel>{item_xml}</channel></rss>'
    )


def _atom_xml(entries: list[tuple[str, str, str | None]]) -> str:
    """Build Atom 1.0 from (title, href, published) tuples."""
    entry_xml = ""
    for title, href, pub in entries:
        pub_tag = f"<published>{pub}</published>" if pub else ""
        entry_xml += (
            f'<entry><title>{title}</title>'
            f'<link href="{href}"/>{pub_tag}</entry>'
        )
    return (
        '<?xml version="1.0"?>'
        '<feed xmlns="http://www.w3.org/2005/Atom">'
        f"{entry_xml}</feed>"
    )


class _MockClient:
    """Sequential-response mock for httpx.AsyncClient."""

    def __init__(self, responses: list):
        self._responses = list(responses)
        self._index = 0
        self.calls: list[str] = []

    async def __aenter__(self):
        return self

    async def __aexit__(self, *a):
        pass

    async def get(self, url: str, **kwargs) -> httpx.Response:
        self.calls.append(url)
        request = httpx.Request("GET", url)
        if self._index >= len(self._responses):
            return httpx.Response(404, text="Not Found", request=request)
        resp = self._responses[self._index]
        self._index += 1
        if isinstance(resp, BaseException):
            raise resp
        status, body = resp
        return httpx.Response(status, text=body, request=request)


# ── parse_sitemap_xml ─────────────────────────────────────────────────────────


def test_parse_sitemap_xml_returns_entries_within_cutoff():
    xml = _sm_xml(
        [
            ("2026-07-05", f"{_BASE}/jobs/1"),
            ("2026-07-03", f"{_BASE}/jobs/2"),
            ("2026-07-01", f"{_BASE}/jobs/3"),  # before cutoff
        ]
    )
    entries = parse_sitemap_xml(xml, _CUTOFF)
    urls = [u for _, u in entries]
    assert f"{_BASE}/jobs/1" in urls
    assert f"{_BASE}/jobs/2" in urls
    assert f"{_BASE}/jobs/3" not in urls


def test_parse_sitemap_xml_unknown_lastmod_included():
    xml = _sm_xml([(None, f"{_BASE}/jobs/unknown")])
    entries = parse_sitemap_xml(xml, _CUTOFF)
    assert any(u == f"{_BASE}/jobs/unknown" for _, u in entries)


def test_parse_sitemap_xml_unknown_lastmod_gets_date_min():
    xml = _sm_xml([(None, f"{_BASE}/jobs/x")])
    entries = parse_sitemap_xml(xml, _CUTOFF)
    d, _ = entries[0]
    assert d == date.min


def test_parse_sitemap_xml_empty_returns_empty():
    xml = _sm_xml([])
    assert parse_sitemap_xml(xml, _CUTOFF) == []


def test_parse_sitemap_xml_invalid_raises():
    with pytest.raises(ValueError, match="invalid sitemap"):
        parse_sitemap_xml("not-xml", _CUTOFF)


# ── parse_page_generic ────────────────────────────────────────────────────────


def test_parse_page_generic_title_from_h1():
    html = "<html><body><h1>백엔드 개발자 채용</h1></body></html>"
    result = parse_page_generic(html, f"{_BASE}/jobs/1", "카카오", "company_1_sitemap")
    assert result is not None
    assert result.title == "백엔드 개발자 채용"
    assert result.company_name == "카카오"
    assert result.source == "company_1_sitemap"
    assert result.source_url == f"{_BASE}/jobs/1"


def test_parse_page_generic_fallback_to_title_tag():
    html = "<html><head><title>iOS 개발자 | 채용</title></head><body></body></html>"
    result = parse_page_generic(html, f"{_BASE}/jobs/2", "네이버", "company_1_sitemap")
    assert result is not None
    assert result.title == "iOS 개발자"


def test_parse_page_generic_strips_title_suffix():
    for suffix in [" | 채용", " - 채용", " | Jobs", " | Careers"]:
        html = f"<html><head><title>Frontend{suffix}</title></head></html>"
        result = parse_page_generic(html, f"{_BASE}/j", "Co", "src")
        assert result is not None
        assert result.title == "Frontend"


def test_parse_page_generic_extracts_korean_deadline():
    html = (
        "<html><body><h1>직무</h1>"
        "<p>마감일: 2026년 8월 15일</p>"
        "</body></html>"
    )
    result = parse_page_generic(html, f"{_BASE}/j", "Co", "src")
    assert result is not None
    assert result.deadline == date(2026, 8, 15)


def test_parse_page_generic_extracts_employment_type():
    html = "<html><body><h1>채용</h1><p>정규직 공채</p></body></html>"
    result = parse_page_generic(html, f"{_BASE}/j", "Co", "src")
    assert result is not None
    assert result.employment_type == "정규직"


def test_parse_page_generic_no_title_returns_none():
    html = "<html><body><p>내용</p></body></html>"
    result = parse_page_generic(html, f"{_BASE}/j", "Co", "src")
    assert result is None


def test_parse_page_generic_skills_and_roles_empty():
    html = "<html><body><h1>개발자</h1></body></html>"
    result = parse_page_generic(html, f"{_BASE}/j", "Co", "src")
    assert result is not None
    assert result.skills == []
    assert result.roles == []


# ── parse_rss_feed ────────────────────────────────────────────────────────────


def test_parse_rss_feed_rss2_success():
    xml = _rss_xml(
        [
            ("백엔드 개발자", f"{_BASE}/jobs/1", "Mon, 01 Jul 2026 09:00:00 +0900"),
            ("프론트엔드 개발자", f"{_BASE}/jobs/2", None),
        ]
    )
    postings = parse_rss_feed(xml, "카카오", "company_1_rss")
    assert len(postings) == 2
    assert postings[0].title == "백엔드 개발자"
    assert postings[0].source_url == f"{_BASE}/jobs/1"
    assert postings[0].company_name == "카카오"
    assert postings[0].source == "company_1_rss"
    assert postings[0].posted_at is not None
    assert postings[1].posted_at is None


def test_parse_rss_feed_atom_success():
    xml = _atom_xml(
        [
            ("백엔드 개발자", f"{_BASE}/jobs/10", "2026-07-01T09:00:00+09:00"),
        ]
    )
    postings = parse_rss_feed(xml, "네이버", "company_2_rss")
    assert len(postings) == 1
    assert postings[0].title == "백엔드 개발자"
    assert postings[0].source_url == f"{_BASE}/jobs/10"
    assert postings[0].posted_at is not None


def test_parse_rss_feed_max_items_respected():
    xml = _rss_xml(
        [(f"직무{i}", f"{_BASE}/jobs/{i}", None) for i in range(10)]
    )
    postings = parse_rss_feed(xml, "Co", "src", max_items=3)
    assert len(postings) == 3


def test_parse_rss_feed_empty_channel_returns_empty():
    xml = '<?xml version="1.0"?><rss version="2.0"><channel></channel></rss>'
    assert parse_rss_feed(xml, "Co", "src") == []


def test_parse_rss_feed_item_missing_title_skipped():
    xml = (
        '<?xml version="1.0"?><rss version="2.0"><channel>'
        f"<item><link>{_BASE}/jobs/1</link></item>"
        "</channel></rss>"
    )
    assert parse_rss_feed(xml, "Co", "src") == []


def test_parse_rss_feed_item_missing_link_skipped():
    xml = (
        '<?xml version="1.0"?><rss version="2.0"><channel>'
        "<item><title>직무</title></item>"
        "</channel></rss>"
    )
    assert parse_rss_feed(xml, "Co", "src") == []


def test_parse_rss_feed_invalid_xml_raises():
    with pytest.raises(ValueError, match="invalid RSS"):
        parse_rss_feed("not-xml{", "Co", "src")


# ── config helpers ────────────────────────────────────────────────────────────


def test_parse_sitemap_config_defaults():
    cfg = _parse_sitemap_config(None)
    assert cfg.max_discover == 50
    assert cfg.max_fetch == 20


def test_parse_sitemap_config_custom_values():
    cfg = _parse_sitemap_config(json.dumps({"max_fetch": 5, "max_discover": 10}))
    assert cfg.max_fetch == 5
    assert cfg.max_discover == 10


def test_parse_rss_config_defaults():
    assert _parse_rss_config(None).max_items == 50


def test_source_id_format():
    s = _source(company_id=42, adapter_type="SITEMAP")
    assert _source_id(s) == "company_42_sitemap"


def test_source_id_rss():
    s = _source(company_id=7, adapter_type="RSS")
    assert _source_id(s) == "company_7_rss"


# ── OfficialCompanyAdapter — SITEMAP ─────────────────────────────────────────


async def test_adapter_sitemap_success(monkeypatch):
    sitemap = _sm_xml([("2026-07-05", f"{_BASE}/jobs/1")])
    page_html = "<html><body><h1>백엔드 개발자</h1></body></html>"
    mock = _MockClient([(200, sitemap), (200, page_html)])
    monkeypatch.setattr(
        "app.adapters.official_company.AsyncClient", lambda **kw: mock
    )

    adapter = OfficialCompanyAdapter(
        [_source(adapter_type="SITEMAP")], [_profile()]
    )
    result = await adapter.fetch(SeedKeywords(), _options(), _COLLECT_DATE)

    assert isinstance(result, AdapterResult)
    assert len(result.postings) == 1
    assert result.postings[0].company_name == "카카오"
    assert result.postings[0].title == "백엔드 개발자"
    assert result.postings[0].source == "company_1_sitemap"
    assert result.warnings == []


async def test_adapter_sitemap_no_source_url_warns():
    s = OfficialCompanySource(
        company_id=1, source_type="CAREERS_PAGE", adapter_type="SITEMAP"
    )
    adapter = OfficialCompanyAdapter([s], [_profile()])
    result = await adapter.fetch(SeedKeywords(), _options(), _COLLECT_DATE)
    assert result.postings == []
    assert any("no source_url" in w for w in result.warnings)


async def test_adapter_sitemap_http_error_warns(monkeypatch):
    mock = _MockClient([(404, "Not Found")])
    monkeypatch.setattr(
        "app.adapters.official_company.AsyncClient", lambda **kw: mock
    )
    adapter = OfficialCompanyAdapter(
        [_source(adapter_type="SITEMAP")], [_profile()]
    )
    result = await adapter.fetch(SeedKeywords(), _options(), _COLLECT_DATE)
    assert result.postings == []
    assert any("HTTP 404" in w for w in result.warnings)


async def test_adapter_sitemap_timeout_warns(monkeypatch):
    mock = _MockClient([TimeoutException("timed out")])
    monkeypatch.setattr(
        "app.adapters.official_company.AsyncClient", lambda **kw: mock
    )
    adapter = OfficialCompanyAdapter(
        [_source(adapter_type="SITEMAP")], [_profile()]
    )
    result = await adapter.fetch(SeedKeywords(), _options(), _COLLECT_DATE)
    assert result.postings == []
    assert any("timeout" in w for w in result.warnings)


async def test_adapter_sitemap_empty_sitemap_returns_empty(monkeypatch):
    mock = _MockClient([(200, _sm_xml([]))])
    monkeypatch.setattr(
        "app.adapters.official_company.AsyncClient", lambda **kw: mock
    )
    adapter = OfficialCompanyAdapter(
        [_source(adapter_type="SITEMAP")], [_profile()]
    )
    result = await adapter.fetch(SeedKeywords(), _options(), _COLLECT_DATE)
    assert result.postings == []
    assert result.warnings == []


async def test_adapter_sitemap_uses_canonical_name_from_profile(monkeypatch):
    sitemap = _sm_xml([("2026-07-05", f"{_BASE}/jobs/1")])
    page_html = "<html><body><h1>개발자</h1></body></html>"
    mock = _MockClient([(200, sitemap), (200, page_html)])
    monkeypatch.setattr(
        "app.adapters.official_company.AsyncClient", lambda **kw: mock
    )
    profile = _profile(name="Kakao Corp")
    adapter = OfficialCompanyAdapter(
        [_source(adapter_type="SITEMAP")], [profile]
    )
    result = await adapter.fetch(SeedKeywords(), _options(), _COLLECT_DATE)
    assert result.postings[0].company_name == "Kakao Corp"


async def test_adapter_sitemap_unknown_profile_uses_fallback(monkeypatch):
    sitemap = _sm_xml([("2026-07-05", f"{_BASE}/jobs/1")])
    page_html = "<html><body><h1>개발자</h1></body></html>"
    mock = _MockClient([(200, sitemap), (200, page_html)])
    monkeypatch.setattr(
        "app.adapters.official_company.AsyncClient", lambda **kw: mock
    )
    adapter = OfficialCompanyAdapter(
        [_source(company_id=99, adapter_type="SITEMAP")], []
    )
    result = await adapter.fetch(SeedKeywords(), _options(), _COLLECT_DATE)
    assert result.postings[0].company_name == "company_99"


# ── OfficialCompanyAdapter — RSS ──────────────────────────────────────────────


async def test_adapter_rss_success(monkeypatch):
    feed = _rss_xml(
        [("백엔드 개발자", f"{_BASE}/jobs/1", "Mon, 01 Jul 2026 09:00:00 +0900")]
    )
    mock = _MockClient([(200, feed)])
    monkeypatch.setattr(
        "app.adapters.official_company.AsyncClient", lambda **kw: mock
    )
    adapter = OfficialCompanyAdapter(
        [_source(adapter_type="RSS", source_url=f"{_BASE}/feed.xml")],
        [_profile()],
    )
    result = await adapter.fetch(SeedKeywords(), _options(), _COLLECT_DATE)
    assert len(result.postings) == 1
    assert result.postings[0].title == "백엔드 개발자"
    assert result.postings[0].source == "company_1_rss"
    assert result.warnings == []


async def test_adapter_rss_no_source_url_warns():
    s = OfficialCompanySource(
        company_id=1, source_type="FEED", adapter_type="RSS"
    )
    adapter = OfficialCompanyAdapter([s], [_profile()])
    result = await adapter.fetch(SeedKeywords(), _options(), _COLLECT_DATE)
    assert result.postings == []
    assert any("no source_url" in w for w in result.warnings)


async def test_adapter_rss_http_error_warns(monkeypatch):
    mock = _MockClient([(500, "Server Error")])
    monkeypatch.setattr(
        "app.adapters.official_company.AsyncClient", lambda **kw: mock
    )
    adapter = OfficialCompanyAdapter(
        [_source(adapter_type="RSS", source_url=f"{_BASE}/feed.xml")],
        [_profile()],
    )
    result = await adapter.fetch(SeedKeywords(), _options(), _COLLECT_DATE)
    assert result.postings == []
    assert any("HTTP 500" in w for w in result.warnings)


async def test_adapter_rss_timeout_warns(monkeypatch):
    mock = _MockClient([TimeoutException("timed out")])
    monkeypatch.setattr(
        "app.adapters.official_company.AsyncClient", lambda **kw: mock
    )
    adapter = OfficialCompanyAdapter(
        [_source(adapter_type="RSS", source_url=f"{_BASE}/feed.xml")],
        [_profile()],
    )
    result = await adapter.fetch(SeedKeywords(), _options(), _COLLECT_DATE)
    assert result.postings == []
    assert any("timeout" in w for w in result.warnings)


async def test_adapter_rss_atom_feed(monkeypatch):
    feed = _atom_xml([("프론트엔드 개발자", f"{_BASE}/jobs/5", None)])
    mock = _MockClient([(200, feed)])
    monkeypatch.setattr(
        "app.adapters.official_company.AsyncClient", lambda **kw: mock
    )
    adapter = OfficialCompanyAdapter(
        [_source(adapter_type="RSS", source_url=f"{_BASE}/atom.xml")],
        [_profile()],
    )
    result = await adapter.fetch(SeedKeywords(), _options(), _COLLECT_DATE)
    assert len(result.postings) == 1
    assert result.postings[0].title == "프론트엔드 개발자"


# ── _extract_parser_key unit tests ───────────────────────────────────────────


def test_extract_parser_key_none_config_returns_none_no_error():
    key, err = _extract_parser_key(None)
    assert key is None
    assert err is None


def test_extract_parser_key_empty_string_returns_none_no_error():
    key, err = _extract_parser_key("")
    assert key is None
    assert err is None


def test_extract_parser_key_whitespace_only_returns_none_no_error():
    key, err = _extract_parser_key("   ")
    assert key is None
    assert err is None


def test_extract_parser_key_invalid_json_returns_error():
    key, err = _extract_parser_key("not-valid-json{")
    assert key is None
    assert err == "invalid_json"


def test_extract_parser_key_missing_field_returns_error():
    key, err = _extract_parser_key('{"max_fetch": 20}')
    assert key is None
    assert err == "missing_key"


def test_extract_parser_key_empty_value_returns_error():
    key, err = _extract_parser_key('{"parser_key": ""}')
    assert key is None
    assert err == "empty_key"


def test_extract_parser_key_whitespace_value_returns_error():
    key, err = _extract_parser_key('{"parser_key": "   "}')
    assert key is None
    assert err == "empty_key"


def test_extract_parser_key_normalizes_lowercase_to_uppercase():
    key, err = _extract_parser_key('{"parser_key": "greeting"}')
    assert key == "GREETING"
    assert err is None


def test_extract_parser_key_strips_whitespace():
    key, err = _extract_parser_key('{"parser_key": "  GREETING  "}')
    assert key == "GREETING"
    assert err is None


def test_extract_parser_key_mixed_case_normalized():
    key, err = _extract_parser_key('{"parser_key": "GreenHouse"}')
    assert key == "GREENHOUSE"
    assert err is None


# ── OfficialCompanyAdapter — CUSTOM (parser_key path) ────────────────────────


async def test_adapter_custom_parser_key_calls_parser(monkeypatch):
    """Parser registered by parser_key is invoked when config_json has matching key."""

    class _KeyParser(CustomParser):
        async def fetch(self, source, profile, options, collect_date):
            return AdapterResult(
                postings=[
                    RawJobPosting(
                        source="company_1_custom",
                        source_url=f"{_BASE}/jobs/key",
                        company_name="카카오",
                        title="parser_key 개발자",
                    )
                ]
            )

    monkeypatch.setitem(_CUSTOM_REGISTRY_BY_KEY, "GREETING", _KeyParser())

    s = _source(
        company_id=1,
        adapter_type="CUSTOM",
        config_json='{"parser_key": "GREETING"}',
    )
    adapter = OfficialCompanyAdapter([s], [_profile()])
    result = await adapter.fetch(SeedKeywords(), _options(), _COLLECT_DATE)

    assert len(result.postings) == 1
    assert result.postings[0].title == "parser_key 개발자"
    assert result.warnings == []

    monkeypatch.delitem(_CUSTOM_REGISTRY_BY_KEY, "GREETING")


async def test_adapter_custom_parser_key_lowercase_normalized(monkeypatch):
    """Lowercase parser_key in config_json is normalized to uppercase for lookup."""
    called = []

    class _KeyParser(CustomParser):
        async def fetch(self, source, profile, options, collect_date):
            called.append(True)
            return AdapterResult()

    monkeypatch.setitem(_CUSTOM_REGISTRY_BY_KEY, "GREENHOUSE", _KeyParser())

    s = _source(
        company_id=2,
        adapter_type="CUSTOM",
        config_json='{"parser_key": "greenhouse"}',
    )
    adapter = OfficialCompanyAdapter([s], [_profile(company_id=2)])
    result = await adapter.fetch(SeedKeywords(), _options(), _COLLECT_DATE)

    assert called, "parser should have been called"
    assert result.warnings == []

    monkeypatch.delitem(_CUSTOM_REGISTRY_BY_KEY, "GREENHOUSE")


async def test_adapter_custom_parser_key_stripped(monkeypatch):
    """Whitespace around parser_key value is trimmed before lookup."""
    called = []

    class _KeyParser(CustomParser):
        async def fetch(self, source, profile, options, collect_date):
            called.append(True)
            return AdapterResult()

    monkeypatch.setitem(_CUSTOM_REGISTRY_BY_KEY, "GREETING", _KeyParser())

    s = _source(
        company_id=1,
        adapter_type="CUSTOM",
        config_json='{"parser_key": "  GREETING  "}',
    )
    adapter = OfficialCompanyAdapter([s], [_profile()])
    result = await adapter.fetch(SeedKeywords(), _options(), _COLLECT_DATE)

    assert called
    assert result.warnings == []

    monkeypatch.delitem(_CUSTOM_REGISTRY_BY_KEY, "GREETING")


async def test_adapter_custom_unknown_parser_key_warns():
    """Known config_json but parser_key not in _CUSTOM_REGISTRY_BY_KEY → warning."""
    s = _source(
        company_id=5,
        adapter_type="CUSTOM",
        config_json='{"parser_key": "UNKNOWN_PARSER"}',
    )
    adapter = OfficialCompanyAdapter([s], [_profile(company_id=5)])
    result = await adapter.fetch(SeedKeywords(), _options(), _COLLECT_DATE)

    assert result.postings == []
    assert any("parser_key=UNKNOWN_PARSER" in w for w in result.warnings)


async def test_adapter_custom_missing_parser_key_warns():
    """config_json present but parser_key field absent → missing_key warning."""
    s = _source(
        company_id=6,
        adapter_type="CUSTOM",
        config_json='{"max_fetch": 20}',
    )
    adapter = OfficialCompanyAdapter([s], [_profile(company_id=6)])
    result = await adapter.fetch(SeedKeywords(), _options(), _COLLECT_DATE)

    assert result.postings == []
    assert any("missing custom parser_key" in w for w in result.warnings)


async def test_adapter_custom_invalid_config_json_warns():
    """config_json is not valid JSON → invalid config_json warning."""
    s = _source(
        company_id=7,
        adapter_type="CUSTOM",
        config_json="not-valid-json{",
    )
    adapter = OfficialCompanyAdapter([s], [_profile(company_id=7)])
    result = await adapter.fetch(SeedKeywords(), _options(), _COLLECT_DATE)

    assert result.postings == []
    assert any("invalid config_json" in w for w in result.warnings)


async def test_adapter_custom_parser_key_reused_across_companies(monkeypatch):
    """Same parser_key can serve multiple company sources."""
    titles: list[str] = []

    class _SharedParser(CustomParser):
        async def fetch(self, source, profile, options, collect_date):
            name = profile.canonical_name if profile else f"co_{source.company_id}"
            titles.append(name)
            return AdapterResult(
                postings=[
                    RawJobPosting(
                        source=f"company_{source.company_id}_custom",
                        source_url=f"{_BASE}/jobs/{source.company_id}",
                        company_name=name,
                        title="공고",
                    )
                ]
            )

    monkeypatch.setitem(_CUSTOM_REGISTRY_BY_KEY, "SHARED", _SharedParser())

    cfg = '{"parser_key":"SHARED"}'
    sources = [
        _source(company_id=10, adapter_type="CUSTOM", config_json=cfg),
        _source(company_id=11, adapter_type="CUSTOM", config_json=cfg),
    ]
    profiles = [_profile(10, "회사A"), _profile(11, "회사B")]
    adapter = OfficialCompanyAdapter(sources, profiles)
    result = await adapter.fetch(SeedKeywords(), _options(), _COLLECT_DATE)

    assert len(result.postings) == 2
    assert set(titles) == {"회사A", "회사B"}
    assert result.warnings == []

    monkeypatch.delitem(_CUSTOM_REGISTRY_BY_KEY, "SHARED")


# ── OfficialCompanyAdapter — CUSTOM (legacy company_id path) ─────────────────


async def test_adapter_custom_unregistered_warns():
    """No config_json and company_id not in legacy _CUSTOM_REGISTRY → warning."""
    s = _source(company_id=999, adapter_type="CUSTOM")
    adapter = OfficialCompanyAdapter([s], [_profile(company_id=999)])
    result = await adapter.fetch(SeedKeywords(), _options(), _COLLECT_DATE)
    assert result.postings == []
    assert any("no custom parser" in w for w in result.warnings)


async def test_adapter_custom_legacy_company_id_fallback(monkeypatch):
    """DEPRECATED: no config_json → legacy _CUSTOM_REGISTRY[company_id] is tried."""

    class _LegacyParser(CustomParser):
        @property
        def company_id(self) -> int:
            return 88

        async def fetch(self, source, profile, options, collect_date):
            return AdapterResult(
                postings=[
                    RawJobPosting(
                        source="company_88_custom",
                        source_url=f"{_BASE}/jobs/legacy",
                        company_name="LegacyCo",
                        title="레거시 공고",
                    )
                ]
            )

    monkeypatch.setitem(_CUSTOM_REGISTRY, 88, _LegacyParser())

    # No config_json → legacy path
    s = _source(company_id=88, adapter_type="CUSTOM", config_json=None)
    adapter = OfficialCompanyAdapter([s], [_profile(company_id=88, name="LegacyCo")])
    result = await adapter.fetch(SeedKeywords(), _options(), _COLLECT_DATE)

    assert len(result.postings) == 1
    assert result.postings[0].title == "레거시 공고"
    # legacy path: no warnings when parser is found
    assert result.warnings == []

    monkeypatch.delitem(_CUSTOM_REGISTRY, 88)


async def test_adapter_custom_registered_calls_parser(monkeypatch):
    """DEPRECATED legacy path: company_id-keyed registry works without config_json."""

    class _FakeParser(CustomParser):
        @property
        def company_id(self) -> int:
            return 77

        async def fetch(self, source, profile, options, collect_date):
            p = RawJobPosting(
                source="company_77_custom",
                source_url=f"{_BASE}/jobs/custom",
                company_name="FakeCo",
                title="Custom 개발자",
            )
            return AdapterResult(postings=[p])

    parser = _FakeParser()
    monkeypatch.setitem(_CUSTOM_REGISTRY, 77, parser)

    s = _source(company_id=77, adapter_type="CUSTOM")
    adapter = OfficialCompanyAdapter([s], [_profile(company_id=77)])
    result = await adapter.fetch(SeedKeywords(), _options(), _COLLECT_DATE)

    assert len(result.postings) == 1
    assert result.postings[0].title == "Custom 개발자"
    assert result.warnings == []

    monkeypatch.delitem(_CUSTOM_REGISTRY, 77)


async def test_adapter_custom_source_failure_continues():
    """CUSTOM source with invalid config_json should warn but not abort next source."""
    # First source: invalid config_json → warns + empty
    broken = _source(
        company_id=20,
        adapter_type="CUSTOM",
        config_json="{{not json",
    )
    # Second source: unregistered legacy → warns + empty
    missing = _source(company_id=21, adapter_type="CUSTOM")

    adapter = OfficialCompanyAdapter(
        [broken, missing],
        [_profile(20, "BrokenCo"), _profile(21, "MissingCo")],
    )
    result = await adapter.fetch(SeedKeywords(), _options(), _COLLECT_DATE)

    assert result.postings == []
    assert len(result.warnings) == 2
    assert any("invalid config_json" in w for w in result.warnings)
    assert any("no custom parser" in w for w in result.warnings)


# ── OfficialCompanyAdapter — dispatcher ──────────────────────────────────────


async def test_adapter_unsupported_type_warns():
    s = _source(adapter_type="UNKNOWN_TYPE")
    adapter = OfficialCompanyAdapter([s], [_profile()])
    result = await adapter.fetch(SeedKeywords(), _options(), _COLLECT_DATE)
    assert result.postings == []
    assert any("unsupported adapterType" in w for w in result.warnings)


async def test_adapter_none_adapter_type_warns():
    s = OfficialCompanySource(
        company_id=1, source_type="X", adapter_type=None
    )
    adapter = OfficialCompanyAdapter([s], [_profile()])
    result = await adapter.fetch(SeedKeywords(), _options(), _COLLECT_DATE)
    assert any("unsupported adapterType" in w for w in result.warnings)


async def test_adapter_multiple_sources_aggregated(monkeypatch):
    sitemap = _sm_xml([("2026-07-05", f"{_BASE}/jobs/1")])
    page_html = "<html><body><h1>개발자</h1></body></html>"
    feed = _rss_xml([("디자이너", f"{_BASE}/jobs/2", None)])
    mock = _MockClient(
        [(200, sitemap), (200, page_html), (200, feed)]
    )
    monkeypatch.setattr(
        "app.adapters.official_company.AsyncClient", lambda **kw: mock
    )
    sources = [
        _source(company_id=1, adapter_type="SITEMAP"),
        _source(
            company_id=2,
            adapter_type="RSS",
            source_url=f"{_BASE}/feed.xml",
        ),
    ]
    profiles = [_profile(1, "카카오"), _profile(2, "네이버")]
    adapter = OfficialCompanyAdapter(sources, profiles)
    result = await adapter.fetch(SeedKeywords(), _options(), _COLLECT_DATE)

    assert len(result.postings) == 2
    sources_used = {p.source for p in result.postings}
    assert "company_1_sitemap" in sources_used
    assert "company_2_rss" in sources_used
    assert result.warnings == []


async def test_adapter_source_exception_adds_warning_not_crash(monkeypatch):
    """One source raising unexpectedly must not abort remaining sources."""

    class _BrokenClient:
        async def __aenter__(self):
            return self

        async def __aexit__(self, *a):
            pass

        async def get(self, url, **kwargs):
            raise RuntimeError("network unavailable")

    call_count = 0

    def _factory(**kw):
        nonlocal call_count
        call_count += 1
        if call_count == 1:
            return _BrokenClient()  # first source (SITEMAP) fails
        feed = _rss_xml([("정상 공고", f"{_BASE}/jobs/ok", None)])
        return _MockClient([(200, feed)])

    monkeypatch.setattr("app.adapters.official_company.AsyncClient", _factory)

    sources = [
        _source(company_id=1, adapter_type="SITEMAP"),
        _source(
            company_id=2,
            adapter_type="RSS",
            source_url=f"{_BASE}/feed.xml",
        ),
    ]
    adapter = OfficialCompanyAdapter(
        sources, [_profile(1, "카카오"), _profile(2, "네이버")]
    )
    result = await adapter.fetch(SeedKeywords(), _options(), _COLLECT_DATE)

    assert len(result.postings) == 1, "second (RSS) source should succeed"
    assert result.postings[0].title == "정상 공고"
    assert any("network unavailable" in w or "SITEMAP" in w for w in result.warnings)


async def test_adapter_source_stats_aggregated(monkeypatch):
    sitemap = _sm_xml([("2026-07-05", f"{_BASE}/jobs/1")])
    page_html = "<html><body><h1>개발자</h1></body></html>"
    mock = _MockClient([(200, sitemap), (200, page_html)])
    monkeypatch.setattr(
        "app.adapters.official_company.AsyncClient", lambda **kw: mock
    )
    adapter = OfficialCompanyAdapter(
        [_source(adapter_type="SITEMAP")], [_profile()]
    )
    result = await adapter.fetch(SeedKeywords(), _options(), _COLLECT_DATE)
    stats = result.source_stats
    assert stats is not None
    assert stats.discovered == 1
    assert stats.fetched == 1
    assert stats.parsed == 1
    assert stats.selected == 1


async def test_adapter_source_name():
    adapter = OfficialCompanyAdapter([], [])
    assert adapter.source_name == "official_company"


# ── pipeline integration: postings go through normalization ───────────────────


async def test_adapter_postings_normalize_via_pipeline():
    """OfficialCompanyAdapter output normalizes correctly through Pipeline V2."""
    from app.services.normalization import normalize

    raw = RawJobPosting(
        source="company_1_sitemap",
        source_url=f"{_BASE}/jobs/99",
        company_name="카카오",
        title="백엔드 개발자",
        deadline=date(2026, 8, 1),
    )
    normalized = normalize(raw)
    assert normalized.canonical_fingerprint is not None
    assert normalized.source_record_key is not None
    assert normalized.source == "company_1_sitemap"
    assert normalized.company_name == "카카오"
