"""Tests for NaverCareersParser.

All tests are offline — no real network calls.

Coverage:
  Pure-function tests:
    - _parse_config: defaults, custom, invalid JSON
    - _parse_roles: dedup, blank, identical class/sub
    - _parse_list_item: normal, missing fields, malformed, edge cases
    - _parse_detail_page: experience enrichment, description, employment type
    - _extract_experience_from_required_text: year patterns
    - workAreaCd → location mapping
  Adapter tests (mock httpx.AsyncClient via _MockClient):
    - Pagination: two pages, totalSize termination
    - max_discover cap stops pagination early
    - 0 results: genuine empty
    - Malformed JSON → warning, 0 postings
    - HTTP error → warning
    - Timeout (list) → warning
    - Timeout (detail) → posting preserved without description
    - Malformed list item → skipped with warning, other items kept
    - Tech + sub-role both in roles
    - Detail enriches experience (경력 → 3년 이상)
    - 경력 무관 experience level
    - NAVER Cloud company_name preserved (not collapsed to NAVER)
    - workAreaCd 0030 → 춘천
    - No jobDetailLink → constructed URL used
  Normalization integration:
    - Tech+Backend roles, employment_type, experience_level
    - 인턴 detected in title → employment_type="인턴"
    - 경력 무관 preserved through normalization
  source_record_key stability
  Regression:
    - NAVER_CAREERS registered in _CUSTOM_REGISTRY_BY_KEY
    - GREETING and JASOSEOL still importable/registered
"""

import json
from datetime import date
from pathlib import Path

import httpx
import pytest

import app.adapters.official.naver_careers  # noqa: F401 — ensure module is imported
from app.adapters.official.naver_careers import (
    _WORK_AREA_MAP,
    NaverCareersParser,
    _extract_experience_from_required_text,
    _parse_config,
    _parse_detail_page,
    _parse_list_item,
    _parse_roles,
)
from app.adapters.official_company import _CUSTOM_REGISTRY_BY_KEY
from app.core.identifiers import compute_source_record_key
from app.schemas.collection import (
    CollectionOptions,
    CompanyProfile,
    OfficialCompanySource,
)
from app.services.normalization import normalize

_FIXTURES = Path(__file__).parent / "fixtures" / "naver"
_COLLECT_DATE = date(2026, 8, 15)
_COMPANY_ID = 1


def _read(name: str) -> str:
    return (_FIXTURES / name).read_text(encoding="utf-8")


def _read_json(name: str) -> dict:
    return json.loads((_FIXTURES / name).read_text(encoding="utf-8"))


def _source(config_json: str | None = None) -> OfficialCompanySource:
    return OfficialCompanySource(
        company_id=_COMPANY_ID,
        source_type="OFFICIAL_CAREER",
        source_url="https://recruit.navercorp.com/rcrt/list.do",
        adapter_type="CUSTOM",
        config_json=config_json or json.dumps(
            {"parser_key": "NAVER_CAREERS", "max_discover": 50, "max_fetch": 20}
        ),
    )


def _profile() -> CompanyProfile:
    return CompanyProfile(
        id=_COMPANY_ID,
        canonical_name="네이버",
        normalized_name="네이버",
    )


def _options() -> CollectionOptions:
    return CollectionOptions()


# ── Mock HTTP client ──────────────────────────────────────────────────────────

class _MockClient:
    """URL-dispatching mock for httpx.AsyncClient.

    Pass an async handler(url, kwargs) → httpx.Response.
    The handler may raise httpx exceptions directly.
    """

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


def _json_resp(url: str, data: dict, status: int = 200) -> httpx.Response:
    return httpx.Response(
        status,
        content=json.dumps(data).encode(),
        headers={"Content-Type": "application/json"},
        request=httpx.Request("GET", url),
    )


def _html_resp(url: str, html: str, status: int = 200) -> httpx.Response:
    return httpx.Response(
        status,
        content=html.encode("utf-8"),
        headers={"Content-Type": "text/html; charset=utf-8"},
        request=httpx.Request("GET", url),
    )


# Shared test data
_PAGE1 = _read_json("list_page1.json")
_PAGE2 = _read_json("list_page2.json")
_DETAIL_GENERIC = _read("detail_generic.html")
_DETAIL_3YR = _read("detail_backend_3yr.html")

_VALID_ITEM = {
    "annoId": 30000001,
    "sysCompanyCdNm": "NAVER",
    "annoSubject": "[NAVER] Backend 서비스 개발 (경력)",
    "classCdNm": "Tech",
    "subJobCdNm": "Backend",
    "entTypeCd": "0020",
    "entTypeCdNm": "경력",
    "empTypeCdNm": "정규",
    "workAreaCd": "0010",
    "staYmd": "20260801",
    "endYmd": "20260831",
    "staYmdTime": "2026.08.01 09:00:00",
    "endYmdTime": "2026.08.31 18:00:00",
    "stateCd": "0040",
    "stateCdNm": "채용진행중",
    "jobDetailLink": "https://recruit.navercorp.com/rcrt/view.do?annoId=30000001",
}


# ── Registration tests ─────────────────────────────────────────────────────────


def test_naver_careers_registered():
    assert "NAVER_CAREERS" in _CUSTOM_REGISTRY_BY_KEY


def test_greeting_still_registered():
    import app.adapters.official.greeting  # noqa: F401
    assert "GREETING" in _CUSTOM_REGISTRY_BY_KEY


def test_greeting_and_jasoseol_importable():
    import app.adapters.aggregators.jasoseol  # noqa: F401
    import app.adapters.official.greeting  # noqa: F401


# ── _parse_config ─────────────────────────────────────────────────────────────


def test_parse_config_defaults():
    cfg = _parse_config(None)
    assert cfg.max_discover == 50
    assert cfg.max_fetch == 20


def test_parse_config_custom():
    cfg = _parse_config(
        '{"parser_key":"NAVER_CAREERS","max_discover":30,"max_fetch":10}'
    )
    assert cfg.max_discover == 30
    assert cfg.max_fetch == 10


def test_parse_config_invalid_json():
    cfg = _parse_config("not-json")
    assert cfg.max_discover == 50


# ── _parse_roles ──────────────────────────────────────────────────────────────


def test_parse_roles_both_distinct():
    assert _parse_roles("Tech", "Backend") == ["Tech", "Backend"]


def test_parse_roles_same_deduped():
    assert _parse_roles("Tech", "Tech") == ["Tech"]


def test_parse_roles_empty_sub():
    assert _parse_roles("Tech", "") == ["Tech"]


def test_parse_roles_both_empty():
    assert _parse_roles(None, None) == []


def test_parse_roles_design():
    assert _parse_roles("Design", "Product Design") == ["Design", "Product Design"]


def test_parse_roles_service_business():
    roles = _parse_roles("Service & Business", "Product Development")
    assert roles == ["Service & Business", "Product Development"]


def test_parse_roles_corporate():
    roles = _parse_roles("Corporate", "투자")
    assert roles == ["Corporate", "투자"]


# ── workAreaCd mapping ────────────────────────────────────────────────────────


def test_work_area_0010_is_bundang():
    assert _WORK_AREA_MAP["0010"] == "분당"


def test_work_area_0020_is_seoul():
    assert _WORK_AREA_MAP["0020"] == "서울"


def test_work_area_0030_is_chuncheon():
    assert _WORK_AREA_MAP["0030"] == "춘천"


def test_work_area_unknown_returns_none():
    assert _WORK_AREA_MAP.get("9999") is None


# ── _parse_list_item ──────────────────────────────────────────────────────────


def test_parse_list_item_normal():
    item = _parse_list_item(_VALID_ITEM)
    assert item is not None
    assert item.anno_id == 30000001
    assert item.title == "[NAVER] Backend 서비스 개발 (경력)"
    assert item.company_name == "NAVER"
    assert item.roles == ["Tech", "Backend"]
    assert item.experience_level == "경력"
    assert item.employment_type == "정규직"
    assert item.location == "분당"
    assert item.deadline == date(2026, 8, 31)
    assert item.detail_url == "https://recruit.navercorp.com/rcrt/view.do?annoId=30000001"


def test_parse_list_item_shinip():
    item = _parse_list_item({**_VALID_ITEM, "entTypeCd": "0010", "entTypeCdNm": "신입"})
    assert item.experience_level == "신입"


def test_parse_list_item_moogwan():
    item = _parse_list_item({**_VALID_ITEM, "entTypeCd": "0030", "entTypeCdNm": "무관"})
    assert item.experience_level == "경력 무관"


def test_parse_list_item_contract():
    item = _parse_list_item({**_VALID_ITEM, "empTypeCdNm": "계약"})
    assert item.employment_type == "계약직"


def test_parse_list_item_location_seoul():
    item = _parse_list_item({**_VALID_ITEM, "workAreaCd": "0020"})
    assert item.location == "서울"


def test_parse_list_item_unknown_workarea():
    item = _parse_list_item({**_VALID_ITEM, "workAreaCd": "9999"})
    assert item.location is None


def test_parse_list_item_naver_webtoon():
    item = _parse_list_item({**_VALID_ITEM, "sysCompanyCdNm": "NAVER WEBTOON"})
    assert item.company_name == "NAVER WEBTOON"


def test_parse_list_item_no_job_detail_link():
    item = _parse_list_item({**_VALID_ITEM, "jobDetailLink": ""})
    assert item is not None
    assert "30000001" in item.detail_url
    assert item.detail_url.startswith("https://recruit.navercorp.com")


def test_parse_list_item_missing_title_returns_none():
    item = _parse_list_item({**_VALID_ITEM, "annoSubject": ""})
    assert item is None


def test_parse_list_item_missing_anno_id_returns_none():
    bad = {k: v for k, v in _VALID_ITEM.items() if k != "annoId"}
    assert _parse_list_item(bad) is None


def test_parse_list_item_date_from_ymd_fallback():
    item = _parse_list_item({**_VALID_ITEM, "endYmdTime": "", "endYmd": "20260930"})
    assert item.deadline == date(2026, 9, 30)


def test_parse_list_item_roles_same_deduped():
    item = _parse_list_item({**_VALID_ITEM, "classCdNm": "Tech", "subJobCdNm": "Tech"})
    assert item.roles == ["Tech"]


# ── _extract_experience_from_required_text ────────────────────────────────────


def test_exp_extract_min_years():
    result = _extract_experience_from_required_text("백엔드 개발 경력 3년 이상")
    assert result == "3년 이상"


def test_exp_extract_range():
    assert _extract_experience_from_required_text("경력 3~5년 보유자") == "3~5년"


def test_exp_extract_with_space():
    result = _extract_experience_from_required_text("Java 개발 경력 5 년 이상")
    assert result == "5년 이상"


def test_exp_extract_no_pattern():
    assert _extract_experience_from_required_text("Java, Spring 개발 경험") is None


# ── _parse_detail_page ────────────────────────────────────────────────────────


def test_detail_3yr_enriches_experience():
    result = _parse_detail_page(_DETAIL_3YR)
    assert result.experience_level == "3년 이상"


def test_detail_preferred_does_not_override_required():
    """Required Skills 3년 이상 → 3년 이상 (Preferred Skills 5년 이상 is excluded)."""
    result = _parse_detail_page(_DETAIL_3YR)
    assert result.experience_level == "3년 이상"


def test_detail_description_extracted():
    result = _parse_detail_page(_DETAIL_GENERIC)
    assert result.description is not None
    assert len(result.description) > 20


def test_detail_employment_type_extracted():
    result = _parse_detail_page(_DETAIL_GENERIC)
    assert result.employment_type == "정규직"


def test_detail_moogwan_experience():
    html = """<html><body>
    <dl class="card_info">
      <dt>모집 경력</dt><dd class="info_text">무관</dd>
    </dl>
    <div class="detail_wrap">
      <div class="detail_box"><h3>Required Skills</h3><p>열정 있는 분</p></div>
    </div></body></html>"""
    result = _parse_detail_page(html)
    assert result.experience_level == "경력 무관"


def test_detail_no_detail_wrap():
    """No detail_wrap → description is None, no crash."""
    html = """<html><body>
    <dl class="card_info">
      <dt>모집 경력</dt><dd class="info_text">경력</dd>
    </dl>
    </body></html>"""
    result = _parse_detail_page(html)
    assert result.experience_level == "경력"
    assert result.description is None


def test_detail_description_capped_at_2000():
    long_content = "A" * 5000
    html = f"""<html><body>
    <div class="detail_wrap">
      <div class="detail_box"><h3>What You'll Do</h3><p>{long_content}</p></div>
    </div></body></html>"""
    result = _parse_detail_page(html)
    assert result.description is not None
    assert len(result.description) <= 2000


# ── source_record_key stability ────────────────────────────────────────────────


def test_source_record_key_stable_for_same_anno_id():
    sid = "company_1_custom_naver_careers"
    url = "https://recruit.navercorp.com/rcrt/view.do?annoId=30005280"
    key1 = compute_source_record_key(sid, "30005280", url)
    key2 = compute_source_record_key(sid, "30005280", url)
    assert key1 == key2


def test_source_record_key_different_for_different_anno_ids():
    sid = "company_1_custom_naver_careers"
    url1 = "https://recruit.navercorp.com/rcrt/view.do?annoId=30005280"
    url2 = "https://recruit.navercorp.com/rcrt/view.do?annoId=30005281"
    key1 = compute_source_record_key(sid, "30005280", url1)
    key2 = compute_source_record_key(sid, "30005281", url2)
    assert key1 != key2


# ── Normalization pipeline integration ────────────────────────────────────────


def test_normalize_naver_tech_backend():
    from app.adapters.base import RawJobPosting
    raw = RawJobPosting(
        source="company_1_custom_naver_careers",
        source_url="https://recruit.navercorp.com/rcrt/view.do?annoId=30000001",
        company_name="NAVER",
        title="[NAVER] Backend 서비스 개발 (경력)",
        employment_type="정규직",
        experience_level="경력",
        location="분당",
        deadline=date(2026, 8, 31),
        roles=["Tech", "Backend"],
        skills=[],
        source_external_id="30000001",
    )
    collected = normalize(raw)
    assert collected.roles == ["Tech", "Backend"]
    assert collected.experience_level == "경력"
    assert collected.employment_type == "정규직"
    assert collected.source_external_id == "30000001"
    assert collected.source_record_key is not None
    assert collected.canonical_fingerprint is not None


def test_normalize_intern_from_title():
    """Title contains 인턴 → employment_type becomes 인턴 via normalization."""
    from app.adapters.base import RawJobPosting
    raw = RawJobPosting(
        source="company_1_custom_naver_careers",
        source_url="https://recruit.navercorp.com/rcrt/view.do?annoId=30000006",
        company_name="NAVER WEBTOON",
        title="[네이버웹툰] Product Manager (체험형 인턴)",
        employment_type="계약직",
        experience_level="신입",
        roles=["Service & Business", "Product Development"],
        skills=[],
        source_external_id="30000006",
    )
    collected = normalize(raw)
    assert collected.employment_type == "인턴"


def test_normalize_moogwan():
    from app.adapters.base import RawJobPosting
    raw = RawJobPosting(
        source="company_1_custom_naver_careers",
        source_url="https://recruit.navercorp.com/rcrt/view.do?annoId=30000003",
        company_name="NAVER",
        title="[NAVER] 경력 무관 - Data Engineering",
        employment_type="정규직",
        experience_level="경력 무관",
        roles=["Tech", "Data Engineering"],
        skills=[],
        source_external_id="30000003",
    )
    collected = normalize(raw)
    assert collected.experience_level == "경력 무관"


# ── Adapter integration tests (mock HTTP) ─────────────────────────────────────


@pytest.mark.asyncio
async def test_pagination_two_pages(monkeypatch):
    """totalSize=13 → fetches page 1 (10 items) then page 2 (3 items)."""
    list_call_count = {"n": 0}

    async def handler(url, kw):
        params = kw.get("params", {})
        if "loadJobList" in url:
            page = int(params.get("page", 1))
            list_call_count["n"] += 1
            data = _PAGE1 if page == 1 else _PAGE2
            return _json_resp(url, data)
        return _html_resp(url, _DETAIL_GENERIC)

    mock = _MockClient(handler)
    _target = "app.adapters.official.naver_careers.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    result = await NaverCareersParser().fetch(
        _source(), _profile(), _options(), _COLLECT_DATE
    )

    assert result.source_stats is not None
    assert result.source_stats.discovered == 13
    assert len(result.postings) == 13
    assert list_call_count["n"] == 2


@pytest.mark.asyncio
async def test_max_discover_caps_pagination(monkeypatch):
    """max_discover=5 → stops after first page with only 5 items."""
    list_call_count = {"n": 0}

    async def handler(url, kw):
        if "loadJobList" in url:
            list_call_count["n"] += 1
            return _json_resp(url, _PAGE1)
        return _html_resp(url, _DETAIL_GENERIC)

    mock = _MockClient(handler)
    _target = "app.adapters.official.naver_careers.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    cfg = json.dumps({"parser_key": "NAVER_CAREERS", "max_discover": 5, "max_fetch": 3})
    result = await NaverCareersParser().fetch(
        _source(cfg), _profile(), _options(), _COLLECT_DATE
    )

    assert len(result.postings) == 5
    assert list_call_count["n"] == 1


@pytest.mark.asyncio
async def test_genuine_empty_response(monkeypatch):
    """totalSize=0, list=[] → 0 postings, no error warnings."""

    async def handler(url, kw):
        return _json_resp(url, {"result": "Y", "totalSize": 0, "list": []})

    mock = _MockClient(handler)
    _target = "app.adapters.official.naver_careers.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    result = await NaverCareersParser().fetch(
        _source(), _profile(), _options(), _COLLECT_DATE
    )

    assert result.postings == []
    assert result.source_stats.discovered == 0
    assert result.warnings == []


@pytest.mark.asyncio
async def test_malformed_json_emits_warning_and_stops(monkeypatch):
    """Non-JSON body → JSON parse error warning, 0 postings."""

    async def handler(url, kw):
        return httpx.Response(
            200,
            content=b"not-json",
            headers={"Content-Type": "application/json"},
            request=httpx.Request("GET", url),
        )

    mock = _MockClient(handler)
    _target = "app.adapters.official.naver_careers.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    result = await NaverCareersParser().fetch(
        _source(), _profile(), _options(), _COLLECT_DATE
    )

    assert result.postings == []
    assert any("JSON parse error" in w for w in result.warnings)


@pytest.mark.asyncio
async def test_http_error_emits_warning(monkeypatch):
    """HTTP 500 on list page → HTTP warning, 0 postings."""

    async def handler(url, kw):
        if "loadJobList" in url:
            return _json_resp(url, {}, status=500)
        return _html_resp(url, _DETAIL_GENERIC)

    mock = _MockClient(handler)
    _target = "app.adapters.official.naver_careers.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    result = await NaverCareersParser().fetch(
        _source(), _profile(), _options(), _COLLECT_DATE
    )

    assert result.postings == []
    assert any("HTTP" in w for w in result.warnings)


@pytest.mark.asyncio
async def test_list_timeout_emits_warning(monkeypatch):
    """Timeout on list page → timeout warning, 0 postings."""

    async def handler(url, kw):
        if "loadJobList" in url:
            raise httpx.TimeoutException("timed out")
        return _html_resp(url, _DETAIL_GENERIC)

    mock = _MockClient(handler)
    _target = "app.adapters.official.naver_careers.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    result = await NaverCareersParser().fetch(
        _source(), _profile(), _options(), _COLLECT_DATE
    )

    assert result.postings == []
    assert any("timeout" in w for w in result.warnings)


@pytest.mark.asyncio
async def test_detail_timeout_keeps_stub(monkeypatch):
    """Detail page timeout → posting kept without description; warning emitted."""
    single_page = {"result": "Y", "totalSize": 1, "list": [_VALID_ITEM]}

    async def handler(url, kw):
        if "loadJobList" in url:
            return _json_resp(url, single_page)
        raise httpx.TimeoutException("detail timeout")

    mock = _MockClient(handler)
    _target = "app.adapters.official.naver_careers.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    result = await NaverCareersParser().fetch(
        _source(), _profile(), _options(), _COLLECT_DATE
    )

    assert len(result.postings) == 1
    assert result.postings[0].description is None
    assert any("detail timeout" in w for w in result.warnings)


@pytest.mark.asyncio
async def test_malformed_item_skipped_others_preserved(monkeypatch):
    """Item without annoId → skipped with warning; valid item kept."""
    bad_item = {
        "sysCompanyCdNm": "NAVER",
        "annoSubject": "[NAVER] valid job",
        "classCdNm": "Tech",
        "subJobCdNm": "Backend",
        "entTypeCd": "0020",
        "entTypeCdNm": "경력",
        "empTypeCdNm": "정규",
        "workAreaCd": "0010",
        "endYmd": "20260901",
        "staYmdTime": "2026.08.01 09:00:00",
        "endYmdTime": "2026.09.01 18:00:00",
        "stateCd": "0040",
        "stateCdNm": "채용진행중",
        "jobDetailLink": "",
        # annoId missing → _parse_list_item returns None
    }
    page = {"result": "Y", "totalSize": 2, "list": [bad_item, _VALID_ITEM]}

    async def handler(url, kw):
        if "loadJobList" in url:
            return _json_resp(url, page)
        return _html_resp(url, _DETAIL_GENERIC)

    mock = _MockClient(handler)
    _target = "app.adapters.official.naver_careers.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    result = await NaverCareersParser().fetch(
        _source(), _profile(), _options(), _COLLECT_DATE
    )

    assert len(result.postings) == 1
    assert any("malformed" in w for w in result.warnings)


@pytest.mark.asyncio
async def test_tech_backend_roles_both_stored(monkeypatch):
    """classCdNm=Tech, subJobCdNm=Backend → both in roles."""
    page = {"result": "Y", "totalSize": 1, "list": [_VALID_ITEM]}

    async def handler(url, kw):
        if "loadJobList" in url:
            return _json_resp(url, page)
        return _html_resp(url, _DETAIL_GENERIC)

    mock = _MockClient(handler)
    _target = "app.adapters.official.naver_careers.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    result = await NaverCareersParser().fetch(
        _source(), _profile(), _options(), _COLLECT_DATE
    )

    assert len(result.postings) == 1
    assert "Tech" in result.postings[0].roles
    assert "Backend" in result.postings[0].roles


@pytest.mark.asyncio
async def test_detail_enriches_experience_3yr(monkeypatch):
    """List says 경력, Required Skills on detail says 3년 이상 → 3년 이상 wins."""
    item_3yr = {
        **_VALID_ITEM,
        "annoId": 10000008,
        "entTypeCd": "0020",
        "entTypeCdNm": "경력",
        "jobDetailLink": "https://recruit.navercorp.com/rcrt/view.do?annoId=10000008",
    }
    page = {"result": "Y", "totalSize": 1, "list": [item_3yr]}

    async def handler(url, kw):
        if "loadJobList" in url:
            return _json_resp(url, page)
        return _html_resp(url, _DETAIL_3YR)

    mock = _MockClient(handler)
    _target = "app.adapters.official.naver_careers.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    result = await NaverCareersParser().fetch(
        _source(), _profile(), _options(), _COLLECT_DATE
    )

    assert len(result.postings) == 1
    assert result.postings[0].experience_level == "3년 이상"


@pytest.mark.asyncio
async def test_moogwan_experience_level(monkeypatch):
    """entTypeCd=0030 (무관) → experience_level='경력 무관'."""
    item = {**_VALID_ITEM, "entTypeCd": "0030", "entTypeCdNm": "무관"}
    page = {"result": "Y", "totalSize": 1, "list": [item]}

    async def handler(url, kw):
        if "loadJobList" in url:
            return _json_resp(url, page)
        return _html_resp(url, _DETAIL_GENERIC)

    mock = _MockClient(handler)
    _target = "app.adapters.official.naver_careers.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    result = await NaverCareersParser().fetch(
        _source(), _profile(), _options(), _COLLECT_DATE
    )

    assert result.postings[0].experience_level == "경력 무관"


@pytest.mark.asyncio
async def test_naver_cloud_subsidiary_preserved(monkeypatch):
    """NAVER Cloud company_name preserved as-is — not collapsed to NAVER."""
    item = {
        **_VALID_ITEM,
        "annoId": 10000005,
        "sysCompanyCdNm": "NAVER Cloud",
        "jobDetailLink": "https://recruit.navercorp.com/rcrt/view.do?annoId=10000005",
    }
    page = {"result": "Y", "totalSize": 1, "list": [item]}

    async def handler(url, kw):
        if "loadJobList" in url:
            return _json_resp(url, page)
        return _html_resp(url, _DETAIL_GENERIC)

    mock = _MockClient(handler)
    _target = "app.adapters.official.naver_careers.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    result = await NaverCareersParser().fetch(
        _source(), _profile(), _options(), _COLLECT_DATE
    )

    assert len(result.postings) == 1
    assert result.postings[0].company_name == "NAVER Cloud"


@pytest.mark.asyncio
async def test_chuncheon_location(monkeypatch):
    """workAreaCd=0030 → location='춘천' (not raw code '0030')."""
    item = {**_VALID_ITEM, "workAreaCd": "0030"}
    page = {"result": "Y", "totalSize": 1, "list": [item]}

    async def handler(url, kw):
        if "loadJobList" in url:
            return _json_resp(url, page)
        return _html_resp(url, _DETAIL_GENERIC)

    mock = _MockClient(handler)
    _target = "app.adapters.official.naver_careers.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    result = await NaverCareersParser().fetch(
        _source(), _profile(), _options(), _COLLECT_DATE
    )

    assert result.postings[0].location == "춘천"


@pytest.mark.asyncio
async def test_no_job_detail_link_uses_constructed_url(monkeypatch):
    """jobDetailLink absent → URL constructed from annoId, posting still returned."""
    item = {**_VALID_ITEM, "jobDetailLink": ""}
    page = {"result": "Y", "totalSize": 1, "list": [item]}

    async def handler(url, kw):
        if "loadJobList" in url:
            return _json_resp(url, page)
        return _html_resp(url, _DETAIL_GENERIC)

    mock = _MockClient(handler)
    _target = "app.adapters.official.naver_careers.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    result = await NaverCareersParser().fetch(
        _source(), _profile(), _options(), _COLLECT_DATE
    )

    assert len(result.postings) == 1
    assert "30000001" in result.postings[0].source_url
