"""Tests for the KakaoCareersParser (KAKAO_CAREERS custom adapter).

Unit tests use inline fixture dicts — no network I/O.
Integration tests use monkeypatched AsyncClient.
Live smoke tests are marked @pytest.mark.external and excluded from CI.
"""

from __future__ import annotations

import json
from datetime import date, datetime
from unittest.mock import AsyncMock, MagicMock

import pytest

from app.adapters.official.kakao_careers import (
    KakaoCareersParser,
    _build_posting,
    _deadline,
    _description,
    _employment_type,
    _experience_level,
    _is_active,
    _is_shell_response,
    _KakaoConfig,
    _location,
    _parse_config,
    _posted_at,
    _roles,
)
from app.schemas.collection import (
    CollectionOptions,
    CompanyProfile,
    OfficialCompanySource,
)

# ─────────────────────────────────────────────────────────────────────────────
# Fixture items (mirroring real API response fields)
# ─────────────────────────────────────────────────────────────────────────────

_SENIOR_ENG = {
    "realId": "P-14519",
    "jobOfferId": 14519,
    "jobOfferTitle": "AI Research Engineer (Search & Agent) (경력)",
    "closeFlag": False,
    "privateFlag": False,
    "mainCompanyJobOfferFlag": True,
    "companyName": "카카오",
    "companyCodeId": "dk",
    "employeeTypeName": "정규직",
    "locationName": "판교",
    "locationCodeId": "PANGYO",
    "jobPartName": "테크",
    "jobPart": "TECHNOLOGY",
    "skillSetList": [
        {"skillSetType": "Algorithm_ML", "skillSetName": "Algorithm/ML"}
    ],
    "endDate": None,
    "resumeSubmissionEndDatetime": None,
    "regDate": "2026-08-05T09:46:52",
    "uptDate": "2026-08-06T14:40:52",
    "qualification": (
        "**[공통 필수 역량]**<br/>"
        "• 관련 경력을 2년 이상 보유하신 분<br/>"
        "• 머신러닝 기본 개념을 이해하신 분"
    ),
    "workContentDesc": (
        "카카오 AI 검색 플랫폼 핵심 모델을 연구·개발합니다.<br/>"
        "• Agentic Search LLM 연구"
    ),
    "statusCode": "PROGRESS",
    "pinFlag": True,
    "useFlag": True,
    "sortNo": 6,
    "kakaoJobOfferByRealId": True,
}

_NEWCOMER_ONLY = {
    "realId": "P-12345",
    "jobOfferId": 12345,
    "jobOfferTitle": "UI Designer (신입)",
    "closeFlag": False,
    "privateFlag": False,
    "mainCompanyJobOfferFlag": True,
    "companyName": "카카오",
    "companyCodeId": "dk",
    "employeeTypeName": "정규직",
    "locationName": "판교",
    "locationCodeId": "PANGYO",
    "jobPartName": "디자인",
    "skillSetList": [{"skillSetType": "Design_UX", "skillSetName": "UX"}],
    "endDate": None,
    "resumeSubmissionEndDatetime": None,
    "regDate": "2026-07-01T00:00:00",
    "qualification": "신입 환영합니다.",
    "workContentDesc": "UI 디자인 업무.",
    "statusCode": "PROGRESS",
    "kakaoJobOfferByRealId": True,
}

_NEWCOMER_CAREER = {
    "realId": "P-14403",
    "jobOfferId": 14403,
    "jobOfferTitle": "LLM Research Engineer (신입/경력)",
    "closeFlag": False,
    "privateFlag": False,
    "mainCompanyJobOfferFlag": True,
    "companyName": "카카오",
    "companyCodeId": "dk",
    "employeeTypeName": "정규직",
    "locationName": "판교",
    "locationCodeId": "PANGYO",
    "jobPartName": "테크",
    "skillSetList": [
        {"skillSetType": "Algorithm_ML", "skillSetName": "Algorithm/ML"}
    ],
    "endDate": None,
    "resumeSubmissionEndDatetime": None,
    "regDate": "2026-08-01T00:00:00",
    "qualification": "• 관련 연구 또는 개발 경험 보유자",
    "workContentDesc": "LLM 연구.",
    "statusCode": "PROGRESS",
    "kakaoJobOfferByRealId": True,
}

_CAREER_MIN_YR = {
    "realId": "P-14469",
    "jobOfferId": 14469,
    "jobOfferTitle": "AI Platform 추론 최적화 Engineer(경력)",
    "closeFlag": False,
    "privateFlag": False,
    "mainCompanyJobOfferFlag": True,
    "companyName": "카카오",
    "companyCodeId": "dk",
    "employeeTypeName": "정규직",
    "locationName": "판교",
    "locationCodeId": "PANGYO",
    "jobPartName": "테크",
    "skillSetList": [{"skillSetType": "Server", "skillSetName": "Server"}],
    "endDate": None,
    "resumeSubmissionEndDatetime": None,
    "regDate": "2026-06-11T09:34:19",
    "qualification": "• 관련 경력을 5년 이상 보유하신 분",
    "workContentDesc": "추론 최적화.",
    "statusCode": "PROGRESS",
    "kakaoJobOfferByRealId": True,
}

_CAREER_RANGE = {
    "realId": "P-13744",
    "jobOfferId": 13744,
    "jobOfferTitle": "서비스/플랫폼 QA 담당자 (경력)",
    "closeFlag": False,
    "privateFlag": False,
    "mainCompanyJobOfferFlag": True,
    "companyName": "카카오",
    "companyCodeId": "dk",
    "employeeTypeName": "정규직",
    "locationName": "판교",
    "locationCodeId": "PANGYO",
    "jobPartName": "테크",
    "skillSetList": [{"skillSetType": "QA", "skillSetName": "QA"}],
    "endDate": None,
    "resumeSubmissionEndDatetime": None,
    "regDate": "2025-01-10T00:00:00",
    "qualification": "- SW QA 경력이 2년~10년이내 이신 분",
    "workContentDesc": "QA 업무.",
    "statusCode": "PROGRESS",
    "kakaoJobOfferByRealId": True,
}

_WITH_DEADLINE = {
    "realId": "P-11111",
    "jobOfferId": 11111,
    "jobOfferTitle": "마감일 있는 공고 (경력)",
    "closeFlag": False,
    "privateFlag": False,
    "mainCompanyJobOfferFlag": True,
    "companyName": "카카오",
    "companyCodeId": "dk",
    "employeeTypeName": "계약직",
    "locationName": "성수",
    "locationCodeId": "SEONGSU",
    "jobPartName": "서비스비즈",
    "skillSetList": [],
    "endDate": "2026-12-31",
    "resumeSubmissionEndDatetime": None,
    "regDate": "2026-01-01T00:00:00",
    "qualification": "• 관련 경력 3년 이상",
    "workContentDesc": "서비스 기획.",
    "statusCode": "PROGRESS",
    "kakaoJobOfferByRealId": True,
}

_INTERN = {
    "realId": "P-22222",
    "jobOfferId": 22222,
    "jobOfferTitle": "서버 개발 인턴 (신입)",
    "closeFlag": False,
    "privateFlag": False,
    "mainCompanyJobOfferFlag": True,
    "companyName": "카카오",
    "companyCodeId": "dk",
    "employeeTypeName": "인턴",
    "locationName": "판교",
    "locationCodeId": "PANGYO",
    "jobPartName": "테크",
    "skillSetList": [{"skillSetType": "Server", "skillSetName": "Server"}],
    "endDate": None,
    "resumeSubmissionEndDatetime": None,
    "regDate": "2026-05-01T00:00:00",
    "qualification": "",
    "workContentDesc": "서버 개발 인턴십.",
    "statusCode": "PROGRESS",
    "kakaoJobOfferByRealId": True,
}

_CLOSED = {
    "realId": "P-99999",
    "jobOfferId": 99999,
    "jobOfferTitle": "마감된 공고 (경력)",
    "closeFlag": True,
    "privateFlag": False,
    "mainCompanyJobOfferFlag": True,
    "companyName": "카카오",
    "companyCodeId": "dk",
    "employeeTypeName": "정규직",
    "locationName": "판교",
    "locationCodeId": "PANGYO",
    "jobPartName": "테크",
    "skillSetList": [],
    "endDate": None,
    "resumeSubmissionEndDatetime": None,
    "regDate": "2024-01-01T00:00:00",
    "qualification": "",
    "workContentDesc": "",
    "statusCode": "CLOSED",
    "kakaoJobOfferByRealId": True,
}

_AFFILIATE_S = {
    "realId": "S-4740",
    "jobOfferId": 4740,
    "jobOfferTitle": "[공동체] 카카오페이 서버 개발자 (경력)",
    "closeFlag": False,
    "privateFlag": False,
    "mainCompanyJobOfferFlag": False,
    "companyName": "카카오페이",
    "companyCodeId": "kp",
    "employeeTypeName": "정규직",
    "locationName": "판교",
    "locationCodeId": "PANGYO",
    "jobPartName": "테크",
    "skillSetList": [],
    "endDate": None,
    "resumeSubmissionEndDatetime": None,
    "regDate": "2026-01-01T00:00:00",
    "qualification": "",
    "workContentDesc": "",
    "statusCode": "PROGRESS",
    "kakaoJobOfferByRealId": False,
}

_NESTED_HTML = {
    "realId": "P-33333",
    "jobOfferId": 33333,
    "jobOfferTitle": "Frontend Engineer (경력)",
    "closeFlag": False,
    "privateFlag": False,
    "mainCompanyJobOfferFlag": True,
    "companyName": "카카오",
    "companyCodeId": "dk",
    "employeeTypeName": "정규직",
    "locationName": "판교",
    "locationCodeId": "PANGYO",
    "jobPartName": "테크",
    "skillSetList": [{"skillSetType": "Web", "skillSetName": "Web Frontend"}],
    "endDate": None,
    "resumeSubmissionEndDatetime": None,
    "regDate": "2026-03-01T00:00:00",
    "qualification": (
        "<div><strong>필수 자격</strong>"
        "<ul><li>관련 경력을 <em>3년 이상</em> 보유하신 분</li>"
        "<li>React 사용 경험</li></ul></div>"
    ),
    "workContentDesc": (
        "<p>카카오 서비스의 <b>프론트엔드</b>를 담당합니다.</p>"
        "<ul><li>기능 개발</li><li>성능 최적화</li></ul>"
    ),
    "statusCode": "PROGRESS",
    "kakaoJobOfferByRealId": True,
}


# ─────────────────────────────────────────────────────────────────────────────
# _parse_config
# ─────────────────────────────────────────────────────────────────────────────


def test_parse_config_defaults():
    cfg = _parse_config(None)
    assert isinstance(cfg, _KakaoConfig)
    assert cfg.max_items == 200


def test_parse_config_explicit():
    cfg = _parse_config('{"parser_key":"KAKAO_CAREERS","max_items":50}')
    assert cfg.max_items == 50


def test_parse_config_malformed_uses_defaults():
    cfg = _parse_config("{bad json}")
    assert cfg.max_items == 200


# ─────────────────────────────────────────────────────────────────────────────
# _is_shell_response
# ─────────────────────────────────────────────────────────────────────────────


def test_shell_response_html_content_type():
    assert _is_shell_response("text/html; charset=utf-8", "<html>") is True


def test_shell_response_doctype_fallback():
    assert _is_shell_response("", "<!DOCTYPE html><html>") is True


def test_shell_response_json_is_not_shell():
    assert _is_shell_response("application/json", '{"jobList":[]}') is False


def test_shell_response_empty_body_json():
    assert _is_shell_response("application/json; charset=utf-8", "{}") is False


# ─────────────────────────────────────────────────────────────────────────────
# _is_active
# ─────────────────────────────────────────────────────────────────────────────


def test_is_active_normal():
    assert _is_active(_SENIOR_ENG) is True


def test_is_active_closed():
    assert _is_active(_CLOSED) is False


def test_is_active_affiliate_s_id():
    """S-{id} items must be rejected even if closeFlag=False."""
    assert _is_active(_AFFILIATE_S) is False


def test_is_active_main_flag_false():
    item = dict(_SENIOR_ENG, mainCompanyJobOfferFlag=False)
    assert _is_active(item) is False


# ─────────────────────────────────────────────────────────────────────────────
# _experience_level
# ─────────────────────────────────────────────────────────────────────────────


def test_exp_newcomer_only():
    assert _experience_level(_NEWCOMER_ONLY) == "신입"


def test_exp_newcomer_career_no_year():
    result = _experience_level(_NEWCOMER_CAREER)
    assert result == "신입/경력"


def test_exp_career_with_min_year():
    assert _experience_level(_SENIOR_ENG) == "경력 2년 이상"


def test_exp_career_high_min_year():
    assert _experience_level(_CAREER_MIN_YR) == "경력 5년 이상"


def test_exp_career_range():
    """'2년~10년이내' → 경력 2~10년 (lower bound used)."""
    result = _experience_level(_CAREER_RANGE)
    assert result == "경력 2~10년"


def test_exp_career_no_year():
    item = dict(_SENIOR_ENG, qualification="자격 조건 없음")
    result = _experience_level(item)
    assert result == "경력"


def test_exp_null_no_hint():
    item = dict(_SENIOR_ENG, jobOfferTitle="Backend Engineer")
    assert _experience_level(item) is None


def test_exp_newcomer_career_with_year():
    item = dict(
        _NEWCOMER_CAREER,
        qualification="관련 경력을 2년 이상 보유하신 분",
    )
    assert _experience_level(item) == "신입/경력 2년 이상"


# ─────────────────────────────────────────────────────────────────────────────
# _employment_type
# ─────────────────────────────────────────────────────────────────────────────


def test_emp_type_full_time():
    assert _employment_type(_SENIOR_ENG) == "정규직"


def test_emp_type_contract():
    assert _employment_type(_WITH_DEADLINE) == "계약직"


def test_emp_type_intern():
    assert _employment_type(_INTERN) == "인턴"


def test_emp_type_empty():
    assert _employment_type({"employeeTypeName": ""}) is None


# ─────────────────────────────────────────────────────────────────────────────
# _deadline
# ─────────────────────────────────────────────────────────────────────────────


def test_deadline_end_date():
    assert _deadline(_WITH_DEADLINE) == date(2026, 12, 31)


def test_deadline_resume_end_datetime():
    item = {
        "endDate": None,
        "resumeSubmissionEndDatetime": "2026-09-30T23:59:59",
    }
    assert _deadline(item) == date(2026, 9, 30)


def test_deadline_both_null():
    assert _deadline(_SENIOR_ENG) is None


def test_deadline_end_date_takes_priority():
    item = {
        "endDate": "2026-12-01",
        "resumeSubmissionEndDatetime": "2026-11-01T00:00:00",
    }
    assert _deadline(item) == date(2026, 12, 1)


# ─────────────────────────────────────────────────────────────────────────────
# _location
# ─────────────────────────────────────────────────────────────────────────────


def test_location_pangyo():
    assert _location(_SENIOR_ENG) == "판교"


def test_location_seongsu():
    assert _location(_WITH_DEADLINE) == "성수"


def test_location_empty():
    assert _location({"locationName": ""}) is None


# ─────────────────────────────────────────────────────────────────────────────
# _roles
# ─────────────────────────────────────────────────────────────────────────────


def test_roles_tech_with_skillset():
    result = _roles(_SENIOR_ENG)
    assert result[0] == "테크"
    assert "Algorithm/ML" in result


def test_roles_design():
    result = _roles(_NEWCOMER_ONLY)
    assert result[0] == "디자인"
    assert "UX" in result


def test_roles_no_skillset():
    result = _roles(_WITH_DEADLINE)
    assert result == ["서비스비즈"]


def test_roles_deduplicates():
    item = dict(
        _SENIOR_ENG,
        jobPartName="테크",
        skillSetList=[
            {"skillSetName": "테크"},  # duplicate of jobPartName
            {"skillSetName": "Server"},
        ],
    )
    result = _roles(item)
    assert result.count("테크") == 1
    assert "Server" in result


# ─────────────────────────────────────────────────────────────────────────────
# _description
# ─────────────────────────────────────────────────────────────────────────────


def test_description_combines_fields():
    result = _description(_SENIOR_ENG)
    assert result is not None
    assert "경력을 2년 이상" in result
    assert "Agentic Search LLM" in result


def test_description_strips_html_tags():
    result = _description(_NESTED_HTML)
    assert result is not None
    assert "<" not in result
    assert "필수 자격" in result
    assert "3년 이상" in result
    assert "React" in result


def test_description_nested_tags():
    result = _description(_NESTED_HTML)
    assert result is not None
    assert "프론트엔드" in result
    assert "기능 개발" in result


def test_description_truncates_at_4000():
    long_html = f"<p>{'x' * 5000}</p>"
    item = dict(_SENIOR_ENG, workContentDesc=long_html, qualification="")
    result = _description(item)
    assert result is not None
    assert len(result) <= 4000


def test_description_empty_fields():
    item = dict(_SENIOR_ENG, workContentDesc="", qualification="")
    assert _description(item) is None


# ─────────────────────────────────────────────────────────────────────────────
# _posted_at
# ─────────────────────────────────────────────────────────────────────────────


def test_posted_at_parses():
    result = _posted_at(_SENIOR_ENG)
    assert isinstance(result, datetime)
    assert result.year == 2026
    assert result.month == 8
    assert result.day == 5


def test_posted_at_none():
    assert _posted_at({"regDate": None}) is None


# ─────────────────────────────────────────────────────────────────────────────
# _build_posting
# ─────────────────────────────────────────────────────────────────────────────


def test_build_posting_senior_engineer():
    posting = _build_posting(_SENIOR_ENG, "카카오")
    assert posting.title == "AI Research Engineer (Search & Agent) (경력)"
    assert posting.source_external_id == "P-14519"
    assert posting.source_url == "https://careers.kakao.com/jobs/P-14519"
    assert posting.source == "kakao_careers"
    assert posting.company_name == "카카오"
    assert posting.employment_type == "정규직"
    assert posting.experience_level == "경력 2년 이상"
    assert posting.location == "판교"
    assert "테크" in posting.roles
    assert "Algorithm/ML" in posting.roles
    assert posting.deadline is None
    assert posting.description is not None


def test_build_posting_newcomer():
    posting = _build_posting(_NEWCOMER_ONLY, "카카오")
    assert posting.experience_level == "신입"
    assert "디자인" in posting.roles


def test_build_posting_intern():
    posting = _build_posting(_INTERN, "카카오")
    assert posting.employment_type == "인턴"
    assert posting.experience_level == "신입"


def test_build_posting_with_deadline():
    posting = _build_posting(_WITH_DEADLINE, "카카오")
    assert posting.deadline == date(2026, 12, 31)
    assert posting.employment_type == "계약직"


def test_build_posting_career_range():
    posting = _build_posting(_CAREER_RANGE, "카카오")
    assert posting.experience_level == "경력 2~10년"


def test_build_posting_company_name_from_profile():
    posting = _build_posting(_SENIOR_ENG, "카카오코퍼레이션")
    assert posting.company_name == "카카오코퍼레이션"


# ─────────────────────────────────────────────────────────────────────────────
# KakaoCareersParser.fetch — integration with mocked HTTP
# ─────────────────────────────────────────────────────────────────────────────


def _source(config_json: str | None = None) -> OfficialCompanySource:
    return OfficialCompanySource(
        company_id=4,
        source_type="OFFICIAL_CAREER",
        source_url="https://careers.kakao.com/jobs",
        adapter_type="CUSTOM",
        config_json=config_json
        or '{"parser_key":"KAKAO_CAREERS","max_items":50}',
    )


def _profile() -> CompanyProfile:
    return CompanyProfile(
        id=4, canonical_name="카카오", normalized_name="카카오"
    )


def _options() -> CollectionOptions:
    return CollectionOptions()


_COLLECT_DATE = date(2026, 8, 17)


def _make_page_response(jobs: list[dict], total: int, total_page: int):
    return {
        "jobList": jobs,
        "totalJobCount": total,
        "totalPage": total_page,
        "jobTypeCountDtoList": [],
    }


def _make_mock_client(page_map: dict[int, list[dict]], total: int):
    """Build AsyncMock client returning per-page job lists."""
    total_page = max(page_map.keys()) if page_map else 1

    async def _get(url, params=None, **kwargs):
        page = int((params or {}).get("page", 1))
        jobs = page_map.get(page, [])
        body = json.dumps(
            _make_page_response(jobs, total, total_page), ensure_ascii=False
        )
        resp = MagicMock()
        resp.status_code = 200
        resp.headers = {"content-type": "application/json"}
        resp.text = body
        resp.json = lambda: json.loads(body)
        resp.raise_for_status = MagicMock()
        return resp

    mock_client = MagicMock()
    mock_client.get = AsyncMock(side_effect=_get)
    mock_client.__aenter__ = AsyncMock(return_value=mock_client)
    mock_client.__aexit__ = AsyncMock(return_value=False)
    return mock_client


async def test_fetch_single_page_success(monkeypatch):
    mock = _make_mock_client({1: [_SENIOR_ENG, _CAREER_MIN_YR]}, total=2)
    monkeypatch.setattr(
        "app.adapters.official.kakao_careers.AsyncClient", lambda **kw: mock
    )

    result = await KakaoCareersParser().fetch(
        _source(), _profile(), _options(), _COLLECT_DATE
    )

    assert len(result.postings) == 2
    assert result.postings[0].title == "AI Research Engineer (Search & Agent) (경력)"
    assert result.source_stats.discovered == 2
    assert result.source_stats.parsed == 2


async def test_fetch_multi_page(monkeypatch):
    page_map = {
        1: [_SENIOR_ENG] * 15,
        2: [_CAREER_MIN_YR] * 5,
    }
    mock = _make_mock_client(page_map, total=20)
    monkeypatch.setattr(
        "app.adapters.official.kakao_careers.AsyncClient", lambda **kw: mock
    )

    result = await KakaoCareersParser().fetch(
        _source(), _profile(), _options(), _COLLECT_DATE
    )

    assert len(result.postings) == 20
    assert result.source_stats.discovered == 20


async def test_fetch_filters_closed(monkeypatch):
    mock = _make_mock_client({1: [_SENIOR_ENG, _CLOSED]}, total=2)
    monkeypatch.setattr(
        "app.adapters.official.kakao_careers.AsyncClient", lambda **kw: mock
    )

    result = await KakaoCareersParser().fetch(
        _source(), _profile(), _options(), _COLLECT_DATE
    )

    assert len(result.postings) == 1
    assert result.postings[0].source_external_id == "P-14519"


async def test_fetch_filters_affiliate_s_id(monkeypatch):
    """S-{id} items in response must be excluded even without company filter."""
    mock = _make_mock_client({1: [_SENIOR_ENG, _AFFILIATE_S]}, total=2)
    monkeypatch.setattr(
        "app.adapters.official.kakao_careers.AsyncClient", lambda **kw: mock
    )

    result = await KakaoCareersParser().fetch(
        _source(), _profile(), _options(), _COLLECT_DATE
    )

    assert len(result.postings) == 1
    assert result.postings[0].source_external_id == "P-14519"


async def test_fetch_empty_list_no_warnings(monkeypatch):
    mock = _make_mock_client({1: []}, total=0)
    monkeypatch.setattr(
        "app.adapters.official.kakao_careers.AsyncClient", lambda **kw: mock
    )

    result = await KakaoCareersParser().fetch(
        _source(), _profile(), _options(), _COLLECT_DATE
    )

    assert result.postings == []
    assert result.source_stats.discovered == 0
    assert result.warnings == []


async def test_fetch_cloudflare_shell_response(monkeypatch):
    """HTML shell instead of JSON must produce warning and empty postings."""
    shell_html = (
        "<!doctype html><html><body>"
        '<div id="root"><div id="app-loader"></div></div>'
        "</body></html>"
    )

    async def _get(url, **kwargs):
        resp = MagicMock()
        resp.status_code = 200
        resp.headers = {"content-type": "text/html"}
        resp.text = shell_html
        resp.raise_for_status = MagicMock()
        return resp

    mock_client = MagicMock()
    mock_client.get = AsyncMock(side_effect=_get)
    mock_client.__aenter__ = AsyncMock(return_value=mock_client)
    mock_client.__aexit__ = AsyncMock(return_value=False)
    monkeypatch.setattr(
        "app.adapters.official.kakao_careers.AsyncClient", lambda **kw: mock_client
    )

    result = await KakaoCareersParser().fetch(
        _source(), _profile(), _options(), _COLLECT_DATE
    )

    assert result.postings == []
    assert any("shell" in w for w in result.warnings)


async def test_fetch_timeout_returns_warning(monkeypatch):
    from httpx import TimeoutException

    mock_client = MagicMock()
    mock_client.get = AsyncMock(side_effect=TimeoutException("timeout"))
    mock_client.__aenter__ = AsyncMock(return_value=mock_client)
    mock_client.__aexit__ = AsyncMock(return_value=False)
    monkeypatch.setattr(
        "app.adapters.official.kakao_careers.AsyncClient", lambda **kw: mock_client
    )

    result = await KakaoCareersParser().fetch(
        _source(), _profile(), _options(), _COLLECT_DATE
    )

    assert result.postings == []
    assert any("timeout" in w for w in result.warnings)


async def test_fetch_respects_max_items(monkeypatch):
    jobs = [_SENIOR_ENG] * 30
    mock = _make_mock_client({1: jobs}, total=30)
    monkeypatch.setattr(
        "app.adapters.official.kakao_careers.AsyncClient", lambda **kw: mock
    )

    src = _source('{"parser_key":"KAKAO_CAREERS","max_items":5}')
    result = await KakaoCareersParser().fetch(
        src, _profile(), _options(), _COLLECT_DATE
    )

    assert len(result.postings) == 5
    assert result.source_stats.discovered == 30


async def test_fetch_company_name_from_profile(monkeypatch):
    mock = _make_mock_client({1: [_SENIOR_ENG]}, total=1)
    monkeypatch.setattr(
        "app.adapters.official.kakao_careers.AsyncClient", lambda **kw: mock
    )

    profile = CompanyProfile(
        id=4, canonical_name="카카오 Corp", normalized_name="카카오"
    )
    result = await KakaoCareersParser().fetch(
        _source(), profile, _options(), _COLLECT_DATE
    )

    assert result.postings[0].company_name == "카카오 Corp"


async def test_fetch_page2_shell_produces_warning(monkeypatch):
    """Page 2 returning HTML shell emits a warning but doesn't crash."""
    json_body = json.dumps(
        _make_page_response([_SENIOR_ENG] * 15, total=20, total_page=2),
        ensure_ascii=False,
    )
    shell = "<!doctype html><html><body><div id='root'></div></body></html>"

    call_count = 0

    async def _get(url, params=None, **kwargs):
        nonlocal call_count
        call_count += 1
        page = int((params or {}).get("page", 1))

        if page == 1:
            resp = MagicMock()
            resp.status_code = 200
            resp.headers = {"content-type": "application/json"}
            resp.text = json_body
            resp.json = lambda: json.loads(json_body)
            resp.raise_for_status = MagicMock()
            return resp
        else:
            resp = MagicMock()
            resp.status_code = 200
            resp.headers = {"content-type": "text/html"}
            resp.text = shell
            resp.raise_for_status = MagicMock()
            return resp

    mock_client = MagicMock()
    mock_client.get = AsyncMock(side_effect=_get)
    mock_client.__aenter__ = AsyncMock(return_value=mock_client)
    mock_client.__aexit__ = AsyncMock(return_value=False)
    monkeypatch.setattr(
        "app.adapters.official.kakao_careers.AsyncClient", lambda **kw: mock_client
    )

    result = await KakaoCareersParser().fetch(
        _source(), _profile(), _options(), _COLLECT_DATE
    )

    assert len(result.postings) == 15
    assert any("shell" in w for w in result.warnings)


# ─────────────────────────────────────────────────────────────────────────────
# Self-registration
# ─────────────────────────────────────────────────────────────────────────────


def test_parser_registered():
    from app.adapters.official_company import _CUSTOM_REGISTRY_BY_KEY

    assert "KAKAO_CAREERS" in _CUSTOM_REGISTRY_BY_KEY
    assert isinstance(
        _CUSTOM_REGISTRY_BY_KEY["KAKAO_CAREERS"], KakaoCareersParser
    )


# ─────────────────────────────────────────────────────────────────────────────
# Live smoke test
# ─────────────────────────────────────────────────────────────────────────────


@pytest.mark.external
async def test_kakao_live_smoke():
    """Fetch up to 5 postings from careers.kakao.com and validate.

    Checks:
    - API reachable (not shell response)
    - All postings are P-{id} (카카오 본사)
    - title, source_url, roles populated
    - No S-{id} postings in result
    """
    from app.services.normalization import normalize_many

    src = OfficialCompanySource(
        company_id=4,
        source_type="OFFICIAL_CAREER",
        source_url="https://careers.kakao.com/jobs",
        adapter_type="CUSTOM",
        config_json='{"parser_key":"KAKAO_CAREERS","max_items":5}',
    )
    profile = CompanyProfile(
        id=4, canonical_name="카카오", normalized_name="카카오"
    )

    result = await KakaoCareersParser().fetch(
        src, profile, _options(), _COLLECT_DATE
    )

    for w in result.warnings:
        print(f"  WARN: {w}")

    postings = result.postings
    stats = result.source_stats

    print(
        f"\n[카카오] discovered={stats.discovered if stats else '?'},"
        f" parsed={len(postings)}"
    )
    for p in postings:
        print(
            f"  - {p.source_external_id} | {p.title[:50]!r}"
            f" | exp={p.experience_level!r}"
            f" | emp={p.employment_type!r}"
            f" | roles={p.roles}"
        )

    assert len(postings) > 0, (
        f"No postings returned. Warnings: {result.warnings}"
    )
    for p in postings:
        assert p.source_external_id.startswith("P-"), (
            f"Affiliate S-id leaked: {p.source_external_id}"
        )
        assert p.title.strip(), "Empty title"
        assert p.source_url.startswith(
            "https://careers.kakao.com/jobs/P-"
        ), f"Unexpected source_url: {p.source_url}"
        assert p.roles, f"Empty roles for {p.title!r}"
        assert p.company_name == "카카오"

    normalized = normalize_many(postings)
    if normalized:
        print(
            f"\n  normalize → {normalized[0].title!r}"
            f" content_hash={normalized[0].content_hash}"
        )
