"""Tests for GreetingParser and greeting_preflight.

All tests are offline: no real HTTP calls.
- Pure-function tests: _parse_greeting_config, _discover_job_urls,
  _extract_job_id, _parse_greeting_job
- Async adapter tests: mock httpx.AsyncClient via monkeypatch
"""

import json
from datetime import date

import httpx
from httpx import TimeoutException

from app.adapters.greeting import (
    GreetingParser,
    _discover_job_urls,
    _extract_job_id,
    _GreetingConfig,
    _parse_greeting_config,
    _parse_greeting_job,
    greeting_preflight,
)
from app.schemas.collection import (
    CollectionOptions,
    CompanyProfile,
    OfficialCompanySource,
)

_BASE = "https://careers.greeting.works"
_SLUG = "kakao"
_LIST_URL = f"{_BASE}/companies/{_SLUG}"
_JOB_URL_1 = f"{_BASE}/companies/{_SLUG}/jobs/101"
_JOB_URL_2 = f"{_BASE}/companies/{_SLUG}/jobs/102"
_COLLECT_DATE = date(2026, 7, 18)


# ── fixtures & helpers ────────────────────────────────────────────────────────


def _source(
    source_url: str | None = _LIST_URL,
    config_json: str | None = None,
    company_id: int = 1,
) -> OfficialCompanySource:
    return OfficialCompanySource(
        company_id=company_id,
        source_type="CAREERS_PAGE",
        source_url=source_url,
        adapter_type="CUSTOM",
        config_json=config_json or json.dumps({"parser_key": "GREETING"}),
    )


def _profile(name: str = "카카오", company_id: int = 1) -> CompanyProfile:
    return CompanyProfile(
        id=company_id,
        canonical_name=name,
        normalized_name=name.lower(),
    )


def _options() -> CollectionOptions:
    return CollectionOptions()


def _list_html(*job_ids: int | str, extra_links: list[str] | None = None) -> str:
    links = "".join(
        f'<a href="/companies/{_SLUG}/jobs/{jid}">직무 {jid}</a>'
        for jid in job_ids
    )
    for href in extra_links or []:
        links += f'<a href="{href}">기타</a>'
    return f"<html><body>{links}</body></html>"


def _detail_html(
    title: str = "백엔드 개발자",
    *,
    employment_type: str | None = None,
    deadline: str | None = None,
    location: str | None = None,
    in_main: bool = True,
) -> str:
    extras = " ".join(
        filter(None, [employment_type, deadline, location])
    )
    if in_main:
        body = f"<main><h1>{title}</h1><p>{extras}</p></main>"
    else:
        body = f"<h1>{title}</h1><p>{extras}</p>"
    return f"<html><body>{body}</body></html>"


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


# ── _parse_greeting_config ────────────────────────────────────────────────────


def test_parse_greeting_config_defaults():
    cfg = _parse_greeting_config(None)
    assert cfg.max_discover == 50
    assert cfg.max_fetch == 20
    assert cfg.include_paths == []
    assert cfg.exclude_paths == []


def test_parse_greeting_config_custom_values():
    raw = json.dumps(
        {"parser_key": "GREETING", "max_discover": 30, "max_fetch": 10}
    )
    cfg = _parse_greeting_config(raw)
    assert cfg.max_discover == 30
    assert cfg.max_fetch == 10


def test_parse_greeting_config_include_exclude_paths():
    raw = json.dumps(
        {"include_paths": ["/jobs/"], "exclude_paths": ["/archived/"]}
    )
    cfg = _parse_greeting_config(raw)
    assert cfg.include_paths == ["/jobs/"]
    assert cfg.exclude_paths == ["/archived/"]


def test_parse_greeting_config_invalid_json_falls_back():
    cfg = _parse_greeting_config("not-json")
    assert cfg == _GreetingConfig()


def test_parse_greeting_config_empty_string_falls_back():
    cfg = _parse_greeting_config("")
    assert cfg == _GreetingConfig()


# ── _discover_job_urls ────────────────────────────────────────────────────────


def test_discover_job_urls_finds_matching_links():
    html = _list_html(101, 102)
    cfg = _GreetingConfig()
    urls = _discover_job_urls(html, _LIST_URL, cfg)
    assert _JOB_URL_1 in urls
    assert _JOB_URL_2 in urls


def test_discover_job_urls_deduplicates():
    html = (
        f'<html><body>'
        f'<a href="/companies/{_SLUG}/jobs/101">a</a>'
        f'<a href="/companies/{_SLUG}/jobs/101">b</a>'
        f'</body></html>'
    )
    urls = _discover_job_urls(html, _LIST_URL, _GreetingConfig())
    assert urls.count(_JOB_URL_1) == 1


def test_discover_job_urls_respects_max_discover():
    html = _list_html(101, 102, 103, 104, 105)
    cfg = _GreetingConfig(max_discover=2)
    urls = _discover_job_urls(html, _LIST_URL, cfg)
    assert len(urls) == 2


def test_discover_job_urls_include_paths_filters():
    html = _list_html(101)
    cfg = _GreetingConfig(include_paths=["/companies/other/"])
    urls = _discover_job_urls(html, _LIST_URL, cfg)
    assert urls == []


def test_discover_job_urls_exclude_paths_skips():
    html = _list_html(101)
    cfg = _GreetingConfig(exclude_paths=[f"/companies/{_SLUG}/"])
    urls = _discover_job_urls(html, _LIST_URL, cfg)
    assert urls == []


def test_discover_job_urls_skips_non_job_links():
    html = _list_html(
        extra_links=[
            f"/companies/{_SLUG}",
            "/about",
            "https://example.com/other",
        ]
    )
    urls = _discover_job_urls(html, _LIST_URL, _GreetingConfig())
    assert urls == []


def test_discover_job_urls_resolves_relative_links():
    html = (
        f'<html><body>'
        f'<a href="/companies/{_SLUG}/jobs/999">job</a>'
        f'</body></html>'
    )
    urls = _discover_job_urls(html, _LIST_URL, _GreetingConfig())
    assert f"{_BASE}/companies/{_SLUG}/jobs/999" in urls


def test_discover_job_urls_strips_query_and_fragment():
    html = (
        f'<html><body>'
        f'<a href="/companies/{_SLUG}/jobs/101?ref=top#apply">job</a>'
        f'</body></html>'
    )
    urls = _discover_job_urls(html, _LIST_URL, _GreetingConfig())
    assert _JOB_URL_1 in urls
    assert all("?" not in u and "#" not in u for u in urls)


# ── _extract_job_id ───────────────────────────────────────────────────────────


def test_extract_job_id_returns_id():
    assert _extract_job_id(_JOB_URL_1) == "101"


def test_extract_job_id_alphanumeric_id():
    url = f"{_BASE}/companies/{_SLUG}/jobs/abc-123"
    assert _extract_job_id(url) == "abc-123"


def test_extract_job_id_returns_none_for_non_job_url():
    assert _extract_job_id(f"{_BASE}/companies/{_SLUG}") is None


# ── _parse_greeting_job ───────────────────────────────────────────────────────


def test_parse_greeting_job_title_from_h1():
    html = _detail_html("프론트엔드 개발자")
    result = _parse_greeting_job(html, _JOB_URL_1, "카카오", "sid")
    assert result is not None
    assert result.title == "프론트엔드 개발자"
    assert result.company_name == "카카오"
    assert result.source == "sid"
    assert result.source_url == _JOB_URL_1


def test_parse_greeting_job_title_from_title_tag():
    html = (
        "<html><head><title>데이터 엔지니어 | 채용</title></head>"
        "<body></body></html>"
    )
    result = _parse_greeting_job(html, _JOB_URL_1, "카카오", "sid")
    assert result is not None
    assert result.title == "데이터 엔지니어"


def test_parse_greeting_job_title_strip_suffix_greeting():
    html = (
        "<html><head><title>백엔드 개발자 | Greeting</title></head>"
        "<body></body></html>"
    )
    result = _parse_greeting_job(html, _JOB_URL_1, "카카오", "sid")
    assert result is not None
    assert result.title == "백엔드 개발자"


def test_parse_greeting_job_no_title_returns_none():
    html = "<html><body><p>내용</p></body></html>"
    result = _parse_greeting_job(html, _JOB_URL_1, "카카오", "sid")
    assert result is None


def test_parse_greeting_job_employment_type_detected():
    html = _detail_html("백엔드 개발자", employment_type="정규직")
    result = _parse_greeting_job(html, _JOB_URL_1, "카카오", "sid")
    assert result is not None
    assert result.employment_type == "정규직"


def test_parse_greeting_job_employment_type_intern():
    html = _detail_html("인턴 개발자", employment_type="인턴")
    result = _parse_greeting_job(html, _JOB_URL_1, "카카오", "sid")
    assert result is not None
    assert result.employment_type == "인턴"


def test_parse_greeting_job_deadline_detected():
    html = _detail_html("백엔드 개발자", deadline="2026년 12월 31일 마감")
    result = _parse_greeting_job(html, _JOB_URL_1, "카카오", "sid")
    assert result is not None
    assert result.deadline == date(2026, 12, 31)


def test_parse_greeting_job_no_deadline_returns_none():
    html = _detail_html("백엔드 개발자")
    result = _parse_greeting_job(html, _JOB_URL_1, "카카오", "sid")
    assert result is not None
    assert result.deadline is None


def test_parse_greeting_job_location_detected():
    html = _detail_html("백엔드 개발자", location="서울")
    result = _parse_greeting_job(html, _JOB_URL_1, "카카오", "sid")
    assert result is not None
    assert result.location == "서울"


def test_parse_greeting_job_location_pangyo():
    html = _detail_html("백엔드 개발자", location="판교")
    result = _parse_greeting_job(html, _JOB_URL_1, "카카오", "sid")
    assert result is not None
    assert result.location == "판교"


def test_parse_greeting_job_description_from_main():
    html = _detail_html("백엔드 개발자", in_main=True)
    result = _parse_greeting_job(html, _JOB_URL_1, "카카오", "sid")
    assert result is not None
    assert result.description is not None
    assert "백엔드 개발자" in result.description


def test_parse_greeting_job_source_external_id():
    result = _parse_greeting_job(
        _detail_html("직무"), _JOB_URL_1, "카카오", "sid"
    )
    assert result is not None
    assert result.source_external_id == "101"


# ── GreetingParser.fetch ──────────────────────────────────────────────────────


async def test_greeting_parser_fetch_returns_postings(monkeypatch):
    list_html = _list_html(101)
    detail_html = _detail_html(
        "백엔드 개발자", employment_type="정규직", location="서울"
    )
    mock = _MockClient([(200, list_html), (200, detail_html)])
    monkeypatch.setattr("app.adapters.greeting.AsyncClient", lambda **kw: mock)

    src = _source()
    result = await GreetingParser().fetch(
        src, _profile(), _options(), _COLLECT_DATE
    )

    assert len(result.postings) == 1
    p = result.postings[0]
    assert p.title == "백엔드 개발자"
    assert p.company_name == "카카오"
    assert p.employment_type == "정규직"
    assert p.location == "서울"
    assert p.source_external_id == "101"
    assert result.source_stats is not None
    assert result.source_stats.discovered == 1
    assert result.source_stats.parsed == 1


async def test_greeting_parser_fetch_no_source_url(monkeypatch):
    src = _source(source_url=None)
    result = await GreetingParser().fetch(
        src, None, _options(), _COLLECT_DATE
    )
    assert result.postings == []
    assert any("no source_url" in w for w in result.warnings)


async def test_greeting_parser_fetch_no_profile_uses_company_id(monkeypatch):
    list_html = _list_html(101)
    detail_html = _detail_html("백엔드 개발자")
    mock = _MockClient([(200, list_html), (200, detail_html)])
    monkeypatch.setattr("app.adapters.greeting.AsyncClient", lambda **kw: mock)

    src = _source(company_id=42)
    result = await GreetingParser().fetch(src, None, _options(), _COLLECT_DATE)

    assert len(result.postings) == 1
    assert result.postings[0].company_name == "company_42"


async def test_greeting_parser_list_page_timeout(monkeypatch):
    request = httpx.Request("GET", _LIST_URL)
    mock = _MockClient([TimeoutException("timeout", request=request)])
    monkeypatch.setattr("app.adapters.greeting.AsyncClient", lambda **kw: mock)

    result = await GreetingParser().fetch(
        _source(), _profile(), _options(), _COLLECT_DATE
    )
    assert result.postings == []
    assert any("timeout" in w for w in result.warnings)


async def test_greeting_parser_list_page_http_error(monkeypatch):
    mock = _MockClient([(503, "Service Unavailable")])
    monkeypatch.setattr("app.adapters.greeting.AsyncClient", lambda **kw: mock)

    result = await GreetingParser().fetch(
        _source(), _profile(), _options(), _COLLECT_DATE
    )
    assert result.postings == []
    assert any("503" in w for w in result.warnings)


async def test_greeting_parser_detail_http_error_skipped(monkeypatch):
    list_html = _list_html(101, 102)
    mock = _MockClient([
        (200, list_html),
        (404, "Not Found"),
        (200, _detail_html("두번째 직무")),
    ])
    monkeypatch.setattr("app.adapters.greeting.AsyncClient", lambda **kw: mock)

    result = await GreetingParser().fetch(
        _source(), _profile(), _options(), _COLLECT_DATE
    )
    assert len(result.postings) == 1
    assert result.postings[0].title == "두번째 직무"
    assert any("404" in w for w in result.warnings)


async def test_greeting_parser_respects_max_fetch(monkeypatch):
    list_html = _list_html(101, 102, 103)
    mock = _MockClient([
        (200, list_html),
        (200, _detail_html("직무 A")),
        (200, _detail_html("직무 B")),
    ])
    monkeypatch.setattr("app.adapters.greeting.AsyncClient", lambda **kw: mock)

    cfg = json.dumps({"parser_key": "GREETING", "max_fetch": 2})
    result = await GreetingParser().fetch(
        _source(config_json=cfg), _profile(), _options(), _COLLECT_DATE
    )
    assert len(result.postings) == 2
    assert result.source_stats is not None
    assert result.source_stats.discovered == 3
    assert result.source_stats.fetched == 2


async def test_greeting_parser_multiple_postings(monkeypatch):
    list_html = _list_html(101, 102)
    mock = _MockClient([
        (200, list_html),
        (200, _detail_html("백엔드 개발자")),
        (200, _detail_html("프론트엔드 개발자")),
    ])
    monkeypatch.setattr("app.adapters.greeting.AsyncClient", lambda **kw: mock)

    result = await GreetingParser().fetch(
        _source(), _profile(), _options(), _COLLECT_DATE
    )
    titles = [p.title for p in result.postings]
    assert "백엔드 개발자" in titles
    assert "프론트엔드 개발자" in titles


async def test_greeting_parser_source_id_format(monkeypatch):
    list_html = _list_html(101)
    mock = _MockClient([(200, list_html), (200, _detail_html("직무"))])
    monkeypatch.setattr("app.adapters.greeting.AsyncClient", lambda **kw: mock)

    result = await GreetingParser().fetch(
        _source(company_id=7), _profile(company_id=7), _options(), _COLLECT_DATE
    )
    assert result.postings[0].source == "company_7_custom_greeting"


# ── greeting_preflight ────────────────────────────────────────────────────────


async def test_greeting_preflight_reachable(monkeypatch):
    list_html = _list_html(101)
    detail_html = _detail_html(
        "백엔드 개발자",
        employment_type="정규직",
        deadline="2026년 12월 31일",
        location="서울",
    )
    mock = _MockClient([(200, list_html), (200, detail_html)])
    monkeypatch.setattr("app.adapters.greeting.AsyncClient", lambda **kw: mock)

    result = await greeting_preflight(_source())
    assert result["reachable"] is True
    assert result["discovered_count"] == 1
    assert result["sample_parsed"] is not None
    assert result["sample_parsed"]["title"] == "백엔드 개발자"
    assert result["sample_parsed"]["employment_type"] == "정규직"
    assert result["sample_parsed"]["deadline"] == "2026-12-31"
    assert result["sample_parsed"]["location"] == "서울"
    assert result["sample_parsed"]["source_external_id"] == "101"
    assert result["warnings"] == []


async def test_greeting_preflight_no_source_url():
    result = await greeting_preflight(_source(source_url=None))
    assert result["reachable"] is False
    assert result["discovered_count"] == 0
    assert result["sample_parsed"] is None
    assert "no source_url" in result["warnings"]


async def test_greeting_preflight_unreachable(monkeypatch):
    mock = _MockClient([(503, "error")])
    monkeypatch.setattr("app.adapters.greeting.AsyncClient", lambda **kw: mock)

    result = await greeting_preflight(_source())
    assert result["reachable"] is False
    assert result["discovered_count"] == 0


async def test_greeting_preflight_no_jobs_discovered(monkeypatch):
    list_html = "<html><body><p>공고 없음</p></body></html>"
    mock = _MockClient([(200, list_html)])
    monkeypatch.setattr("app.adapters.greeting.AsyncClient", lambda **kw: mock)

    result = await greeting_preflight(_source())
    assert result["reachable"] is True
    assert result["discovered_count"] == 0
    assert result["sample_parsed"] is None


async def test_greeting_preflight_sample_fetch_error_warns(monkeypatch):
    list_html = _list_html(101)
    mock = _MockClient([(200, list_html), (500, "Internal Server Error")])
    monkeypatch.setattr("app.adapters.greeting.AsyncClient", lambda **kw: mock)

    result = await greeting_preflight(_source())
    assert result["reachable"] is True
    assert result["discovered_count"] == 1
    assert result["sample_parsed"] is None
    assert len(result["warnings"]) > 0
