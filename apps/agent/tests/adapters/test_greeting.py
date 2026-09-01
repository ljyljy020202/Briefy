"""Tests for GreetingParser and greeting_preflight.

All tests are offline: no real HTTP calls.
- Pure-function tests: _parse_greeting_config, _discover_job_urls,
  _extract_job_id, _parse_greeting_job, _career_to_experience_level,
  _extract_greeting_metadata, _extract_next_data_openings
- Async adapter tests: mock httpx.AsyncClient via monkeypatch

Self-hosted fixture types (synthetic DOM only — no real company HTML):
  Type A : home page embeds job cards  (무신사 / musinsacareers.com style)
  Type B : /apply has the job list     (현대오토에버 / hyundai-autoever.com style)
  Type C : branding home + /apply list (CJ올리브영 / oliveyoung.com style)
"""

import json
from datetime import date

import httpx
from httpx import TimeoutException

from app.adapters.official.greeting import (
    GreetingParser,
    _build_self_hosted_job_url,
    _career_to_experience_level,
    _discover_job_urls,
    _extract_greeting_metadata,
    _extract_job_id,
    _extract_next_data_openings,
    _GreetingConfig,
    _parse_greeting_config,
    _parse_greeting_job,
    _postings_from_next_data,
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

# ── Self-hosted constants ──────────────────────────────────────────────────────
# Type A (무신사 style): home page itself contains job cards
_SH_A_BASE = "https://www.musinsacareers.com"
_SH_A_HOME = f"{_SH_A_BASE}/ko/home"
_SH_A_JOB_1 = f"{_SH_A_BASE}/ko/o/101"
_SH_A_JOB_2 = f"{_SH_A_BASE}/ko/o/102"

# Type B (현대오토에버 style): /apply page contains the job list
_SH_B_BASE = "https://career.hyundai-autoever.com"
_SH_B_APPLY = f"{_SH_B_BASE}/ko/apply"
_SH_B_JOB_1 = f"{_SH_B_BASE}/ko/o/201"
_SH_B_JOB_2 = f"{_SH_B_BASE}/ko/o/202"

# Type C (CJ올리브영 style): branding home + separate /apply job list
_SH_C_BASE = "https://career.oliveyoung.com"
_SH_C_APPLY = f"{_SH_C_BASE}/ko/apply"
_SH_C_JOB_1 = f"{_SH_C_BASE}/ko/o/301"


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
        f'<a href="/companies/{_SLUG}/jobs/{jid}">직무 {jid}</a>' for jid in job_ids
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
    extras = " ".join(filter(None, [employment_type, deadline, location]))
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
    raw = json.dumps({"parser_key": "GREETING", "max_discover": 30, "max_fetch": 10})
    cfg = _parse_greeting_config(raw)
    assert cfg.max_discover == 30
    assert cfg.max_fetch == 10


def test_parse_greeting_config_include_exclude_paths():
    raw = json.dumps({"include_paths": ["/jobs/"], "exclude_paths": ["/archived/"]})
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
        f"<html><body>"
        f'<a href="/companies/{_SLUG}/jobs/101">a</a>'
        f'<a href="/companies/{_SLUG}/jobs/101">b</a>'
        f"</body></html>"
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
        f"<html><body>"
        f'<a href="/companies/{_SLUG}/jobs/999">job</a>'
        f"</body></html>"
    )
    urls = _discover_job_urls(html, _LIST_URL, _GreetingConfig())
    assert f"{_BASE}/companies/{_SLUG}/jobs/999" in urls


def test_discover_job_urls_strips_query_and_fragment():
    html = (
        f"<html><body>"
        f'<a href="/companies/{_SLUG}/jobs/101?ref=top#apply">job</a>'
        f"</body></html>"
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
    result = _parse_greeting_job(_detail_html("직무"), _JOB_URL_1, "카카오", "sid")
    assert result is not None
    assert result.source_external_id == "101"


# ── GreetingParser.fetch ──────────────────────────────────────────────────────


async def test_greeting_parser_fetch_returns_postings(monkeypatch):
    list_html = _list_html(101)
    detail_html = _detail_html(
        "백엔드 개발자", employment_type="정규직", location="서울"
    )
    mock = _MockClient([(200, list_html), (200, detail_html)])
    _target = "app.adapters.official.greeting.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    src = _source()
    result = await GreetingParser().fetch(src, _profile(), _options(), _COLLECT_DATE)

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
    result = await GreetingParser().fetch(src, None, _options(), _COLLECT_DATE)
    assert result.postings == []
    assert any("no source_url" in w for w in result.warnings)


async def test_greeting_parser_fetch_no_profile_uses_company_id(monkeypatch):
    list_html = _list_html(101)
    detail_html = _detail_html("백엔드 개발자")
    mock = _MockClient([(200, list_html), (200, detail_html)])
    _target = "app.adapters.official.greeting.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    src = _source(company_id=42)
    result = await GreetingParser().fetch(src, None, _options(), _COLLECT_DATE)

    assert len(result.postings) == 1
    assert result.postings[0].company_name == "company_42"


async def test_greeting_parser_list_page_timeout(monkeypatch):
    request = httpx.Request("GET", _LIST_URL)
    mock = _MockClient([TimeoutException("timeout", request=request)])
    _target = "app.adapters.official.greeting.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    result = await GreetingParser().fetch(
        _source(), _profile(), _options(), _COLLECT_DATE
    )
    assert result.postings == []
    assert any("timeout" in w for w in result.warnings)


async def test_greeting_parser_list_page_http_error(monkeypatch):
    mock = _MockClient([(503, "Service Unavailable")])
    _target = "app.adapters.official.greeting.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    result = await GreetingParser().fetch(
        _source(), _profile(), _options(), _COLLECT_DATE
    )
    assert result.postings == []
    assert any("503" in w for w in result.warnings)


async def test_greeting_parser_detail_http_error_skipped(monkeypatch):
    list_html = _list_html(101, 102)
    mock = _MockClient(
        [
            (200, list_html),
            (404, "Not Found"),
            (200, _detail_html("두번째 직무")),
        ]
    )
    _target = "app.adapters.official.greeting.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    result = await GreetingParser().fetch(
        _source(), _profile(), _options(), _COLLECT_DATE
    )
    assert len(result.postings) == 1
    assert result.postings[0].title == "두번째 직무"
    assert any("404" in w for w in result.warnings)


async def test_greeting_parser_respects_max_fetch(monkeypatch):
    list_html = _list_html(101, 102, 103)
    mock = _MockClient(
        [
            (200, list_html),
            (200, _detail_html("직무 A")),
            (200, _detail_html("직무 B")),
        ]
    )
    _target = "app.adapters.official.greeting.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

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
    mock = _MockClient(
        [
            (200, list_html),
            (200, _detail_html("백엔드 개발자")),
            (200, _detail_html("프론트엔드 개발자")),
        ]
    )
    _target = "app.adapters.official.greeting.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    result = await GreetingParser().fetch(
        _source(), _profile(), _options(), _COLLECT_DATE
    )
    titles = [p.title for p in result.postings]
    assert "백엔드 개발자" in titles
    assert "프론트엔드 개발자" in titles


async def test_greeting_parser_source_id_format(monkeypatch):
    list_html = _list_html(101)
    mock = _MockClient([(200, list_html), (200, _detail_html("직무"))])
    _target = "app.adapters.official.greeting.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

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
    _target = "app.adapters.official.greeting.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

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
    _target = "app.adapters.official.greeting.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    result = await greeting_preflight(_source())
    assert result["reachable"] is False
    assert result["discovered_count"] == 0


async def test_greeting_preflight_no_jobs_discovered(monkeypatch):
    list_html = "<html><body><p>공고 없음</p></body></html>"
    mock = _MockClient([(200, list_html)])
    _target = "app.adapters.official.greeting.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    result = await greeting_preflight(_source())
    assert result["reachable"] is True
    assert result["discovered_count"] == 0
    assert result["sample_parsed"] is None


async def test_greeting_preflight_sample_fetch_error_warns(monkeypatch):
    list_html = _list_html(101)
    mock = _MockClient([(200, list_html), (500, "Internal Server Error")])
    _target = "app.adapters.official.greeting.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    result = await greeting_preflight(_source())
    assert result["reachable"] is True
    assert result["discovered_count"] == 1
    assert result["sample_parsed"] is None
    assert len(result["warnings"]) > 0


# ── Self-hosted helper ────────────────────────────────────────────────────────


def _sh_source(
    base: str,
    list_url: str,
    company_id: int = 10,
) -> OfficialCompanySource:
    return OfficialCompanySource(
        company_id=company_id,
        source_type="OFFICIAL_CAREER",
        source_url=list_url,
        adapter_type="CUSTOM",
        config_json=json.dumps({"parser_key": "GREETING"}),
    )


def _sh_list_html(
    base: str, *job_ids: int, extra_links: list[str] | None = None
) -> str:
    """Synthetic Greeting self-hosted list page with /ko/o/{id} job cards."""
    links = "".join(f'<a href="/ko/o/{jid}">공고 {jid}</a>' for jid in job_ids)
    for href in extra_links or []:
        links += f'<a href="{href}">기타</a>'
    return f"<html><body>{links}</body></html>"


def _sh_detail_html(
    title: str = "백엔드 개발자", *, employment_type: str | None = None
) -> str:
    extras = employment_type or ""
    return f"<html><body><main><h1>{title}</h1><p>{extras}</p></main></body></html>"


# ── Type A: home page embeds job cards (무신사) ───────────────────────────────


def test_selfhosted_discover_ko_locale_links():
    """Type A: /ko/o/{id} links on the home page are discovered."""
    html = _sh_list_html(_SH_A_BASE, 101, 102)
    urls = _discover_job_urls(html, _SH_A_HOME, _GreetingConfig())
    assert _SH_A_JOB_1 in urls
    assert _SH_A_JOB_2 in urls


def test_selfhosted_discover_en_locale_links():
    """/en/o/{id} links are discovered."""
    html = '<html><body><a href="/en/o/101">job</a></body></html>'
    urls = _discover_job_urls(html, _SH_A_HOME, _GreetingConfig())
    assert f"{_SH_A_BASE}/en/o/101" in urls


def test_selfhosted_discover_no_locale_links():
    """/o/{id} links (no locale prefix) are discovered."""
    html = '<html><body><a href="/o/55">job</a></body></html>'
    urls = _discover_job_urls(html, _SH_A_HOME, _GreetingConfig())
    assert f"{_SH_A_BASE}/o/55" in urls


def test_selfhosted_discover_relative_url_resolved():
    """Relative /ko/o/{id} href is resolved to absolute using base_url origin."""
    html = '<html><body><a href="/ko/o/999">job</a></body></html>'
    urls = _discover_job_urls(html, _SH_A_HOME, _GreetingConfig())
    assert f"{_SH_A_BASE}/ko/o/999" in urls


def test_selfhosted_discover_external_url_excluded():
    """External domain /ko/o/{id} link is NOT discovered (same-origin enforcement)."""
    external = "https://other-domain.com/ko/o/999"
    html = f'<html><body><a href="{external}">external job</a></body></html>'
    urls = _discover_job_urls(html, _SH_A_HOME, _GreetingConfig())
    assert external not in urls
    assert urls == []


def test_selfhosted_discover_deduplicates():
    """Duplicate /ko/o/{id} links appear only once."""
    html = (
        "<html><body>"
        '<a href="/ko/o/101">a</a>'
        '<a href="/ko/o/101">b</a>'
        "</body></html>"
    )
    urls = _discover_job_urls(html, _SH_A_HOME, _GreetingConfig())
    assert urls.count(_SH_A_JOB_1) == 1


def test_selfhosted_discover_non_job_links_excluded():
    """Nav links like /ko/home and /ko/apply are NOT matched."""
    html = (
        "<html><body>"
        '<a href="/ko/home">홈</a>'
        '<a href="/ko/apply">공고 목록</a>'
        '<a href="/about">회사 소개</a>'
        "</body></html>"
    )
    urls = _discover_job_urls(html, _SH_A_HOME, _GreetingConfig())
    assert urls == []


def test_selfhosted_discover_max_discover_respected():
    html = _sh_list_html(_SH_A_BASE, 1, 2, 3, 4, 5)
    cfg = _GreetingConfig(max_discover=2)
    urls = _discover_job_urls(html, _SH_A_HOME, cfg)
    assert len(urls) == 2


def test_selfhosted_extract_job_id_ko_locale():
    assert _extract_job_id(_SH_A_JOB_1) == "101"


def test_selfhosted_extract_job_id_no_locale():
    assert _extract_job_id(f"{_SH_A_BASE}/o/42") == "42"


async def test_selfhosted_type_a_fetch_discovers_and_parses(monkeypatch):
    """Type A: home page with /ko/o/{id} cards — full fetch pipeline."""
    list_html = _sh_list_html(_SH_A_BASE, 101)
    detail_html = _sh_detail_html("프론트엔드 개발자", employment_type="정규직")
    mock = _MockClient([(200, list_html), (200, detail_html)])
    _target = "app.adapters.official.greeting.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    src = _sh_source(_SH_A_BASE, _SH_A_HOME)
    result = await GreetingParser().fetch(
        src, _profile("무신사", 10), _options(), _COLLECT_DATE
    )

    assert len(result.postings) == 1
    p = result.postings[0]
    assert p.title == "프론트엔드 개발자"
    assert p.employment_type == "정규직"
    assert p.source_external_id == "101"
    assert result.source_stats is not None
    assert result.source_stats.discovered == 1
    assert result.source_stats.parsed == 1


async def test_selfhosted_type_a_no_jobs_on_home(monkeypatch):
    """Type A edge: home page has no job cards → 0 discovered, no error."""
    list_html = "<html><body><p>채용 준비 중</p></body></html>"
    mock = _MockClient([(200, list_html)])
    _target = "app.adapters.official.greeting.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    src = _sh_source(_SH_A_BASE, _SH_A_HOME)
    result = await GreetingParser().fetch(
        src, _profile("무신사", 10), _options(), _COLLECT_DATE
    )
    assert result.postings == []
    assert result.source_stats is not None
    assert result.source_stats.discovered == 0


# ── Type B: /apply page contains job list (현대오토에버) ──────────────────────


async def test_selfhosted_type_b_apply_page_discovered(monkeypatch):
    """Type B: source_url=/ko/apply, /ko/o/{id} links discovered on that page."""
    list_html = _sh_list_html(_SH_B_BASE, 201, 202)
    detail_html = _sh_detail_html("클라우드 엔지니어")
    mock = _MockClient([(200, list_html), (200, detail_html), (200, detail_html)])
    _target = "app.adapters.official.greeting.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    src = _sh_source(_SH_B_BASE, _SH_B_APPLY, company_id=20)
    result = await GreetingParser().fetch(
        src, _profile("현대오토에버", 20), _options(), _COLLECT_DATE
    )

    assert result.source_stats is not None
    assert result.source_stats.discovered == 2
    assert len(result.postings) == 2
    assert all(p.source_url.startswith(_SH_B_BASE) for p in result.postings)


async def test_selfhosted_type_b_max_fetch_respected(monkeypatch):
    """Type B: max_fetch=1 fetches only one of two discovered jobs."""
    list_html = _sh_list_html(_SH_B_BASE, 201, 202)
    detail_html = _sh_detail_html("SW 개발자")
    mock = _MockClient([(200, list_html), (200, detail_html)])
    _target = "app.adapters.official.greeting.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    cfg = json.dumps({"parser_key": "GREETING", "max_fetch": 1})
    src = _sh_source(_SH_B_BASE, _SH_B_APPLY, company_id=20)
    src = OfficialCompanySource(
        company_id=20,
        source_type="OFFICIAL_CAREER",
        source_url=_SH_B_APPLY,
        adapter_type="CUSTOM",
        config_json=cfg,
    )
    result = await GreetingParser().fetch(
        src, _profile("현대오토에버", 20), _options(), _COLLECT_DATE
    )

    assert result.source_stats is not None
    assert result.source_stats.discovered == 2
    assert result.source_stats.fetched == 1
    assert len(result.postings) == 1


async def test_selfhosted_type_b_detail_title_missing_skipped(monkeypatch):
    """Type B: detail page with no title → posting skipped, no crash."""
    list_html = _sh_list_html(_SH_B_BASE, 201)
    no_title_html = "<html><body><p>내용만 있는 페이지</p></body></html>"
    mock = _MockClient([(200, list_html), (200, no_title_html)])
    _target = "app.adapters.official.greeting.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    src = _sh_source(_SH_B_BASE, _SH_B_APPLY, company_id=20)
    result = await GreetingParser().fetch(
        src, _profile("현대오토에버", 20), _options(), _COLLECT_DATE
    )
    assert result.postings == []
    assert result.source_stats is not None
    assert result.source_stats.discovered == 1
    assert result.source_stats.parsed == 0


async def test_selfhosted_type_b_optional_fields_absent(monkeypatch):
    """Type B: detail page with title only — optional fields are None, not inferred."""
    list_html = _sh_list_html(_SH_B_BASE, 201)
    detail_html = "<html><body><main><h1>데이터 엔지니어</h1></main></body></html>"
    mock = _MockClient([(200, list_html), (200, detail_html)])
    _target = "app.adapters.official.greeting.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    src = _sh_source(_SH_B_BASE, _SH_B_APPLY, company_id=20)
    result = await GreetingParser().fetch(
        src, _profile("현대오토에버", 20), _options(), _COLLECT_DATE
    )

    assert len(result.postings) == 1
    p = result.postings[0]
    assert p.title == "데이터 엔지니어"
    assert p.employment_type is None
    assert p.deadline is None
    assert p.location is None


async def test_selfhosted_type_b_partial_detail_failure_warns(monkeypatch):
    """Type B: first detail 500, second OK → warning + partial success."""
    list_html = _sh_list_html(_SH_B_BASE, 201, 202)
    detail_html = _sh_detail_html("SW 개발자")
    mock = _MockClient(
        [
            (200, list_html),
            (500, "Internal Server Error"),
            (200, detail_html),
        ]
    )
    _target = "app.adapters.official.greeting.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    src = _sh_source(_SH_B_BASE, _SH_B_APPLY, company_id=20)
    result = await GreetingParser().fetch(
        src, _profile("현대오토에버", 20), _options(), _COLLECT_DATE
    )

    assert len(result.postings) == 1
    assert any("500" in w for w in result.warnings)


# ── Type C: branding home + separate /apply list (CJ올리브영) ─────────────────


async def test_selfhosted_type_c_apply_page_fetched(monkeypatch):
    """Type C: source_url is /ko/apply (after migration fix); jobs discovered there."""
    list_html = _sh_list_html(_SH_C_BASE, 301)
    detail_html = _sh_detail_html("MD 기획자")
    mock = _MockClient([(200, list_html), (200, detail_html)])
    _target = "app.adapters.official.greeting.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    src = _sh_source(_SH_C_BASE, _SH_C_APPLY, company_id=30)
    result = await GreetingParser().fetch(
        src, _profile("CJ올리브영", 30), _options(), _COLLECT_DATE
    )

    assert result.source_stats is not None
    assert result.source_stats.discovered == 1
    assert len(result.postings) == 1
    assert result.postings[0].title == "MD 기획자"
    assert result.postings[0].source_external_id == "301"


async def test_selfhosted_type_c_preflight_success(monkeypatch):
    """Type C preflight: reachable, discovered > 0, sample parsed."""
    list_html = _sh_list_html(_SH_C_BASE, 301)
    detail_html = _sh_detail_html("뷰티 MD")
    mock = _MockClient([(200, list_html), (200, detail_html)])
    _target = "app.adapters.official.greeting.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    src = _sh_source(_SH_C_BASE, _SH_C_APPLY, company_id=30)
    result = await greeting_preflight(src)

    assert result["reachable"] is True
    assert result["discovered_count"] == 1
    assert result["sample_parsed"] is not None
    assert result["sample_parsed"]["title"] == "뷰티 MD"
    assert result["sample_parsed"]["source_external_id"] == "301"


# ── Regression: hosted pattern still works after self-hosted support added ────


def test_hosted_pattern_still_discovered_after_selfhosted_added():
    """Regression: /companies/{slug}/jobs/{id} links still discovered."""
    html = _list_html(101, 102)
    urls = _discover_job_urls(html, _LIST_URL, _GreetingConfig())
    assert _JOB_URL_1 in urls
    assert _JOB_URL_2 in urls


def test_hosted_extract_job_id_unchanged():
    assert _extract_job_id(_JOB_URL_1) == "101"


async def test_hosted_parser_fetch_regression(monkeypatch):
    """Regression: existing hosted-pattern fetch pipeline unchanged."""
    list_html = _list_html(101)
    detail_html = _detail_html("백엔드 개발자", employment_type="정규직")
    mock = _MockClient([(200, list_html), (200, detail_html)])
    _target = "app.adapters.official.greeting.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    result = await GreetingParser().fetch(
        _source(), _profile(), _options(), _COLLECT_DATE
    )
    assert len(result.postings) == 1
    assert result.postings[0].title == "백엔드 개발자"
    assert result.postings[0].source_external_id == "101"


# ── _career_to_experience_level ──────────────────────────────────────────────


def test_career_new_comer():
    assert _career_to_experience_level({"careerType": "NEW_COMER"}) == "신입"


def test_career_not_matter():
    assert _career_to_experience_level({"careerType": "NOT_MATTER"}) == "경력 무관"


def test_career_new_comer_and_experienced():
    assert (
        _career_to_experience_level({"careerType": "NEW_COMER_AND_EXPERIENCED"})
        == "신입/경력"
    )


def test_career_experienced_with_from():
    assert (
        _career_to_experience_level(
            {"careerType": "EXPERIENCED", "careerFrom": 3, "careerTo": None}
        )
        == "3년 이상"
    )


def test_career_experienced_no_from():
    assert (
        _career_to_experience_level({"careerType": "EXPERIENCED", "careerFrom": None})
        == "경력"
    )


def test_career_none_returns_none():
    assert _career_to_experience_level(None) is None


def test_career_unknown_type_returns_none():
    assert _career_to_experience_level({"careerType": "UNKNOWN"}) is None


# ── _extract_greeting_metadata ───────────────────────────────────────────────


def _opening(
    opening_id: int = 1001,
    title: str = "백엔드 개발자",
    job: str | None = "Backend Engineering",
    occupation: str | None = None,
    location: str = "서울",
    career_type: str = "EXPERIENCED",
    career_from: int | None = 3,
    career_to: int | None = None,
    employment_type: str = "FULL_TIME_WORKER",
    open_date: str = "2026-07-01T09:00:00Z",
    due_date: str | None = None,
) -> dict:
    """Build a synthetic Greeting 'openings' list entry."""
    ws_job = {"id": 1, "job": job, "sortOrder": 1} if job else None
    ws_occ = {"id": 2, "occupation": occupation, "sortOrder": 1} if occupation else None
    return {
        "openingId": opening_id,
        "title": title,
        "deploy": True,
        "fixed": False,
        "openDate": open_date,
        "dueDate": due_date,
        "deadlineDDay": None,
        "workspaceDivision": None,
        "group": {"name": "TestCo"},
        "openingJobPosition": {
            "openingJobPositionSetting": {"id": 100, "maxPriority": 1},
            "openingJobPositions": [
                {
                    "id": 200,
                    "workspaceField": None,
                    "workspaceOccupation": ws_occ,
                    "workspaceJob": ws_job,
                    "workspacePlace": {
                        "id": 300,
                        "location": location,
                        "place": None,
                        "workFromHome": False,
                    },
                    "jobPositionCareer": {
                        "id": 400,
                        "careerFrom": career_from,
                        "careerTo": career_to,
                        "careerType": career_type,
                    },
                    "jobPositionEmployment": {
                        "id": 500,
                        "employmentType": employment_type,
                    },
                }
            ],
            "openingJobPositionCount": 1,
        },
    }


def test_extract_metadata_dev_role_from_workspace_job():
    meta = _extract_greeting_metadata(
        _opening(job="Backend Engineering", occupation=None)
    )
    assert meta["roles"] == ["Backend Engineering"]


def test_extract_metadata_role_from_occupation_when_no_job():
    meta = _extract_greeting_metadata(_opening(job=None, occupation="Design/VMD"))
    assert meta["roles"] == ["Design/VMD"]


def test_extract_metadata_non_dev_role_preserved():
    """Non-dev roles (MD, Sales, Design) must be stored as-is, not filtered."""
    for role in ["MD", "Sales Manager", "BX Design", "Off-Line Operation"]:
        meta = _extract_greeting_metadata(_opening(job=role))
        assert meta["roles"] == [role], f"Expected [{role}], got {meta['roles']}"


def test_extract_metadata_experience_level_3_year():
    meta = _extract_greeting_metadata(
        _opening(career_type="EXPERIENCED", career_from=3)
    )
    assert meta["experience_level"] == "3년 이상"


def test_extract_metadata_experience_level_new_comer():
    meta = _extract_greeting_metadata(
        _opening(career_type="NEW_COMER", career_from=None)
    )
    assert meta["experience_level"] == "신입"


def test_extract_metadata_experience_level_not_matter():
    meta = _extract_greeting_metadata(
        _opening(career_type="NOT_MATTER", career_from=None)
    )
    assert meta["experience_level"] == "경력 무관"


def test_extract_metadata_employment_type_full_time():
    meta = _extract_greeting_metadata(_opening(employment_type="FULL_TIME_WORKER"))
    assert meta["employment_type"] == "정규직"


def test_extract_metadata_employment_type_contract():
    meta = _extract_greeting_metadata(_opening(employment_type="CONTRACT_WORKER"))
    assert meta["employment_type"] == "계약직"


def test_extract_metadata_location():
    meta = _extract_greeting_metadata(_opening(location="서울/경기"))
    assert meta["location"] == "서울/경기"


def test_extract_metadata_open_date_sets_posted_at():
    meta = _extract_greeting_metadata(_opening(open_date="2026-07-01T09:00:00Z"))
    assert meta["posted_at"] is not None
    assert meta["posted_at"].year == 2026
    assert meta["posted_at"].month == 7


def test_extract_metadata_due_date_sets_deadline():
    meta = _extract_greeting_metadata(_opening(due_date="2026-08-31T14:59:59Z"))
    assert meta["deadline"] == date(2026, 8, 31)


def test_extract_metadata_no_positions_returns_empty():
    opening = _opening()
    opening["openingJobPosition"]["openingJobPositions"] = []
    meta = _extract_greeting_metadata(opening)
    assert meta["roles"] == []
    assert meta["experience_level"] is None
    assert meta["employment_type"] is None
    assert meta["location"] is None


# ── _extract_next_data_openings ───────────────────────────────────────────────


def _next_data_html(openings: list[dict]) -> str:
    """Build a minimal HTML page with __NEXT_DATA__ containing an openings query."""
    payload = {
        "props": {
            "pageProps": {
                "dehydratedState": {
                    "queries": [
                        {
                            "queryKey": ["openings"],
                            "state": {"data": openings},
                        }
                    ]
                }
            }
        }
    }
    payload_json = json.dumps(payload, ensure_ascii=False)
    return (
        f"<html><head></head><body>"
        f'<script id="__NEXT_DATA__" type="application/json">'
        f"{payload_json}"
        f"</script></body></html>"
    )


def test_extract_next_data_openings_returns_list():
    openings = [_opening(1001), _opening(1002)]
    html = _next_data_html(openings)
    result = _extract_next_data_openings(html)
    assert result is not None
    assert len(result) == 2


def test_extract_next_data_openings_not_found_returns_none():
    result = _extract_next_data_openings("<html><body><p>no data</p></body></html>")
    assert result is None


def test_extract_next_data_openings_invalid_json_returns_none():
    result = _extract_next_data_openings(
        '<html><script id="__NEXT_DATA__">not json</script></html>'
    )
    assert result is None


def test_extract_next_data_openings_empty_list_returns_empty():
    html = _next_data_html([])
    result = _extract_next_data_openings(html)
    assert result == []


# ── _postings_from_next_data ─────────────────────────────────────────────────


def test_postings_from_next_data_basic():
    openings = [
        _opening(1001, "Backend Developer", job="Backend Engineering"),
        _opening(1002, "Frontend Developer", job="Frontend Engineering"),
    ]
    base_url = "https://career.example.com/ko/apply"
    stubs, warnings = _postings_from_next_data(
        openings, base_url, "TestCo", "company_99_custom_greeting", _GreetingConfig()
    )
    assert len(stubs) == 2
    assert stubs[0].title == "Backend Developer"
    assert stubs[0].roles == ["Backend Engineering"]
    assert stubs[0].source_external_id == "1001"
    assert stubs[0].source_url == "https://career.example.com/ko/o/1001"
    assert warnings == []


def test_postings_from_next_data_non_dev_roles_preserved():
    openings = [
        _opening(2001, "MD 기획자", job="MD"),
        _opening(2002, "BX Design", occupation="BX Design", job=None),
        _opening(2003, "영업 담당", occupation="Off-Line Operation", job=None),
    ]
    stubs, _ = _postings_from_next_data(
        openings,
        "https://career.example.com/ko/apply",
        "TestCo",
        "src",
        _GreetingConfig(),
    )
    assert stubs[0].roles == ["MD"]
    assert stubs[1].roles == ["BX Design"]
    assert stubs[2].roles == ["Off-Line Operation"]


def test_postings_from_next_data_respects_max_discover():
    openings = [_opening(i) for i in range(1001, 1010)]
    stubs, _ = _postings_from_next_data(
        openings,
        "https://career.example.com/ko/apply",
        "TestCo",
        "src",
        _GreetingConfig(max_discover=3),
    )
    assert len(stubs) == 3


def test_postings_from_next_data_experience_levels():
    cases = [
        ("NEW_COMER", None, "신입"),
        ("NOT_MATTER", None, "경력 무관"),
        ("EXPERIENCED", 3, "3년 이상"),
        ("EXPERIENCED", None, "경력"),
    ]
    for i, (career_type, career_from, expected_level) in enumerate(cases):
        openings = [
            _opening(3000 + i, career_type=career_type, career_from=career_from)
        ]
        stubs, _ = _postings_from_next_data(
            openings,
            "https://career.example.com/ko/apply",
            "TestCo",
            "src",
            _GreetingConfig(),
        )
        assert stubs[0].experience_level == expected_level, (
            f"career_type={career_type} career_from={career_from}: "
            f"expected {expected_level}, got {stubs[0].experience_level}"
        )


def test_postings_from_next_data_description_is_none():
    """Stubs from __NEXT_DATA__ have description=None (filled later by detail fetch)."""
    openings = [_opening(4001, "백엔드 개발자")]
    stubs, _ = _postings_from_next_data(
        openings,
        "https://career.example.com/ko/apply",
        "TestCo",
        "src",
        _GreetingConfig(),
    )
    assert stubs[0].description is None


def test_postings_from_next_data_skips_opening_without_title():
    openings = [
        {
            "openingId": 5001,
            "title": "",
            "openingJobPosition": {"openingJobPositions": []},
        },
        _opening(5002, "정상 공고"),
    ]
    stubs, _ = _postings_from_next_data(
        openings,
        "https://career.example.com/ko/apply",
        "TestCo",
        "src",
        _GreetingConfig(),
    )
    assert len(stubs) == 1
    assert stubs[0].title == "정상 공고"


# ── _build_self_hosted_job_url ────────────────────────────────────────────────


def test_build_job_url_ko_locale_from_home():
    url = _build_self_hosted_job_url("https://www.musinsacareers.com/ko/home", 227433)
    assert url == "https://www.musinsacareers.com/ko/o/227433"


def test_build_job_url_ko_locale_from_apply():
    base = "https://career.hyundai-autoever.com/ko/apply"
    url = _build_self_hosted_job_url(base, 229348)
    assert url == "https://career.hyundai-autoever.com/ko/o/229348"


def test_build_job_url_no_locale():
    url = _build_self_hosted_job_url("https://career.example.com/apply", 9999)
    assert url == "https://career.example.com/o/9999"


# ── GreetingParser.fetch with __NEXT_DATA__ path ─────────────────────────────


def _next_data_list_html(openings: list[dict]) -> str:
    """Full page HTML with __NEXT_DATA__ openings AND anchor links for job cards."""
    payload = {
        "props": {
            "pageProps": {
                "dehydratedState": {
                    "queries": [
                        {
                            "queryKey": ["openings"],
                            "state": {"data": openings},
                        }
                    ]
                }
            }
        }
    }
    payload_json = json.dumps(payload, ensure_ascii=False)
    # Also include the anchor links (as real Greeting pages do)
    links = "".join(
        '<a href="/ko/o/{}">{}</a>'.format(o["openingId"], o["title"]) for o in openings
    )
    return (
        f"<html><head></head><body>{links}"
        f'<script id="__NEXT_DATA__" type="application/json">'
        f"{payload_json}"
        f"</script></body></html>"
    )


def _detail_html_with_description(description: str = "채용 공고 상세 설명") -> str:
    return (
        f"<html><body><main><h1>공고 제목</h1>"
        f"<div class='desc'>{description}</div></main></body></html>"
    )


async def test_fetch_next_data_path_extracts_metadata(monkeypatch):
    """When __NEXT_DATA__ openings present, metadata comes from payload, not DOM."""
    openings = [
        _opening(
            101,
            "백엔드 개발자",
            job="Backend Engineering",
            career_type="EXPERIENCED",
            career_from=3,
            employment_type="FULL_TIME_WORKER",
            location="서울",
        )
    ]
    list_html = _next_data_list_html(openings)
    detail_html = _detail_html_with_description("담당 업무: 서버 개발")
    mock = _MockClient([(200, list_html), (200, detail_html)])
    _target = "app.adapters.official.greeting.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    src = _sh_source(_SH_A_BASE, _SH_A_HOME, company_id=10)
    result = await GreetingParser().fetch(
        src, _profile("무신사", 10), _options(), _COLLECT_DATE
    )

    assert len(result.postings) == 1
    p = result.postings[0]
    assert p.title == "백엔드 개발자"
    assert p.roles == ["Backend Engineering"]
    assert p.experience_level == "3년 이상"
    assert p.employment_type == "정규직"
    assert p.location == "서울"
    assert p.source_external_id == "101"
    assert result.source_stats is not None
    assert result.source_stats.discovered == 1


async def test_fetch_non_dev_role_not_misclassified(monkeypatch):
    """Non-dev roles (Design/VMD, IT PM, MD) stored as-is from structured metadata."""
    non_dev_cases = [
        ("Design/VMD 담당자", "Design/VMD", None),
        ("IT PM - CRM", None, "IT PM"),
        ("물류 관리", None, "Logistics"),
    ]
    for title, job, occ in non_dev_cases:
        openings = [_opening(200, title, job=job, occupation=occ)]
        list_html = _next_data_list_html(openings)
        detail_html = _detail_html_with_description("상세 설명")
        mock = _MockClient([(200, list_html), (200, detail_html)])
        _target = "app.adapters.official.greeting.AsyncClient"
        monkeypatch.setattr(_target, lambda **kw: mock)

        src = _sh_source(_SH_B_BASE, _SH_B_APPLY, company_id=20)
        result = await GreetingParser().fetch(
            src, _profile("현대오토에버", 20), _options(), _COLLECT_DATE
        )

        assert len(result.postings) == 1
        p = result.postings[0]
        expected_role = job or occ or ""
        assert p.roles == [
            expected_role
        ], f"title={title}: expected [{expected_role}], got {p.roles}"


async def test_fetch_next_data_empty_openings_returns_zero_with_warning(monkeypatch):
    """__NEXT_DATA__ with empty openings list → 0 results + warning (not silent)."""
    list_html = _next_data_list_html([])
    mock = _MockClient([(200, list_html)])
    _target = "app.adapters.official.greeting.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    src = _sh_source(_SH_A_BASE, _SH_A_HOME, company_id=10)
    result = await GreetingParser().fetch(
        src, _profile("무신사", 10), _options(), _COLLECT_DATE
    )

    assert result.postings == []
    assert result.source_stats is not None
    assert result.source_stats.discovered == 0
    # Must warn to distinguish from parse failure
    assert any("empty" in w.lower() for w in result.warnings)


async def test_fetch_next_data_detail_failure_keeps_stub(monkeypatch):
    """When detail page fails, the posting is kept without description (not dropped)."""
    openings = [_opening(301, "프론트엔드 개발자", job="Frontend Engineering")]
    list_html = _next_data_list_html(openings)
    mock = _MockClient([(200, list_html), (500, "Server Error")])
    _target = "app.adapters.official.greeting.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    src = _sh_source(_SH_C_BASE, _SH_C_APPLY, company_id=30)
    result = await GreetingParser().fetch(
        src, _profile("CJ올리브영", 30), _options(), _COLLECT_DATE
    )

    # Posting must NOT be dropped even though detail failed
    assert len(result.postings) == 1
    p = result.postings[0]
    assert p.title == "프론트엔드 개발자"
    assert p.roles == ["Frontend Engineering"]
    assert p.description is None
    assert any("500" in w for w in result.warnings)


async def test_fetch_next_data_detail_timeout_keeps_stub(monkeypatch):
    """When detail page times out, the posting is kept without description."""
    openings = [_opening(302, "데이터 엔지니어", job="Data Engineering")]
    list_html = _next_data_list_html(openings)
    request = httpx.Request("GET", f"{_SH_C_BASE}/ko/o/302")
    mock = _MockClient([(200, list_html), TimeoutException("timeout", request=request)])
    _target = "app.adapters.official.greeting.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    src = _sh_source(_SH_C_BASE, _SH_C_APPLY, company_id=30)
    result = await GreetingParser().fetch(
        src, _profile("CJ올리브영", 30), _options(), _COLLECT_DATE
    )

    assert len(result.postings) == 1
    assert result.postings[0].description is None
    assert any("timeout" in w for w in result.warnings)


async def test_fetch_next_data_multiple_openings_respects_max_fetch(monkeypatch):
    """max_fetch is respected when fetching detail pages for __NEXT_DATA__ openings."""
    openings = [_opening(400 + i, f"공고 {i}") for i in range(5)]
    list_html = _next_data_list_html(openings)
    detail_html = _detail_html_with_description("상세 내용")
    # Provide 3 detail pages for max_fetch=3
    mock = _MockClient([(200, list_html)] + [(200, detail_html)] * 3)
    _target = "app.adapters.official.greeting.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    cfg = json.dumps({"parser_key": "GREETING", "max_fetch": 3})
    src = OfficialCompanySource(
        company_id=40,
        source_type="OFFICIAL_CAREER",
        source_url=_SH_A_HOME,
        adapter_type="CUSTOM",
        config_json=cfg,
    )
    result = await GreetingParser().fetch(
        src, _profile("TestCo", 40), _options(), _COLLECT_DATE
    )

    assert result.source_stats is not None
    assert result.source_stats.discovered == 5
    assert result.source_stats.fetched == 3
    assert len(result.postings) == 3


async def test_fetch_structured_data_over_inferred_roles(monkeypatch):
    """Structured roles from __NEXT_DATA__ take precedence over title-inferred roles."""
    # Title "백엔드 개발자" would normally infer roles=["백엔드"] from title.
    # But explicit roles="Backend Engineering" from payload should win.
    openings = [_opening(501, "백엔드 개발자", job="Backend Engineering")]
    list_html = _next_data_list_html(openings)
    detail_html = _detail_html_with_description("서버 개발")
    mock = _MockClient([(200, list_html), (200, detail_html)])
    _target = "app.adapters.official.greeting.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    src = _sh_source(_SH_A_BASE, _SH_A_HOME, company_id=10)
    result = await GreetingParser().fetch(
        src, _profile("무신사", 10), _options(), _COLLECT_DATE
    )

    assert result.postings[0].roles == ["Backend Engineering"]
    # normalization will use this as-is (not infer "백엔드" from title)


async def test_fetch_malformed_next_data_falls_back_to_anchor_links(monkeypatch):
    """If __NEXT_DATA__ is present but malformed, fall back to anchor-link discovery."""
    # Malformed: __NEXT_DATA__ is invalid JSON
    list_html = (
        "<html><body>"
        '<a href="/ko/o/601">공고 601</a>'
        '<script id="__NEXT_DATA__" type="application/json">invalid{json}</script>'
        "</body></html>"
    )
    detail_html = _sh_detail_html("폴백 공고")
    mock = _MockClient([(200, list_html), (200, detail_html)])
    _target = "app.adapters.official.greeting.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    src = _sh_source(_SH_A_BASE, _SH_A_HOME, company_id=10)
    result = await GreetingParser().fetch(
        src, _profile("무신사", 10), _options(), _COLLECT_DATE
    )

    # Falls back to anchor-link discovery: 1 job found
    assert len(result.postings) == 1
    assert result.postings[0].title == "폴백 공고"


async def test_preflight_with_next_data_returns_metadata(monkeypatch):
    """preflight returns experience_level and roles when __NEXT_DATA__ available."""
    openings = [
        _opening(
            701,
            "백엔드 개발자",
            job="Backend Engineering",
            career_type="EXPERIENCED",
            career_from=3,
            employment_type="FULL_TIME_WORKER",
            location="서울",
        )
    ]
    list_html = _next_data_list_html(openings)
    detail_html = _detail_html_with_description("서버 개발")
    mock = _MockClient([(200, list_html), (200, detail_html)])
    _target = "app.adapters.official.greeting.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    src = _sh_source(_SH_A_BASE, _SH_A_HOME, company_id=10)
    result = await greeting_preflight(src)

    assert result["reachable"] is True
    assert result["discovered_count"] == 1
    assert result["sample_parsed"] is not None
    assert result["sample_parsed"]["roles"] == ["Backend Engineering"]
    assert result["sample_parsed"]["experience_level"] == "3년 이상"
    assert result["sample_parsed"]["employment_type"] == "정규직"
    assert result["sample_parsed"]["location"] == "서울"
