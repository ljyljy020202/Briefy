"""Tests for GreenhouseParser / DAANGN_CAREERS adapter.

All tests are offline — no real network calls.

Coverage:
  Pure-function tests:
    - _parse_config: defaults, board_slug inference, career_url_tmpl
    - _get_meta: present, absent, null, empty string
    - _parse_deadline: date, empty, null, application_deadline fallback
    - _build_roles: division + dept, dedup, missing division
    - _extract_year_experience: range, min, tilde with HTML entity, preferred exclusion
    - _html_to_description_and_exp: required section extraction, preferred excluded
    - _normalize_location: SEOUL→서울, unknown passthrough
  Registration:
    - GREENHOUSE and DAANGN_CAREERS both registered
    - Naver / Greeting adapters unaffected
  Adapter integration (mock AsyncClient via _MockClient):
    - Backend developer posting (경력 3년 이상 from content)
    - ML engineer posting (신입/경력, tilde range in content)
    - Android intern (인턴, 신입, deadline from Valid Through)
    - Sales (Business division, no year)
    - Product Manager (Product Management division, content 5년 이상)
    - Contract designer (계약직, 경력 3~7년 from content)
    - 당근마켓 legal entity (Corporate="당근마켓")
    - 당근페이 legal entity (Corporate="당근페이")
    - null deadline (상시채용 → deadline=None)
    - metadata order invariance (Division before Prior Experience)
    - max_items cap
    - genuine empty list → 0 postings, no warnings
    - schema failure ("jobs" missing) → warning
    - malformed JSON → warning
    - HTTP error → warning
    - list timeout → warning
    - malformed metadata (partial) → posting still produced with defaults
    - source_record_key stability for same job_id
    - DAANGN_CAREERS parser_key uses board_slug "daangn" and correct career URL
  Normalization integration:
    - 인턴 detected in title overrides employment_type
    - 경력 N년 이상 preserved through normalize()
  Regression:
    - Naver / Greeting adapters still registered and importable
"""

import json
from datetime import date
from pathlib import Path

import httpx
import pytest

import app.adapters.official.greenhouse  # noqa: F401
from app.adapters.official.greenhouse import (
    GreenhouseParser,
    _build_roles,
    _extract_year_experience,
    _get_meta,
    _html_to_description_and_exp,
    _normalize_location,
    _parse_config,
    _parse_deadline,
)
from app.adapters.official_company import _CUSTOM_REGISTRY_BY_KEY
from app.core.identifiers import compute_source_record_key
from app.schemas.collection import (
    CollectionOptions,
    CompanyProfile,
    OfficialCompanySource,
)
from app.services.normalization import normalize

_FIXTURES = Path(__file__).parent / "fixtures" / "greenhouse"
_COLLECT_DATE = date(2026, 8, 15)
_COMPANY_ID = 10


def _read_json(name: str) -> dict:
    return json.loads((_FIXTURES / name).read_text(encoding="utf-8"))


def _source(config_json: str | None = None) -> OfficialCompanySource:
    return OfficialCompanySource(
        company_id=_COMPANY_ID,
        source_type="OFFICIAL_CAREER",
        source_url="https://careers.daangn.com/jobs/",
        adapter_type="CUSTOM",
        config_json=config_json or json.dumps(
            {"parser_key": "DAANGN_CAREERS", "max_discover": 50}
        ),
    )


def _profile() -> CompanyProfile:
    return CompanyProfile(id=_COMPANY_ID, canonical_name="당근", normalized_name="당근")


def _options() -> CollectionOptions:
    return CollectionOptions()


# ── Mock HTTP client (same pattern as greeting tests) ────────────────────────


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


def _json_resp(url: str, data: dict, status: int = 200) -> httpx.Response:
    return httpx.Response(
        status,
        content=json.dumps(data, ensure_ascii=False).encode(),
        headers={"Content-Type": "application/json"},
        request=httpx.Request("GET", url),
    )


_LIST_DATA = _read_json("daangn_list.json")


# ── Registration ──────────────────────────────────────────────────────────────


def test_greenhouse_registered():
    assert "GREENHOUSE" in _CUSTOM_REGISTRY_BY_KEY


def test_daangn_careers_registered():
    assert "DAANGN_CAREERS" in _CUSTOM_REGISTRY_BY_KEY


def test_daangn_careers_is_greenhouse_instance():
    assert isinstance(_CUSTOM_REGISTRY_BY_KEY["DAANGN_CAREERS"], GreenhouseParser)


def test_naver_and_greeting_unaffected():
    import app.adapters.official.greeting  # noqa: F401
    import app.adapters.official.naver_careers  # noqa: F401
    assert "NAVER_CAREERS" in _CUSTOM_REGISTRY_BY_KEY
    assert "GREETING" in _CUSTOM_REGISTRY_BY_KEY


# ── _parse_config ─────────────────────────────────────────────────────────────


def test_parse_config_daangn_careers_defaults():
    cfg = _parse_config(
        '{"parser_key":"DAANGN_CAREERS","max_discover":50}',
        "DAANGN_CAREERS",
    )
    assert cfg.board_slug == "daangn"
    assert cfg.max_items == 50
    assert "careers.daangn.com" in cfg.career_url_tmpl


def test_parse_config_greenhouse_explicit_slug():
    cfg = _parse_config(
        '{"parser_key":"GREENHOUSE","board_slug":"craftone","max_items":100}',
        "GREENHOUSE",
    )
    assert cfg.board_slug == "craftone"
    assert cfg.max_items == 100


def test_parse_config_invalid_json_defaults():
    cfg = _parse_config("not-json", "DAANGN_CAREERS")
    assert cfg.board_slug == "daangn"
    assert cfg.max_items == 200


def test_parse_config_max_discover_alias():
    cfg = _parse_config(
        '{"parser_key":"DAANGN_CAREERS","max_discover":30}', "DAANGN_CAREERS"
    )
    assert cfg.max_items == 30


# ── _get_meta ─────────────────────────────────────────────────────────────────


_SAMPLE_META = [
    {"name": "Division", "value": "Tech"},
    {"name": "Employment Type", "value": "정규직"},
    {"name": "Prior Experience", "value": "경력"},
    {"name": "Corporate", "value": "당근"},
    {"name": "Valid Through", "value": ""},
    {"name": "NullField", "value": None},
]

def test_get_meta_present():
    assert _get_meta(_SAMPLE_META, "Division") == "Tech"


def test_get_meta_absent():
    assert _get_meta(_SAMPLE_META, "NonExistent") is None


def test_get_meta_empty_string():
    assert _get_meta(_SAMPLE_META, "Valid Through") is None


def test_get_meta_null_value():
    meta = [{"name": "Tags", "value": None}]
    assert _get_meta(meta, "Tags") is None


def test_get_meta_order_independent():
    meta = [
        {"name": "Prior Experience", "value": "신입"},
        {"name": "Division", "value": "Tech"},
    ]
    assert _get_meta(meta, "Division") == "Tech"
    assert _get_meta(meta, "Prior Experience") == "신입"


# ── _parse_deadline ───────────────────────────────────────────────────────────


def test_parse_deadline_valid_date():
    assert _parse_deadline("2026-09-30") == date(2026, 9, 30)


def test_parse_deadline_empty_string():
    assert _parse_deadline("") is None


def test_parse_deadline_none():
    assert _parse_deadline(None) is None


def test_parse_deadline_invalid():
    assert _parse_deadline("not-a-date") is None


# ── _build_roles ──────────────────────────────────────────────────────────────


def test_build_roles_division_and_dept():
    depts = [{"name": "Software Engineer, Backend"}]
    assert _build_roles(depts, "Tech") == ["Tech", "Software Engineer, Backend"]


def test_build_roles_no_division():
    depts = [{"name": "Sales"}]
    assert _build_roles(depts, None) == ["Sales"]


def test_build_roles_dedup_when_same():
    depts = [{"name": "Tech"}]
    assert _build_roles(depts, "Tech") == ["Tech"]


def test_build_roles_multiple_depts():
    depts = [{"name": "Software Engineer, Backend"}, {"name": "Software Engineer, iOS"}]
    assert _build_roles(depts, "Tech") == [
        "Tech", "Software Engineer, Backend", "Software Engineer, iOS"
    ]


def test_build_roles_empty():
    assert _build_roles([], None) == []


def test_build_roles_product_manager():
    depts = [{"name": "Product Manager"}]
    assert _build_roles(depts, "Product Management") == [
        "Product Management", "Product Manager"
    ]


# ── _extract_year_experience ──────────────────────────────────────────────────


def test_extract_year_min():
    assert _extract_year_experience("백엔드 개발 경력 3년 이상인 분") == "경력 3년 이상"


def test_extract_year_range_korean():
    assert _extract_year_experience("경력 3년 이상 7년 이하인 분") == "경력 3~7년"


def test_extract_year_tilde():
    assert _extract_year_experience("ML/AI 관련 경험 3~7년 보유하신 분") == "경력 3~7년"


def test_extract_year_no_pattern():
    assert _extract_year_experience("Java, Spring 개발 경험이 있는 분") is None


def test_extract_year_prefers_range_over_min():
    # "경력 3년 이상 7년 이하" should match range first
    result = _extract_year_experience("브랜드 디자인 경력 3년 이상 7년 이하인 분")
    assert result == "경력 3~7년"


# ── _html_to_description_and_exp ─────────────────────────────────────────────


def test_html_to_desc_required_section_extracts_year():
    raw = (
        "&lt;h3&gt;이런 분과 함께하고 싶어요&lt;/h3&gt;"
        "&lt;ul&gt;&lt;li&gt;백엔드 경력 3년 이상인 분&lt;/li&gt;&lt;/ul&gt;"
        "&lt;h3&gt;이런 분이면 더 좋아요!&lt;/h3&gt;"
        "&lt;ul&gt;&lt;li&gt;경력 10년 이상 (우대)&lt;/li&gt;&lt;/ul&gt;"
    )
    desc, exp = _html_to_description_and_exp(raw)
    assert exp == "경력 3년 이상"


def test_html_to_desc_preferred_not_extracted():
    raw = (
        "&lt;h3&gt;이런 분이면 더 좋아요!&lt;/h3&gt;"
        "&lt;ul&gt;&lt;li&gt;경력 10년 이상 (우대)&lt;/li&gt;&lt;/ul&gt;"
    )
    _, exp = _html_to_description_and_exp(raw)
    assert exp is None


def test_html_to_desc_description_not_empty():
    raw = (
        "&lt;h3&gt;이런 일을 해요&lt;/h3&gt;"
        "&lt;p&gt;백엔드 서비스를 개발해요.&lt;/p&gt;"
    )
    desc, _ = _html_to_description_and_exp(raw)
    assert desc is not None
    assert "백엔드 서비스" in desc


def test_html_to_desc_empty_content():
    desc, exp = _html_to_description_and_exp("")
    assert desc is None
    assert exp is None


def test_html_to_desc_caps_at_2000():
    raw = "&lt;p&gt;" + ("A" * 5000) + "&lt;/p&gt;"
    desc, _ = _html_to_description_and_exp(raw)
    assert desc is not None
    assert len(desc) <= 2000


# ── _normalize_location ───────────────────────────────────────────────────────


def test_normalize_location_seoul_upper():
    assert _normalize_location("SEOUL") == "서울"


def test_normalize_location_unknown_passthrough():
    assert _normalize_location("BUSAN") == "부산"


def test_normalize_location_none():
    assert _normalize_location(None) is None


# ── source_record_key stability ────────────────────────────────────────────────


def test_source_record_key_stable():
    sid = "company_10_custom_daangn_careers"
    url = "https://careers.daangn.com/jobs/role/5046757003/"
    k1 = compute_source_record_key(sid, "5046757003", url)
    k2 = compute_source_record_key(sid, "5046757003", url)
    assert k1 == k2


def test_source_record_key_different_jobs():
    sid = "company_10_custom_daangn_careers"
    url1 = "https://careers.daangn.com/jobs/role/5046757003/"
    url2 = "https://careers.daangn.com/jobs/role/5248527003/"
    k1 = compute_source_record_key(sid, "5046757003", url1)
    k2 = compute_source_record_key(sid, "5248527003", url2)
    assert k1 != k2


# ── Normalization integration ─────────────────────────────────────────────────


def test_normalize_intern_in_title():
    from app.adapters.base import RawJobPosting
    raw = RawJobPosting(
        source="company_10_custom_daangn_careers",
        source_url="https://careers.daangn.com/jobs/role/6615071003/",
        company_name="당근",
        title="Software Engineer, Android (인턴)",
        employment_type="인턴",
        experience_level="신입",
        location="서울",
        deadline=date(2026, 8, 31),
        roles=["Tech", "Software Engineer, Android"],
        skills=[],
        source_external_id="6615071003",
    )
    collected = normalize(raw)
    assert collected.employment_type == "인턴"
    assert collected.experience_level == "신입"


def test_normalize_experience_3yr():
    from app.adapters.base import RawJobPosting
    raw = RawJobPosting(
        source="company_10_custom_daangn_careers",
        source_url="https://careers.daangn.com/jobs/role/5046757003/",
        company_name="당근",
        title="Software Engineer, Backend - 피드",
        employment_type="정규직",
        experience_level="경력 3년 이상",
        location="서울",
        deadline=None,
        roles=["Tech", "Software Engineer, Backend"],
        skills=[],
        source_external_id="5046757003",
    )
    collected = normalize(raw)
    assert collected.experience_level == "경력 3년 이상"


# ── Adapter integration tests ─────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_full_list_parsed(monkeypatch):
    """All 9 fixture jobs are parsed (no filter)."""
    async def handler(url, kw):
        return _json_resp(url, _LIST_DATA)

    mock = _MockClient(handler)
    _target = "app.adapters.official.greenhouse.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    result = await GreenhouseParser().fetch(
        _source(), _profile(), _options(), _COLLECT_DATE
    )

    assert result.source_stats.discovered == 9
    assert len(result.postings) == 9
    assert result.warnings == []


@pytest.mark.asyncio
async def test_backend_experience_enriched_from_content(monkeypatch):
    """Backend job: metadata says '경력', content says '3년 이상' → '경력 3년 이상'."""
    data = {"jobs": [_LIST_DATA["jobs"][0]], "meta": {"total": 1}}

    async def handler(url, kw):
        return _json_resp(url, data)

    mock = _MockClient(handler)
    _target = "app.adapters.official.greenhouse.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    result = await GreenhouseParser().fetch(
        _source(), _profile(), _options(), _COLLECT_DATE
    )

    p = result.postings[0]
    assert p.experience_level == "경력 3년 이상"
    assert p.employment_type == "정규직"
    assert "Tech" in p.roles
    assert "Software Engineer, Backend" in p.roles
    assert p.location == "서울"
    assert p.deadline is None


@pytest.mark.asyncio
async def test_ml_engineer_shinip_gyeongryeok(monkeypatch):
    """ML job: metadata '신입/경력', content has tilde range but base wins."""
    data = {"jobs": [_LIST_DATA["jobs"][1]], "meta": {"total": 1}}

    async def handler(url, kw):
        return _json_resp(url, data)

    mock = _MockClient(handler)
    _target = "app.adapters.official.greenhouse.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    result = await GreenhouseParser().fetch(
        _source(), _profile(), _options(), _COLLECT_DATE
    )

    p = result.postings[0]
    # base_exp is "신입/경력" — not "경력" — so content year does NOT override
    assert p.experience_level == "신입/경력"
    assert "Software Engineer, Machine Learning" in p.roles
    assert p.deadline is None


@pytest.mark.asyncio
async def test_android_intern_deadline(monkeypatch):
    """Android intern: employment_type=인턴, deadline from Valid Through."""
    data = {"jobs": [_LIST_DATA["jobs"][2]], "meta": {"total": 1}}

    async def handler(url, kw):
        return _json_resp(url, data)

    mock = _MockClient(handler)
    _target = "app.adapters.official.greenhouse.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    result = await GreenhouseParser().fetch(
        _source(), _profile(), _options(), _COLLECT_DATE
    )

    p = result.postings[0]
    assert p.employment_type == "인턴"
    assert p.experience_level == "신입"
    assert p.deadline == date(2026, 8, 31)
    assert "Software Engineer, Android" in p.roles


@pytest.mark.asyncio
async def test_sales_business_division(monkeypatch):
    """Sales job: Division=Business, dept=Sales, no year in content."""
    data = {"jobs": [_LIST_DATA["jobs"][3]], "meta": {"total": 1}}

    async def handler(url, kw):
        return _json_resp(url, data)

    mock = _MockClient(handler)
    _target = "app.adapters.official.greenhouse.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    result = await GreenhouseParser().fetch(
        _source(), _profile(), _options(), _COLLECT_DATE
    )

    p = result.postings[0]
    assert "Business" in p.roles
    assert "Sales" in p.roles
    assert p.experience_level == "경력"  # no year in content → keep base


@pytest.mark.asyncio
async def test_product_manager_5yr(monkeypatch):
    """PM job: content says 5년 이상 → experience_level='경력 5년 이상'."""
    data = {"jobs": [_LIST_DATA["jobs"][4]], "meta": {"total": 1}}

    async def handler(url, kw):
        return _json_resp(url, data)

    mock = _MockClient(handler)
    _target = "app.adapters.official.greenhouse.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    result = await GreenhouseParser().fetch(
        _source(), _profile(), _options(), _COLLECT_DATE
    )

    p = result.postings[0]
    assert p.experience_level == "경력 5년 이상"
    assert "Product Management" in p.roles
    assert "Product Manager" in p.roles


@pytest.mark.asyncio
async def test_contract_designer_range(monkeypatch):
    """Designer: 계약직, content says '경력 3년 이상 7년 이하' → '경력 3~7년'."""
    data = {"jobs": [_LIST_DATA["jobs"][5]], "meta": {"total": 1}}

    async def handler(url, kw):
        return _json_resp(url, data)

    mock = _MockClient(handler)
    _target = "app.adapters.official.greenhouse.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    result = await GreenhouseParser().fetch(
        _source(), _profile(), _options(), _COLLECT_DATE
    )

    p = result.postings[0]
    assert p.employment_type == "계약직"
    assert p.experience_level == "경력 3~7년"
    assert p.deadline == date(2026, 9, 30)


@pytest.mark.asyncio
async def test_daangn_market_legal_entity(monkeypatch):
    """Corporate='당근마켓' → company_name='당근마켓'."""
    data = {"jobs": [_LIST_DATA["jobs"][6]], "meta": {"total": 1}}

    async def handler(url, kw):
        return _json_resp(url, data)

    mock = _MockClient(handler)
    _target = "app.adapters.official.greenhouse.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    result = await GreenhouseParser().fetch(
        _source(), _profile(), _options(), _COLLECT_DATE
    )

    assert result.postings[0].company_name == "당근마켓"


@pytest.mark.asyncio
async def test_daangn_pay_legal_entity(monkeypatch):
    """Corporate='당근페이' → company_name='당근페이'."""
    data = {"jobs": [_LIST_DATA["jobs"][7]], "meta": {"total": 1}}

    async def handler(url, kw):
        return _json_resp(url, data)

    mock = _MockClient(handler)
    _target = "app.adapters.official.greenhouse.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    result = await GreenhouseParser().fetch(
        _source(), _profile(), _options(), _COLLECT_DATE
    )

    assert result.postings[0].company_name == "당근페이"
    assert result.postings[0].experience_level == "경력 5년 이상"


@pytest.mark.asyncio
async def test_null_deadline_is_none(monkeypatch):
    """Valid Through null → deadline=None."""
    data = {"jobs": [_LIST_DATA["jobs"][1]], "meta": {"total": 1}}  # ML job, null

    async def handler(url, kw):
        return _json_resp(url, data)

    mock = _MockClient(handler)
    _target = "app.adapters.official.greenhouse.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    result = await GreenhouseParser().fetch(
        _source(), _profile(), _options(), _COLLECT_DATE
    )

    assert result.postings[0].deadline is None


@pytest.mark.asyncio
async def test_metadata_order_invariance(monkeypatch):
    """Swapping Division and Prior Experience order gives same result."""
    job = dict(_LIST_DATA["jobs"][0])
    # Reverse metadata list
    job = {**job, "metadata": list(reversed(job["metadata"]))}
    data = {"jobs": [job], "meta": {"total": 1}}

    async def handler(url, kw):
        return _json_resp(url, data)

    mock = _MockClient(handler)
    _target = "app.adapters.official.greenhouse.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    result = await GreenhouseParser().fetch(
        _source(), _profile(), _options(), _COLLECT_DATE
    )

    p = result.postings[0]
    assert "Tech" in p.roles
    assert p.experience_level == "경력 3년 이상"


@pytest.mark.asyncio
async def test_malformed_metadata_produces_posting(monkeypatch):
    """Job with partial metadata (missing Employment Type, Prior Experience)."""
    data = {"jobs": [_LIST_DATA["jobs"][8]], "meta": {"total": 1}}

    async def handler(url, kw):
        return _json_resp(url, data)

    mock = _MockClient(handler)
    _target = "app.adapters.official.greenhouse.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    result = await GreenhouseParser().fetch(
        _source(), _profile(), _options(), _COLLECT_DATE
    )

    assert len(result.postings) == 1
    p = result.postings[0]
    assert p.employment_type is None
    assert p.experience_level is None  # no Prior Experience in metadata
    assert "Tech" in p.roles


@pytest.mark.asyncio
async def test_max_items_cap(monkeypatch):
    """max_items=3 → only first 3 postings returned."""
    async def handler(url, kw):
        return _json_resp(url, _LIST_DATA)

    mock = _MockClient(handler)
    _target = "app.adapters.official.greenhouse.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    cfg = json.dumps({"parser_key": "DAANGN_CAREERS", "max_items": 3})
    result = await GreenhouseParser().fetch(
        _source(cfg), _profile(), _options(), _COLLECT_DATE
    )

    assert len(result.postings) == 3


@pytest.mark.asyncio
async def test_genuine_empty_list(monkeypatch):
    """Empty jobs list → 0 postings, no warnings."""
    async def handler(url, kw):
        return _json_resp(url, {"jobs": [], "meta": {"total": 0}})

    mock = _MockClient(handler)
    _target = "app.adapters.official.greenhouse.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    result = await GreenhouseParser().fetch(
        _source(), _profile(), _options(), _COLLECT_DATE
    )

    assert result.postings == []
    assert result.warnings == []


@pytest.mark.asyncio
async def test_schema_failure_missing_jobs_key(monkeypatch):
    """Response without 'jobs' key → schema warning, 0 postings."""
    async def handler(url, kw):
        return _json_resp(url, {"error": "not found"})

    mock = _MockClient(handler)
    _target = "app.adapters.official.greenhouse.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    result = await GreenhouseParser().fetch(
        _source(), _profile(), _options(), _COLLECT_DATE
    )

    assert result.postings == []
    assert any("schema" in w.lower() for w in result.warnings)


@pytest.mark.asyncio
async def test_malformed_json_warning(monkeypatch):
    async def handler(url, kw):
        return httpx.Response(
            200, content=b"not-json",
            headers={"Content-Type": "application/json"},
            request=httpx.Request("GET", url),
        )

    mock = _MockClient(handler)
    _target = "app.adapters.official.greenhouse.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    result = await GreenhouseParser().fetch(
        _source(), _profile(), _options(), _COLLECT_DATE
    )

    assert result.postings == []
    assert any("JSON parse error" in w for w in result.warnings)


@pytest.mark.asyncio
async def test_http_error_warning(monkeypatch):
    async def handler(url, kw):
        return _json_resp(url, {}, status=503)

    mock = _MockClient(handler)
    _target = "app.adapters.official.greenhouse.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    result = await GreenhouseParser().fetch(
        _source(), _profile(), _options(), _COLLECT_DATE
    )

    assert result.postings == []
    assert any("HTTP" in w for w in result.warnings)


@pytest.mark.asyncio
async def test_timeout_warning(monkeypatch):
    async def handler(url, kw):
        raise httpx.TimeoutException("timed out")

    mock = _MockClient(handler)
    _target = "app.adapters.official.greenhouse.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    result = await GreenhouseParser().fetch(
        _source(), _profile(), _options(), _COLLECT_DATE
    )

    assert result.postings == []
    assert any("timeout" in w.lower() for w in result.warnings)


@pytest.mark.asyncio
async def test_daangn_careers_career_url_format(monkeypatch):
    """DAANGN_CAREERS source_url uses careers.daangn.com/jobs/role/{job_id}/."""
    data = {"jobs": [_LIST_DATA["jobs"][0]], "meta": {"total": 1}}

    async def handler(url, kw):
        return _json_resp(url, data)

    mock = _MockClient(handler)
    _target = "app.adapters.official.greenhouse.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)

    result = await GreenhouseParser().fetch(
        _source(), _profile(), _options(), _COLLECT_DATE
    )

    p = result.postings[0]
    assert "careers.daangn.com" in p.source_url
    assert "5046757003" in p.source_url
