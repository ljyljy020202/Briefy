"""Tests for TossCareersParser / TOSS_CAREERS adapter.

All tests are offline — no real network calls.

Coverage:
  Pure-function tests:
    - _parse_config: defaults, max_discover, max_items cap
    - _parse_sitemap: job_id extraction, dedup, non-job URL filter,
                      empty sitemap, malformed XML
    - _parse_job_detail_html: present, missing __NEXT_DATA__, missing job-detail query
    - _extract_experience: Server Dev 3년 이상, 인턴, 신입, 경력 무관,
                           N~M 범위, N년 이상 M년 이하, 연수 불명, preferred 제외
    - _build_roles: main+sub, dedup, None mainCategory
    - _parse_deadline: date, empty, None, invalid
    - _job_to_posting: field mapping, 단기계약직→계약직, location 정규화
  Registration:
    - TOSS_CAREERS registered; greenhouse + naver unaffected
  Adapter integration (mock AsyncClient):
    - Full sitemap → 8 unique job_ids (dedup of 9 entries)
    - Server Developer: 경력 3년 이상, Engineering/Backend, 토스뱅크
    - 인턴십: 인턴, deadline, 신입 (from required section)
    - 계약직 + N~M년 range
    - 경력 무관
    - 1년 이상 5년 이하 → 경력 1~5년
    - mainCategory=None → roles=[subCategory]
    - 단기계약직 → 계약직
    - max_items cap
    - sitemap HTTP error → warning
    - sitemap timeout → warning
    - sitemap malformed XML → warning
    - sitemap genuine empty → 0 postings, no warning
    - detail HTTP error → warning, skip (other jobs proceed)
    - detail timeout → warning, skip
    - detail __NEXT_DATA__ missing → warning, skip
    - preferred section excluded from experience
    - source_record_key stability
    - DAANGN_CAREERS and NAVER_CAREERS unaffected (regression)
"""

import json
from datetime import date
from pathlib import Path

import httpx
import pytest

import app.adapters.official.toss_careers  # noqa: F401
from app.adapters.official.toss_careers import (
    TossCareersParser,
    _build_roles,
    _extract_experience,
    _job_to_posting,
    _parse_config,
    _parse_deadline,
    _parse_job_detail_html,
    _parse_sitemap,
)
from app.adapters.official_company import _CUSTOM_REGISTRY_BY_KEY
from app.core.identifiers import compute_source_record_key
from app.schemas.collection import (
    CollectionOptions,
    CompanyProfile,
    OfficialCompanySource,
)

_FIXTURES = Path(__file__).parent / "fixtures" / "toss"
_COLLECT_DATE = date(2026, 8, 15)
_COMPANY_ID = 9


def _read_fixture(name: str) -> str:
    return (_FIXTURES / name).read_text(encoding="utf-8")


def _source(config_json: str | None = None) -> OfficialCompanySource:
    return OfficialCompanySource(
        company_id=_COMPANY_ID,
        source_type="OFFICIAL_CAREER",
        source_url="https://toss.im/career/jobs",
        adapter_type="CUSTOM",
        config_json=config_json
        or json.dumps({"parser_key": "TOSS_CAREERS", "max_items": 50}),
    )


def _profile() -> CompanyProfile:
    return CompanyProfile(id=_COMPANY_ID, canonical_name="토스", normalized_name="토스")


def _options() -> CollectionOptions:
    return CollectionOptions()


# ── Fixture HTML loader ───────────────────────────────────────────────────────


_SITEMAP_XML = _read_fixture("sitemap.xml")

_JOB_HTMLS: dict[str, str] = {
    jid: _read_fixture(fname)
    for jid, fname in {
        "1001001003": "job_server_dev.html",
        "1002001003": "job_intern.html",
        "1003001003": "job_contract_range.html",
        "1004001003": "job_toss_pay.html",
        "1005001003": "job_toss_insurance.html",
        "1006001003": "job_no_main_cat.html",
        "1007001003": "job_android.html",
        "1008001003": "job_short_term_contract.html",
    }.items()
}


# ── Mock HTTP client ──────────────────────────────────────────────────────────


class _MockClient:
    def __init__(self, handler):
        self._handler = handler
        self.calls: list[str] = []

    async def __aenter__(self):
        return self

    async def __aexit__(self, *a):
        pass

    async def get(self, url: str, **kwargs) -> httpx.Response:
        self.calls.append(url)
        return await self._handler(url, kwargs)


def _ok(url: str, content: str, content_type: str = "text/html") -> httpx.Response:
    return httpx.Response(
        200,
        content=content.encode("utf-8"),
        headers={"Content-Type": content_type},
        request=httpx.Request("GET", url),
    )


def _err(url: str, status: int) -> httpx.Response:
    return httpx.Response(
        status,
        content=b"error",
        request=httpx.Request("GET", url),
    )


def _default_handler(sitemap_xml: str = _SITEMAP_XML, job_htmls: dict = _JOB_HTMLS):
    async def handler(url: str, kw: dict) -> httpx.Response:
        if "sitemap" in url:
            return _ok(url, sitemap_xml, "application/xml")
        # Extract job_id from URL
        from urllib.parse import parse_qs, urlparse

        qs = parse_qs(urlparse(url).query)
        jid = (qs.get("job_id") or [""])[0]
        if jid in job_htmls:
            return _ok(url, job_htmls[jid])
        return _err(url, 404)

    return handler


# ── Registration ──────────────────────────────────────────────────────────────


def test_toss_careers_registered():
    assert "TOSS_CAREERS" in _CUSTOM_REGISTRY_BY_KEY


def test_toss_careers_is_parser_instance():
    assert isinstance(_CUSTOM_REGISTRY_BY_KEY["TOSS_CAREERS"], TossCareersParser)


def test_regression_greenhouse_naver_unaffected():
    import app.adapters.official.greenhouse  # noqa: F401
    import app.adapters.official.naver_careers  # noqa: F401

    assert "GREENHOUSE" in _CUSTOM_REGISTRY_BY_KEY
    assert "DAANGN_CAREERS" in _CUSTOM_REGISTRY_BY_KEY
    assert "NAVER_CAREERS" in _CUSTOM_REGISTRY_BY_KEY


# ── _parse_config ─────────────────────────────────────────────────────────────


def test_parse_config_defaults():
    cfg = _parse_config(None)
    assert cfg.max_discover == 500
    assert cfg.max_items == 50


def test_parse_config_explicit():
    cfg = _parse_config(
        '{"parser_key":"TOSS_CAREERS","max_discover":300,"max_items":30}'
    )
    assert cfg.max_discover == 300
    assert cfg.max_items == 30


def test_parse_config_max_items_capped_by_max_discover():
    # max_items > max_discover → clamp to max_discover
    cfg = _parse_config('{"max_discover":20,"max_items":100}')
    assert cfg.max_items == 20


def test_parse_config_invalid_json_uses_defaults():
    cfg = _parse_config("not-json")
    assert cfg.max_discover == 500
    assert cfg.max_items == 50


# ── _parse_sitemap ────────────────────────────────────────────────────────────


def test_parse_sitemap_extracts_job_ids():
    ids = _parse_sitemap(_SITEMAP_XML)
    assert "1001001003" in ids
    assert "1008001003" in ids


def test_parse_sitemap_deduplicates():
    # sitemap.xml has job_id=1001001003 twice
    ids = _parse_sitemap(_SITEMAP_XML)
    assert ids.count("1001001003") == 1


def test_parse_sitemap_excludes_non_job_urls():
    ids = _parse_sitemap(_SITEMAP_XML)
    # /career/jobs, /career/culture, /career/faq have no job_id
    for jid in ids:
        assert jid.isdigit()


def test_parse_sitemap_total_unique_count():
    ids = _parse_sitemap(_SITEMAP_XML)
    assert len(ids) == 8  # 9 entries - 1 duplicate = 8 unique


def test_parse_sitemap_preserves_order():
    ids = _parse_sitemap(_SITEMAP_XML)
    assert ids[0] == "1001001003"
    assert ids[-1] == "1008001003"


def test_parse_sitemap_empty():
    xml = '<?xml version="1.0"?><urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9"></urlset>'
    assert _parse_sitemap(xml) == []


def test_parse_sitemap_malformed_raises():
    with pytest.raises(ValueError, match="malformed sitemap XML"):
        _parse_sitemap("<this is not xml")


def test_parse_sitemap_no_job_urls():
    xml = (
        '<?xml version="1.0"?>'
        '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">'
        "<url><loc>https://toss.im/career/jobs</loc></url>"
        "</urlset>"
    )
    assert _parse_sitemap(xml) == []


# ── _parse_job_detail_html ────────────────────────────────────────────────────


def test_parse_job_detail_html_server_dev():
    job = _parse_job_detail_html(_JOB_HTMLS["1001001003"])
    assert job is not None
    assert job["id"] == 1001001003
    assert job["title"] == "Server Developer (여신)"
    assert job["company"] == "토스뱅크"
    assert job["mainCategory"] == "Engineering"
    assert job["subCategory"] == "Backend"


def test_parse_job_detail_html_missing_next_data():
    result = _parse_job_detail_html("<html><body>no script here</body></html>")
    assert result is None


def test_parse_job_detail_html_missing_job_detail_query():
    # __NEXT_DATA__ exists but no job-detail query
    next_data = {
        "props": {
            "pageProps": {
                "prefetchResult": {
                    "dehydratedState": {
                        "mutations": [],
                        "queries": [
                            {
                                "queryKey": ["@tossteam/iso-resource", "other"],
                                "state": {"data": "{}"},
                            }
                        ],
                    }
                }
            }
        },
        "page": "/career/job-detail",
        "query": {},
        "buildId": "x",
        "assetPrefix": "",
        "runtimeConfig": {},
        "isFallback": False,
        "customServer": False,
        "gip": True,
        "appGip": True,
        "scriptLoader": [],
    }
    payload = json.dumps(next_data)
    html = (
        f'<html><body><script id="__NEXT_DATA__" type="application/json">'
        f"{payload}</script></body></html>"
    )
    assert _parse_job_detail_html(html) is None


# ── _extract_experience ───────────────────────────────────────────────────────


def test_extract_experience_3yr_min():
    desc = (
        "# 이런 분과 함께하고 싶어요\n"
        "- 서버 개발 경력 3년 이상에 준하는 실력을 보유한 분이 필요해요."
    )
    assert _extract_experience(desc, "정규직", "Server Developer") == "경력 3년 이상"


def test_extract_experience_intern_from_employment_type():
    assert _extract_experience("# 이런 분과", "인턴", "인턴십") == "인턴"


def test_extract_experience_intern_from_title():
    title = "Server Developer 채용 연계형 인턴십"
    result = _extract_experience("# 이런 분과\n- 신입 환영", "정규직", title)
    assert result == "인턴"


def test_extract_experience_shinip_or_3yr():
    desc = "# 지원 자격을 꼭 확인해주세요.\n- 신입 또는 3년 미만의 회사 경력이 있는 분"
    assert _extract_experience(desc, "인턴", "인턴십") == "인턴"


def test_extract_experience_gyeongryeok_mungwan():
    desc = "# 이런 분과 함께하고 싶어요\n- 경력 무관, 성장하고 싶은 분이면 좋아요."
    assert _extract_experience(desc, "정규직", "Operations Specialist") == "경력 무관"


def test_extract_experience_range_tilde():
    desc = (
        "# 이런 분과 함께하고 싶어요\n"
        "- 모바일 UX 디자인 경력 2~3년 정도의 업무 경험이 있으시면 좋아요."
    )
    assert _extract_experience(desc, "계약직", "Design Partner") == "경력 2~3년"


def test_extract_experience_range_korean_syntax():
    desc = (
        "# 이런 분과 함께하고 싶어요\n"
        "- 1년 이상 5년 이하의 보험업계 수수료 관련 업무 경험이 있는 분을 찾고 있어요."
    )
    assert _extract_experience(desc, "정규직", "Commission Manager") == "경력 1~5년"


def test_extract_experience_preferred_not_extracted():
    desc = (
        "# 이런 분과 함께하고 싶어요\n"
        "- Android 개발 경력 3년 이상에 준하는 실력을 보유한 분이면 좋아요.\n"
        "# 이런 경험이 있다면 더 좋아요\n"
        "- 성능 최적화 경험 10년 이상이면 더 좋아요 (우대)."
    )
    # Should match 3년 이상 from required, NOT 10년 이상 from preferred
    assert _extract_experience(desc, "정규직", "Android Developer") == "경력 3년 이상"


def test_extract_experience_no_year_in_required():
    desc = (
        "# 이런 분과 함께하고 싶어요\n"
        "- 상담 업무 관련 팀 리드 또는 시니어 경험이 있는 분이 필요해요."
    )
    assert _extract_experience(desc, "정규직", "상담팀 리드") == "경력"


def test_extract_experience_no_required_section():
    desc = "# 합류하면 함께 할 업무예요\n- 서버 개발해요."
    assert _extract_experience(desc, "정규직", "Server Developer") is None


# ── _build_roles ──────────────────────────────────────────────────────────────


def test_build_roles_main_and_sub():
    assert _build_roles("Engineering", "Backend") == ["Engineering", "Backend"]


def test_build_roles_dedup_when_same():
    assert _build_roles("Backend", "Backend") == ["Backend"]


def test_build_roles_none_main_category():
    assert _build_roles(None, "Customer Support") == ["Customer Support"]


def test_build_roles_both_none():
    assert _build_roles(None, None) == []


def test_build_roles_empty_string_ignored():
    assert _build_roles("", "Backend") == ["Backend"]


# ── _parse_deadline ───────────────────────────────────────────────────────────


def test_parse_deadline_valid():
    assert _parse_deadline("2026-08-31", None) == date(2026, 8, 31)


def test_parse_deadline_empty_string():
    assert _parse_deadline("", None) is None


def test_parse_deadline_none():
    assert _parse_deadline(None, None) is None


def test_parse_deadline_invalid_string():
    assert _parse_deadline("not-a-date", None) is None


# ── _job_to_posting ───────────────────────────────────────────────────────────


def test_job_to_posting_server_dev():
    job = _parse_job_detail_html(_JOB_HTMLS["1001001003"])
    warnings: list[str] = []
    posting = _job_to_posting(
        job, sid="company_9_custom_toss_careers", profile=_profile(), warnings=warnings
    )
    assert posting is not None
    assert posting.title == "Server Developer (여신)"
    assert posting.company_name == "토스뱅크"
    assert posting.employment_type == "정규직"
    assert "Engineering" in posting.roles
    assert "Backend" in posting.roles
    assert posting.location == "서울"
    assert posting.deadline is None
    assert posting.experience_level == "경력 3년 이상"
    assert posting.source_external_id == "1001001003"
    assert "1001001003" in posting.source_url
    assert warnings == []


def test_job_to_posting_intern():
    job = _parse_job_detail_html(_JOB_HTMLS["1002001003"])
    warnings: list[str] = []
    posting = _job_to_posting(
        job, sid="company_9_custom_toss_careers", profile=_profile(), warnings=warnings
    )
    assert posting is not None
    assert posting.employment_type == "인턴"
    assert posting.experience_level == "인턴"
    assert posting.deadline == date(2026, 8, 31)


def test_job_to_posting_contract_range():
    job = _parse_job_detail_html(_JOB_HTMLS["1003001003"])
    warnings: list[str] = []
    posting = _job_to_posting(
        job, sid="company_9_custom_toss_careers", profile=_profile(), warnings=warnings
    )
    assert posting is not None
    assert posting.employment_type == "계약직"
    assert posting.experience_level == "경력 2~3년"
    assert posting.deadline == date(2026, 9, 30)
    assert posting.location == "서울"  # 'seoul' → '서울'


def test_job_to_posting_gyeongryeok_mungwan():
    job = _parse_job_detail_html(_JOB_HTMLS["1004001003"])
    warnings: list[str] = []
    posting = _job_to_posting(
        job, sid="company_9_custom_toss_careers", profile=_profile(), warnings=warnings
    )
    assert posting is not None
    assert posting.company_name == "토스페이먼츠"
    assert posting.experience_level == "경력 무관"
    assert posting.deadline is None


def test_job_to_posting_toss_insurance_range():
    job = _parse_job_detail_html(_JOB_HTMLS["1005001003"])
    warnings: list[str] = []
    posting = _job_to_posting(
        job, sid="company_9_custom_toss_careers", profile=_profile(), warnings=warnings
    )
    assert posting is not None
    assert posting.company_name == "토스인슈어런스"
    assert posting.experience_level == "경력 1~5년"


def test_job_to_posting_no_main_category():
    job = _parse_job_detail_html(_JOB_HTMLS["1006001003"])
    warnings: list[str] = []
    posting = _job_to_posting(
        job, sid="company_9_custom_toss_careers", profile=_profile(), warnings=warnings
    )
    assert posting is not None
    assert posting.roles == ["Customer Support"]  # no mainCategory → only subCategory


def test_job_to_posting_short_term_contract_maps_to_gyeyakjik():
    job = _parse_job_detail_html(_JOB_HTMLS["1008001003"])
    warnings: list[str] = []
    posting = _job_to_posting(
        job, sid="company_9_custom_toss_careers", profile=_profile(), warnings=warnings
    )
    assert posting is not None
    assert posting.employment_type == "계약직"  # 단기계약직 → 계약직


def test_job_to_posting_missing_id_returns_none():
    warnings: list[str] = []
    result = _job_to_posting(
        {"title": "Some Job"}, sid="sid", profile=None, warnings=warnings
    )
    assert result is None
    assert any("missing id" in w for w in warnings)


def test_job_to_posting_missing_title_returns_none():
    warnings: list[str] = []
    result = _job_to_posting(
        {"id": 9999, "title": ""}, sid="sid", profile=None, warnings=warnings
    )
    assert result is None
    assert any("empty title" in w for w in warnings)


# ── source_record_key stability ────────────────────────────────────────────────


def test_source_record_key_stable():
    sid = "company_9_custom_toss_careers"
    url = "https://toss.im/career/job-detail?job_id=1001001003"
    k1 = compute_source_record_key(sid, "1001001003", url)
    k2 = compute_source_record_key(sid, "1001001003", url)
    assert k1 == k2


def test_source_record_key_unique_per_job():
    sid = "company_9_custom_toss_careers"
    url1 = "https://toss.im/career/job-detail?job_id=1001001003"
    url2 = "https://toss.im/career/job-detail?job_id=1002001003"
    k1 = compute_source_record_key(sid, "1001001003", url1)
    k2 = compute_source_record_key(sid, "1002001003", url2)
    assert k1 != k2


# ── Adapter integration tests ─────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_full_fetch_all_jobs(monkeypatch):
    """Sitemap has 8 unique jobs (after dedup) → 8 postings."""
    mock = _MockClient(_default_handler())
    _target = "app.adapters.official.toss_careers.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    result = await TossCareersParser().fetch(
        _source(), _profile(), _options(), _COLLECT_DATE
    )

    assert result.source_stats.discovered == 8
    assert len(result.postings) == 8
    assert result.warnings == []


@pytest.mark.asyncio
async def test_server_dev_fields(monkeypatch):
    """Server Developer → 경력 3년 이상, Engineering/Backend, 토스뱅크."""

    async def handler(url, kw):
        if "sitemap" in url:
            xml = (
                '<?xml version="1.0"?>'
                '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">'
                "<url><loc>https://toss.im/career/job-detail?job_id=1001001003</loc></url>"
                "</urlset>"
            )
            return _ok(url, xml, "application/xml")
        return _ok(url, _JOB_HTMLS["1001001003"])

    mock = _MockClient(handler)
    _target = "app.adapters.official.toss_careers.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    result = await TossCareersParser().fetch(
        _source(), _profile(), _options(), _COLLECT_DATE
    )

    assert len(result.postings) == 1
    p = result.postings[0]
    assert p.company_name == "토스뱅크"
    assert p.employment_type == "정규직"
    assert p.experience_level == "경력 3년 이상"
    assert "Engineering" in p.roles
    assert "Backend" in p.roles
    assert p.location == "서울"
    assert p.deadline is None


@pytest.mark.asyncio
async def test_intern_fields(monkeypatch):
    """Intern job → 인턴, 마감일, roles."""

    async def handler(url, kw):
        if "sitemap" in url:
            xml = (
                '<?xml version="1.0"?>'
                '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">'
                "<url><loc>https://toss.im/career/job-detail?job_id=1002001003</loc></url>"
                "</urlset>"
            )
            return _ok(url, xml, "application/xml")
        return _ok(url, _JOB_HTMLS["1002001003"])

    mock = _MockClient(handler)
    _target = "app.adapters.official.toss_careers.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    result = await TossCareersParser().fetch(
        _source(), _profile(), _options(), _COLLECT_DATE
    )

    p = result.postings[0]
    assert p.employment_type == "인턴"
    assert p.experience_level == "인턴"
    assert p.deadline == date(2026, 8, 31)
    assert "Engineering" in p.roles
    assert "Backend" in p.roles


@pytest.mark.asyncio
async def test_max_items_cap(monkeypatch):
    """max_items=3 → only 3 jobs fetched despite 8 in sitemap."""
    mock = _MockClient(_default_handler())
    _target = "app.adapters.official.toss_careers.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    cfg = json.dumps({"parser_key": "TOSS_CAREERS", "max_items": 3})
    result = await TossCareersParser().fetch(
        _source(cfg), _profile(), _options(), _COLLECT_DATE
    )

    # Discovered = 8 (all sitemap entries), but only 3 fetched
    assert result.source_stats.discovered == 8
    assert len(result.postings) == 3


@pytest.mark.asyncio
async def test_sitemap_http_error(monkeypatch):
    async def handler(url, kw):
        return _err(url, 503)

    mock = _MockClient(handler)
    _target = "app.adapters.official.toss_careers.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    result = await TossCareersParser().fetch(
        _source(), _profile(), _options(), _COLLECT_DATE
    )

    assert result.postings == []
    assert any("HTTP 503" in w for w in result.warnings)


@pytest.mark.asyncio
async def test_sitemap_timeout(monkeypatch):
    async def handler(url, kw):
        raise httpx.TimeoutException("timed out")

    mock = _MockClient(handler)
    _target = "app.adapters.official.toss_careers.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    result = await TossCareersParser().fetch(
        _source(), _profile(), _options(), _COLLECT_DATE
    )

    assert result.postings == []
    assert any("timeout" in w.lower() for w in result.warnings)


@pytest.mark.asyncio
async def test_sitemap_malformed_xml(monkeypatch):
    async def handler(url, kw):
        return _ok(url, "<not valid xml", "application/xml")

    mock = _MockClient(handler)
    _target = "app.adapters.official.toss_careers.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    result = await TossCareersParser().fetch(
        _source(), _profile(), _options(), _COLLECT_DATE
    )

    assert result.postings == []
    assert any("malformed" in w.lower() for w in result.warnings)


@pytest.mark.asyncio
async def test_sitemap_genuine_empty(monkeypatch):
    """Empty sitemap (0 job URLs) → 0 postings, no warnings."""

    async def handler(url, kw):
        xml = (
            '<?xml version="1.0"?>'
            '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">'
            "<url><loc>https://toss.im/career/jobs</loc></url>"
            "</urlset>"
        )
        return _ok(url, xml, "application/xml")

    mock = _MockClient(handler)
    _target = "app.adapters.official.toss_careers.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    result = await TossCareersParser().fetch(
        _source(), _profile(), _options(), _COLLECT_DATE
    )

    assert result.postings == []
    assert result.warnings == []


@pytest.mark.asyncio
async def test_detail_http_error_skips_job(monkeypatch):
    """HTTP error on one detail page → warning for that job, other jobs proceed."""

    async def handler(url, kw):
        if "sitemap" in url:
            return _ok(url, _SITEMAP_XML, "application/xml")
        from urllib.parse import parse_qs, urlparse

        jid = (parse_qs(urlparse(url).query).get("job_id") or [""])[0]
        if jid == "1001001003":
            return _err(url, 500)
        if jid in _JOB_HTMLS:
            return _ok(url, _JOB_HTMLS[jid])
        return _err(url, 404)

    mock = _MockClient(handler)
    _target = "app.adapters.official.toss_careers.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    result = await TossCareersParser().fetch(
        _source(), _profile(), _options(), _COLLECT_DATE
    )

    # 7 postings instead of 8
    assert len(result.postings) == 7
    assert any("HTTP 500" in w for w in result.warnings)


@pytest.mark.asyncio
async def test_detail_missing_next_data_skips_job(monkeypatch):
    """Detail page without __NEXT_DATA__ → warning, skip, others proceed."""

    async def handler(url, kw):
        if "sitemap" in url:
            xml = (
                '<?xml version="1.0"?>'
                '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">'
                "<url><loc>https://toss.im/career/job-detail?job_id=1001001003</loc></url>"
                "<url><loc>https://toss.im/career/job-detail?job_id=1007001003</loc></url>"
                "</urlset>"
            )
            return _ok(url, xml, "application/xml")
        from urllib.parse import parse_qs, urlparse

        jid = (parse_qs(urlparse(url).query).get("job_id") or [""])[0]
        if jid == "1001001003":
            return _ok(url, "<html><body>no script</body></html>")
        if jid in _JOB_HTMLS:
            return _ok(url, _JOB_HTMLS[jid])
        return _err(url, 404)

    mock = _MockClient(handler)
    _target = "app.adapters.official.toss_careers.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    result = await TossCareersParser().fetch(
        _source(), _profile(), _options(), _COLLECT_DATE
    )

    assert len(result.postings) == 1
    assert any("__NEXT_DATA__" in w or "job-detail" in w for w in result.warnings)


@pytest.mark.asyncio
async def test_detail_timeout_skips_job(monkeypatch):
    """Timeout on one detail → warning, skip, other jobs proceed."""

    async def handler(url, kw):
        if "sitemap" in url:
            xml = (
                '<?xml version="1.0"?>'
                '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">'
                "<url><loc>https://toss.im/career/job-detail?job_id=1001001003</loc></url>"
                "<url><loc>https://toss.im/career/job-detail?job_id=1007001003</loc></url>"
                "</urlset>"
            )
            return _ok(url, xml, "application/xml")
        from urllib.parse import parse_qs, urlparse

        jid = (parse_qs(urlparse(url).query).get("job_id") or [""])[0]
        if jid == "1001001003":
            raise httpx.TimeoutException("timed out")
        if jid in _JOB_HTMLS:
            return _ok(url, _JOB_HTMLS[jid])
        return _err(url, 404)

    mock = _MockClient(handler)
    _target = "app.adapters.official.toss_careers.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    result = await TossCareersParser().fetch(
        _source(), _profile(), _options(), _COLLECT_DATE
    )

    assert len(result.postings) == 1
    assert any("timeout" in w.lower() for w in result.warnings)


@pytest.mark.asyncio
async def test_android_preferred_not_extracted(monkeypatch):
    """Android job: 3년 이상 from required, 10년 이상 from preferred → 경력 3년 이상."""

    async def handler(url, kw):
        if "sitemap" in url:
            xml = (
                '<?xml version="1.0"?>'
                '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">'
                "<url><loc>https://toss.im/career/job-detail?job_id=1007001003</loc></url>"
                "</urlset>"
            )
            return _ok(url, xml, "application/xml")
        return _ok(url, _JOB_HTMLS["1007001003"])

    mock = _MockClient(handler)
    _target = "app.adapters.official.toss_careers.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    result = await TossCareersParser().fetch(
        _source(), _profile(), _options(), _COLLECT_DATE
    )

    p = result.postings[0]
    assert p.experience_level == "경력 3년 이상"
    assert p.company_name == "토스"
