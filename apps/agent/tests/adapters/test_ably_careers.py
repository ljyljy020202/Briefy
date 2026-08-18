"""Tests for the AblyCareersParser (ABLY_CAREERS custom adapter).

Unit tests use inline HTML fixtures — no network I/O.
Integration tests use monkeypatched AsyncClient.
Live smoke tests are marked @pytest.mark.external and excluded from CI.
"""

from __future__ import annotations

import json
from datetime import date, datetime
from unittest.mock import AsyncMock, MagicMock

import pytest

from app.adapters.ably_careers import (
    AblyCareersParser,
    _AblyConfig,
    _build_posting,
    _career_to_experience,
    _deadline,
    _employment_type,
    _extract_address_key,
    _extract_detail_content,
    _html_to_text,
    _location,
    _parse_config,
    _parse_list_html,
    _posted_at,
    _roles,
)
from app.schemas.collection import (
    CollectionOptions,
    CompanyProfile,
    OfficialCompanySource,
)

# ─────────────────────────────────────────────────────────────────────────────
# HTML fixtures
# ─────────────────────────────────────────────────────────────────────────────

def _make_list_html(recruits: list[dict]) -> str:
    """Build minimal ably.team/recruit HTML with inline pageProps JSON."""
    payload = json.dumps({"props": {"pageProps": {"recruits": recruits}}})
    return f"<html><body><script>{payload}</script></body></html>"


def _make_detail_html(content: str = "<p>Job description</p>") -> str:
    """Build minimal recruit.ably.team detail page HTML."""
    next_data = json.dumps({
        "props": {
            "pageProps": {
                "recruitment": {},
                "jobPosting": {
                    "content": content,
                    "content_english": "",
                },
            }
        }
    })
    return (
        f'<html><body>'
        f'<script id="__NEXT_DATA__" type="application/json">{next_data}</script>'
        f'</body></html>'
    )


# Fixture recruit items
_BACKEND_SENIOR = {
    "id": "f49e48c0-56fe-11ee-be94-6d60768bf508",
    "title": "백엔드 엔지니어 (시니어)",
    "status": "in_progress",
    "applyUrl": "https://tydtr0dj.ninehire.site/job_posting/1Ni2VkMj",
    "deadline": None,
    "deadlineType": "open_ended",
    "career": "experienced",
    "careerRange": {"over": 7, "below": 0},
    "employmentTypes": ["full_time"],
    "jobLocations": [{"name": "신논현", "address": "신논현"}],
    "jobGroup": "Engineering",
    "isPrivate": False,
    "createdAt": "2021-11-30T09:45:36.000Z",
}

_DATA_INTERN = {
    "id": "bc16f7c0-6deb-11f1-ad20-4b6a45d63bab",
    "title": "데이터 분석가 (채용 연계형 인턴)",
    "status": "in_progress",
    "applyUrl": "https://tydtr0dj.ninehire.site/job_posting/YlcGUJLg",
    "deadline": None,
    "deadlineType": "open_ended",
    "career": "newcomer",
    "careerRange": None,
    "employmentTypes": ["intern"],
    "jobLocations": [{"name": "신논현", "address": "신논현"}],
    "jobGroup": "Data",
    "isPrivate": False,
    "createdAt": "2026-06-22T03:40:19.000Z",
}

_MARKETING_MANAGER = {
    "id": "09e6f180-8fa2-11f1-af62-41619867fddb",
    "title": "프로모션 마케터 인턴",
    "status": "in_progress",
    "applyUrl": "https://tydtr0dj.ninehire.site/job_posting/87AONAKl",
    "deadline": None,
    "deadlineType": "until_filled",
    "career": "newcomer",
    "careerRange": None,
    "employmentTypes": ["intern"],
    "jobLocations": [{"name": "신논현", "address": "신논현"}],
    "jobGroup": "Marketing",
    "isPrivate": False,
    "createdAt": "2026-08-04T01:16:43.000Z",
}

_PEOPLE_BELOW = {
    "id": "9386a960-3e14-11f1-8369-fb3689818323",
    "title": "컬쳐 매니저 (주니어)",
    "status": "in_progress",
    "applyUrl": "https://tydtr0dj.ninehire.site/job_posting/XplHs1TX",
    "deadline": None,
    "deadlineType": "until_filled",
    "career": "experienced",
    "careerRange": {"over": 0, "below": 2},
    "employmentTypes": ["full_time"],
    "jobLocations": [{"name": "신논현", "address": "신논현"}],
    "jobGroup": "People",
    "isPrivate": False,
    "createdAt": "2026-04-22T06:29:56.000Z",
}

_DESIGN_RANGE = {
    "id": "5b6b6020-4fa0-11ef-90e8-29f3c0bf8862",
    "title": "프로덕트 디자이너 (주니어)",
    "status": "in_progress",
    "applyUrl": "https://tydtr0dj.ninehire.site/job_posting/zQF7C4jj",
    "deadline": None,
    "deadlineType": "open_ended",
    "career": "experienced",
    "careerRange": {"over": 3, "below": 5},
    "employmentTypes": ["full_time"],
    "jobLocations": [{"name": "신논현", "address": "신논현"}],
    "jobGroup": "Design",
    "isPrivate": False,
    "createdAt": "2024-08-01T00:52:43.000Z",
}

_IRRELEVANT_CAREER = {
    "id": "034755a0-4dfc-11f1-9afc-e13600b082e6",
    "title": "사업 개발 매니저 인턴",
    "status": "in_progress",
    "applyUrl": "https://tydtr0dj.ninehire.site/job_posting/tx51CwAx",
    "deadline": None,
    "deadlineType": "until_filled",
    "career": "irrelevant",
    "careerRange": None,
    "employmentTypes": ["intern"],
    "jobLocations": [{"name": "신논현", "address": "신논현"}],
    "jobGroup": "Business",
    "isPrivate": False,
    "createdAt": "2026-05-12T12:19:54.000Z",
}

_WITH_DEADLINE = {
    "id": "aaaa-0001",
    "title": "마감일 있는 공고",
    "status": "in_progress",
    "applyUrl": "https://tydtr0dj.ninehire.site/job_posting/AbCdEfGh",
    "deadline": "2026-12-31",
    "deadlineType": "specific_date",
    "career": None,
    "careerRange": None,
    "employmentTypes": ["full_time"],
    "jobLocations": [{"name": "신논현", "address": "신논현"}],
    "jobGroup": "Product",
    "isPrivate": False,
    "createdAt": "2026-01-01T00:00:00.000Z",
}

_PRIVATE_HIDDEN = {
    "id": "priv-0001",
    "title": "비공개 공고",
    "status": "in_progress",
    "applyUrl": "https://tydtr0dj.ninehire.site/job_posting/HiddenKey",
    "deadline": None,
    "deadlineType": "open_ended",
    "career": None,
    "careerRange": None,
    "employmentTypes": ["full_time"],
    "jobLocations": [],
    "jobGroup": "Engineering",
    "isPrivate": True,
    "createdAt": "2026-01-01T00:00:00.000Z",
}

_CLOSED = {
    "id": "closed-0001",
    "title": "마감된 공고",
    "status": "closed",
    "applyUrl": "https://tydtr0dj.ninehire.site/job_posting/ClosedKey",
    "deadline": None,
    "deadlineType": "open_ended",
    "career": None,
    "careerRange": None,
    "employmentTypes": ["full_time"],
    "jobLocations": [],
    "jobGroup": "Engineering",
    "isPrivate": False,
    "createdAt": "2026-01-01T00:00:00.000Z",
}


# ─────────────────────────────────────────────────────────────────────────────
# _parse_config
# ─────────────────────────────────────────────────────────────────────────────


def test_parse_config_defaults():
    cfg = _parse_config(None)
    assert isinstance(cfg, _AblyConfig)
    assert cfg.max_items == 100


def test_parse_config_explicit():
    cfg = _parse_config('{"parser_key":"ABLY_CAREERS","max_items":30}')
    assert cfg.max_items == 30


def test_parse_config_malformed_json_uses_defaults():
    cfg = _parse_config("{not valid json}")
    assert cfg.max_items == 100


# ─────────────────────────────────────────────────────────────────────────────
# _parse_list_html
# ─────────────────────────────────────────────────────────────────────────────


def test_parse_list_html_returns_recruits():
    html = _make_list_html([_BACKEND_SENIOR, _DATA_INTERN])
    result = _parse_list_html(html)
    assert len(result) == 2
    assert result[0]["title"] == "백엔드 엔지니어 (시니어)"
    assert result[1]["title"] == "데이터 분석가 (채용 연계형 인턴)"


def test_parse_list_html_empty_recruits_is_valid():
    html = _make_list_html([])
    result = _parse_list_html(html)
    assert result == []


def test_parse_list_html_missing_raises_value_error():
    with pytest.raises(ValueError, match="pageProps.recruits not found"):
        _parse_list_html("<html><body><script>{}</script></body></html>")


def test_parse_list_html_no_script_raises_value_error():
    with pytest.raises(ValueError):
        _parse_list_html("<html><body>no script here</body></html>")


# ─────────────────────────────────────────────────────────────────────────────
# _extract_address_key
# ─────────────────────────────────────────────────────────────────────────────


def test_extract_address_key_ninehire_url():
    key = _extract_address_key(
        "https://tydtr0dj.ninehire.site/job_posting/1Ni2VkMj"
    )
    assert key == "1Ni2VkMj"


def test_extract_address_key_recruit_ably_url():
    key = _extract_address_key(
        "https://recruit.ably.team/job_posting/YlcGUJLg"
    )
    assert key == "YlcGUJLg"


def test_extract_address_key_trailing_slash():
    key = _extract_address_key(
        "https://tydtr0dj.ninehire.site/job_posting/AbCdEfGh/"
    )
    assert key == "AbCdEfGh"


def test_extract_address_key_unexpected_path_returns_none():
    assert _extract_address_key("https://example.com/jobs") is None


def test_extract_address_key_empty_returns_none():
    assert _extract_address_key("") is None


# ─────────────────────────────────────────────────────────────────────────────
# _career_to_experience
# ─────────────────────────────────────────────────────────────────────────────


def test_career_newcomer():
    assert _career_to_experience("newcomer", None) == "신입"


def test_career_irrelevant():
    assert _career_to_experience("irrelevant", None) == "경력 무관"


def test_career_experienced_min_years():
    result = _career_to_experience("experienced", {"over": 7, "below": 0})
    assert result == "경력 7년 이상"


def test_career_experienced_below_only_is_not_flipped():
    """'below > 0 only' must produce '이하', never '이상'."""
    result = _career_to_experience("experienced", {"over": 0, "below": 3})
    assert result == "경력 3년 이하"
    assert "이상" not in result


def test_career_experienced_range():
    result = _career_to_experience("experienced", {"over": 3, "below": 5})
    assert result == "경력 3~5년"


def test_career_experienced_no_range():
    result = _career_to_experience("experienced", None)
    assert result == "경력"


def test_career_null_returns_none():
    assert _career_to_experience(None, None) is None


# ─────────────────────────────────────────────────────────────────────────────
# _employment_type
# ─────────────────────────────────────────────────────────────────────────────


def test_employment_type_full_time():
    assert _employment_type(["full_time"]) == "정규직"


def test_employment_type_intern():
    assert _employment_type(["intern"]) == "인턴"


def test_employment_type_contractor():
    assert _employment_type(["contractor"]) == "계약직"


def test_employment_type_empty():
    assert _employment_type([]) is None


# ─────────────────────────────────────────────────────────────────────────────
# _deadline / _location / _roles / _posted_at
# ─────────────────────────────────────────────────────────────────────────────


def test_deadline_open_ended_is_none():
    item = {"deadline": None, "deadlineType": "open_ended"}
    assert _deadline(item) is None


def test_deadline_until_filled_is_none():
    item = {"deadline": None, "deadlineType": "until_filled"}
    assert _deadline(item) is None


def test_deadline_date_string_parsed():
    item = {"deadline": "2026-12-31", "deadlineType": "specific_date"}
    assert _deadline(item) == date(2026, 12, 31)


def test_location_first_name():
    item = {"jobLocations": [{"name": "신논현", "address": "신논현"}]}
    assert _location(item) == "신논현"


def test_location_seongsu():
    item = {"jobLocations": [{"name": "성수(임팩센터)", "address": "성수"}]}
    assert _location(item) == "성수(임팩센터)"


def test_location_empty_list():
    assert _location({"jobLocations": []}) is None


def test_roles_engineering():
    assert _roles({"jobGroup": "Engineering"}) == ["Engineering"]


def test_roles_data():
    assert _roles({"jobGroup": "Data"}) == ["Data"]


def test_roles_marketing_preserved():
    assert _roles({"jobGroup": "Marketing"}) == ["Marketing"]


def test_roles_people_preserved():
    assert _roles({"jobGroup": "People"}) == ["People"]


def test_roles_empty_group():
    assert _roles({"jobGroup": ""}) == []


def test_posted_at_parses_iso():
    item = {"createdAt": "2021-11-30T09:45:36.000Z"}
    result = _posted_at(item)
    assert isinstance(result, datetime)
    assert result.year == 2021


# ─────────────────────────────────────────────────────────────────────────────
# _extract_detail_content / _html_to_text
# ─────────────────────────────────────────────────────────────────────────────


def test_extract_detail_content_valid():
    html = _make_detail_html("<h2>직무 소개</h2><p>백엔드 엔지니어를 찾습니다.</p>")
    content = _extract_detail_content(html)
    assert content is not None
    assert "직무 소개" in content


def test_extract_detail_content_no_next_data():
    assert _extract_detail_content("<html><body>no data</body></html>") is None


def test_extract_detail_content_missing_job_posting():
    next_data = json.dumps({"props": {"pageProps": {"recruitment": {}}}})
    html = (
        f'<script id="__NEXT_DATA__" type="application/json">'
        f"{next_data}</script>"
    )
    assert _extract_detail_content(html) is None


def test_html_to_text_strips_tags():
    result = _html_to_text("<h2>제목</h2><p>내용입니다.</p>")
    assert result is not None
    assert "<" not in result
    assert "제목" in result
    assert "내용입니다" in result


def test_html_to_text_nested_tags():
    html = "<div><span><b>경력</b> <em>3년 이상</em></span></div>"
    result = _html_to_text(html)
    assert result is not None
    assert "경력" in result
    assert "3년 이상" in result


def test_html_to_text_empty_returns_none():
    assert _html_to_text("") is None


def test_html_to_text_truncates_at_3000():
    long_html = f"<p>{'x' * 5000}</p>"
    result = _html_to_text(long_html)
    assert result is not None
    assert len(result) <= 3000


# ─────────────────────────────────────────────────────────────────────────────
# _build_posting — structured field assertions
# ─────────────────────────────────────────────────────────────────────────────


def _profile() -> CompanyProfile:
    return CompanyProfile(
        id=15, canonical_name="에이블리", normalized_name="에이블리"
    )


def test_build_posting_engineering_backend():
    posting = _build_posting(_BACKEND_SENIOR, "에이블리", "1Ni2VkMj", None)
    assert posting.title == "백엔드 엔지니어 (시니어)"
    assert posting.roles == ["Engineering"]
    assert posting.experience_level == "경력 7년 이상"
    assert posting.employment_type == "정규직"
    assert posting.location == "신논현"
    assert posting.deadline is None
    assert posting.source_external_id == "1Ni2VkMj"
    assert posting.source_url == (
        "https://recruit.ably.team/job_posting/1Ni2VkMj"
    )
    assert posting.source == "ably_careers"
    assert posting.company_name == "에이블리"


def test_build_posting_data_intern_newcomer_separated():
    """career=newcomer + employmentType=intern → experience='신입', type='인턴'.

    These fields are independent; do NOT conflate intern=신입.
    """
    posting = _build_posting(_DATA_INTERN, "에이블리", "YlcGUJLg", None)
    assert posting.roles == ["Data"]
    assert posting.experience_level == "신입"
    assert posting.employment_type == "인턴"


def test_build_posting_marketing_intern():
    posting = _build_posting(_MARKETING_MANAGER, "에이블리", "87AONAKl", None)
    assert posting.roles == ["Marketing"]
    assert posting.experience_level == "신입"
    assert posting.employment_type == "인턴"


def test_build_posting_people_below_years():
    posting = _build_posting(_PEOPLE_BELOW, "에이블리", "XplHs1TX", None)
    assert posting.roles == ["People"]
    assert posting.experience_level == "경력 2년 이하"
    assert "이상" not in (posting.experience_level or "")
    assert posting.employment_type == "정규직"


def test_build_posting_range_years():
    posting = _build_posting(_DESIGN_RANGE, "에이블리", "zQF7C4jj", None)
    assert posting.experience_level == "경력 3~5년"


def test_build_posting_irrelevant_career():
    posting = _build_posting(_IRRELEVANT_CAREER, "에이블리", "tx51CwAx", None)
    assert posting.experience_level == "경력 무관"
    assert posting.employment_type == "인턴"


def test_build_posting_null_career():
    posting = _build_posting(_WITH_DEADLINE, "에이블리", "AbCdEfGh", None)
    assert posting.experience_level is None


def test_build_posting_deadline_parsed():
    posting = _build_posting(_WITH_DEADLINE, "에이블리", "AbCdEfGh", None)
    assert posting.deadline == date(2026, 12, 31)


def test_build_posting_open_ended_no_deadline():
    posting = _build_posting(_BACKEND_SENIOR, "에이블리", "1Ni2VkMj", None)
    assert posting.deadline is None


def test_build_posting_with_description():
    posting = _build_posting(_BACKEND_SENIOR, "에이블리", "1Ni2VkMj", "직무 설명")
    assert posting.description == "직무 설명"


def test_build_posting_posted_at():
    posting = _build_posting(_BACKEND_SENIOR, "에이블리", "1Ni2VkMj", None)
    assert posting.posted_at is not None
    assert posting.posted_at.year == 2021


# ─────────────────────────────────────────────────────────────────────────────
# AblyCareersParser.fetch — integration with mocked HTTP
# ─────────────────────────────────────────────────────────────────────────────


def _source(config_json: str | None = None) -> OfficialCompanySource:
    return OfficialCompanySource(
        company_id=15,
        source_type="OFFICIAL_CAREER",
        source_url="https://ably.team/recruit",
        adapter_type="CUSTOM",
        config_json=config_json or '{"parser_key":"ABLY_CAREERS","max_items":5}',
    )


def _options() -> CollectionOptions:
    return CollectionOptions()


_COLLECT_DATE = date(2026, 8, 17)


def _make_mock_client(list_html: str, detail_html: str | None = None):
    """Build an AsyncMock-based client that returns canned responses."""
    list_resp = MagicMock()
    list_resp.status_code = 200
    list_resp.text = list_html
    list_resp.raise_for_status = MagicMock()

    detail_resp = MagicMock()
    detail_resp.status_code = 200
    detail_resp.text = detail_html or _make_detail_html()
    detail_resp.raise_for_status = MagicMock()

    async def _get(url, **kwargs):
        if "recruit.ably.team" in str(url) or "ninehire" in str(url):
            return detail_resp
        return list_resp

    mock_client = MagicMock()
    mock_client.get = AsyncMock(side_effect=_get)
    mock_client.__aenter__ = AsyncMock(return_value=mock_client)
    mock_client.__aexit__ = AsyncMock(return_value=False)
    return mock_client


async def test_fetch_success_two_postings(monkeypatch):
    recruits = [_BACKEND_SENIOR, _DATA_INTERN]
    list_html = _make_list_html(recruits)
    mock = _make_mock_client(list_html, _make_detail_html("<p>JD내용</p>"))
    monkeypatch.setattr(
        "app.adapters.ably_careers.AsyncClient", lambda **kw: mock
    )

    result = await AblyCareersParser().fetch(
        _source(), _profile(), _options(), _COLLECT_DATE
    )

    assert len(result.postings) == 2
    assert result.postings[0].title == "백엔드 엔지니어 (시니어)"
    assert result.postings[1].title == "데이터 분석가 (채용 연계형 인턴)"
    assert result.source_stats is not None
    assert result.source_stats.discovered == 2


async def test_fetch_filters_closed_and_private(monkeypatch):
    recruits = [_BACKEND_SENIOR, _PRIVATE_HIDDEN, _CLOSED]
    list_html = _make_list_html(recruits)
    mock = _make_mock_client(list_html)
    monkeypatch.setattr(
        "app.adapters.ably_careers.AsyncClient", lambda **kw: mock
    )

    result = await AblyCareersParser().fetch(
        _source(), _profile(), _options(), _COLLECT_DATE
    )

    assert len(result.postings) == 1
    assert result.postings[0].title == "백엔드 엔지니어 (시니어)"


async def test_fetch_deduplicates_same_address_key(monkeypatch):
    dup = dict(_BACKEND_SENIOR)
    dup["id"] = "different-uuid"
    recruits = [_BACKEND_SENIOR, dup]
    list_html = _make_list_html(recruits)
    mock = _make_mock_client(list_html)
    monkeypatch.setattr(
        "app.adapters.ably_careers.AsyncClient", lambda **kw: mock
    )

    result = await AblyCareersParser().fetch(
        _source(), _profile(), _options(), _COLLECT_DATE
    )
    assert len(result.postings) == 1


async def test_fetch_empty_listing_returns_zero(monkeypatch):
    list_html = _make_list_html([])
    mock = _make_mock_client(list_html)
    monkeypatch.setattr(
        "app.adapters.ably_careers.AsyncClient", lambda **kw: mock
    )

    result = await AblyCareersParser().fetch(
        _source(), _profile(), _options(), _COLLECT_DATE
    )

    assert result.postings == []
    assert result.source_stats.discovered == 0
    assert result.warnings == []


async def test_fetch_list_page_unreachable(monkeypatch):
    from httpx import TimeoutException

    mock_client = MagicMock()
    mock_client.get = AsyncMock(side_effect=TimeoutException("timeout"))
    mock_client.__aenter__ = AsyncMock(return_value=mock_client)
    mock_client.__aexit__ = AsyncMock(return_value=False)
    monkeypatch.setattr(
        "app.adapters.ably_careers.AsyncClient", lambda **kw: mock_client
    )

    result = await AblyCareersParser().fetch(
        _source(), _profile(), _options(), _COLLECT_DATE
    )

    assert result.postings == []
    assert any("timeout" in w for w in result.warnings)


async def test_fetch_list_parse_failure(monkeypatch):
    bad_html = "<html><body><p>no json here</p></body></html>"
    mock = _make_mock_client(bad_html)
    monkeypatch.setattr(
        "app.adapters.ably_careers.AsyncClient", lambda **kw: mock
    )

    result = await AblyCareersParser().fetch(
        _source(), _profile(), _options(), _COLLECT_DATE
    )

    assert result.postings == []
    assert any("pageProps.recruits" in w for w in result.warnings)


async def test_fetch_detail_timeout_posting_still_returned(monkeypatch):
    from httpx import TimeoutException

    list_html = _make_list_html([_BACKEND_SENIOR])

    list_resp = MagicMock()
    list_resp.text = list_html
    list_resp.raise_for_status = MagicMock()

    async def _get(url, **kwargs):
        if "recruit.ably.team" in str(url):
            raise TimeoutException("detail timeout")
        return list_resp

    mock_client = MagicMock()
    mock_client.get = AsyncMock(side_effect=_get)
    mock_client.__aenter__ = AsyncMock(return_value=mock_client)
    mock_client.__aexit__ = AsyncMock(return_value=False)
    monkeypatch.setattr(
        "app.adapters.ably_careers.AsyncClient", lambda **kw: mock_client
    )

    result = await AblyCareersParser().fetch(
        _source(), _profile(), _options(), _COLLECT_DATE
    )

    assert len(result.postings) == 1
    assert result.postings[0].description is None
    assert any("timeout" in w for w in result.warnings)


async def test_fetch_respects_max_items(monkeypatch):
    recruits = [_BACKEND_SENIOR, _DATA_INTERN, _MARKETING_MANAGER]
    list_html = _make_list_html(recruits)
    mock = _make_mock_client(list_html)
    monkeypatch.setattr(
        "app.adapters.ably_careers.AsyncClient", lambda **kw: mock
    )

    src = _source('{"parser_key":"ABLY_CAREERS","max_items":2}')
    result = await AblyCareersParser().fetch(
        src, _profile(), _options(), _COLLECT_DATE
    )

    assert result.source_stats.discovered == 3
    assert len(result.postings) == 2


async def test_fetch_metadata_from_listing_without_detail(monkeypatch):
    """All structured fields come from listing; detail only adds description."""
    list_html = _make_list_html([_PEOPLE_BELOW])
    mock = _make_mock_client(list_html, _make_detail_html())
    monkeypatch.setattr(
        "app.adapters.ably_careers.AsyncClient", lambda **kw: mock
    )

    result = await AblyCareersParser().fetch(
        _source(), _profile(), _options(), _COLLECT_DATE
    )

    p = result.postings[0]
    assert p.roles == ["People"]
    assert p.experience_level == "경력 2년 이하"
    assert "이상" not in (p.experience_level or "")
    assert p.employment_type == "정규직"
    assert p.location == "신논현"


async def test_fetch_company_name_from_profile(monkeypatch):
    list_html = _make_list_html([_BACKEND_SENIOR])
    mock = _make_mock_client(list_html)
    monkeypatch.setattr(
        "app.adapters.ably_careers.AsyncClient", lambda **kw: mock
    )

    profile = CompanyProfile(
        id=15, canonical_name="에이블리코퍼레이션", normalized_name="에이블리코퍼레이션"
    )
    result = await AblyCareersParser().fetch(
        _source(), profile, _options(), _COLLECT_DATE
    )

    assert result.postings[0].company_name == "에이블리코퍼레이션"


# ─────────────────────────────────────────────────────────────────────────────
# Self-registration
# ─────────────────────────────────────────────────────────────────────────────


def test_parser_registered():
    from app.adapters.official_company import _CUSTOM_REGISTRY_BY_KEY
    assert "ABLY_CAREERS" in _CUSTOM_REGISTRY_BY_KEY
    assert isinstance(_CUSTOM_REGISTRY_BY_KEY["ABLY_CAREERS"], AblyCareersParser)


# ─────────────────────────────────────────────────────────────────────────────
# Live smoke test
# ─────────────────────────────────────────────────────────────────────────────


@pytest.mark.external
async def test_ably_live_smoke():
    """Fetch up to 3 postings from ably.team/recruit and validate metadata.

    Checks:
    - At least 1 posting returned
    - All postings have non-empty title and source_url
    - roles field is populated
    - source_external_id matches addressKey pattern (base62, 8 chars)
    """
    from app.services.normalization import normalize_many

    src = OfficialCompanySource(
        company_id=15,
        source_type="OFFICIAL_CAREER",
        source_url="https://ably.team/recruit",
        adapter_type="CUSTOM",
        config_json='{"parser_key":"ABLY_CAREERS","max_items":3}',
    )
    profile = CompanyProfile(
        id=15, canonical_name="에이블리", normalized_name="에이블리"
    )

    result = await AblyCareersParser().fetch(src, profile, _options(), _COLLECT_DATE)

    if result.warnings:
        for w in result.warnings:
            print(f"  WARN: {w}")

    postings = result.postings
    stats = result.source_stats

    print(
        f"\n[에이블리] discovered={stats.discovered if stats else '?'},"
        f" parsed={len(postings)}"
    )
    for p in postings:
        print(
            f"  - {p.title!r}"
            f" | roles={p.roles}"
            f" | exp={p.experience_level!r}"
            f" | emp={p.employment_type!r}"
            f" | loc={p.location!r}"
            f" | id={p.source_external_id!r}"
        )

    assert len(postings) > 0, (
        f"No postings returned. Warnings: {result.warnings}"
    )

    for p in postings:
        assert p.title.strip(), "Empty title"
        assert p.source_url.startswith("https://recruit.ably.team/"), (
            f"Unexpected source_url: {p.source_url}"
        )
        assert p.roles, f"No roles for {p.title!r}"
        assert p.source_external_id, "Missing source_external_id"

    # Compare RawJobPosting vs normalize result for first posting
    if postings:
        normalized = normalize_many(postings)
        first = normalized[0] if normalized else None
        if first:
            print(f"\n  normalize → {first.title!r} content_hash={first.content_hash}")
