"""Tests for LG_CNS_CAREERS adapter."""
from __future__ import annotations

import json
from datetime import date, datetime
from typing import Any
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from app.adapters.lg_cns_careers import (
    LgCnsCareersParser,
    _build_posting,
    _employment_type,
    _experience_level,
    _location_from_recs,
    _parse_config,
    _parse_date,
    _parse_rec_list,
    _RecInfo,
    _roles_from_recs,
)
from app.adapters.official_company import _CUSTOM_REGISTRY_BY_KEY
from app.schemas.collection import (
    CollectionOptions,
    CompanyProfile,
    OfficialCompanySource,
    SeedKeywords,
)

# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------


def _make_profile(name: str = "LG CNS") -> CompanyProfile:
    return CompanyProfile(id=27, canonical_name=name, normalized_name=name.lower())


def _make_source(**kwargs: Any) -> OfficialCompanySource:
    cfg = {"parser_key": "LG_CNS_CAREERS", **kwargs}
    return OfficialCompanySource(
        company_id=27,
        source_type="OFFICIAL_CAREER",
        source_url="https://careers.lg.com/apply",
        adapter_type="CUSTOM",
        config_json=json.dumps(cfg),
    )


def _make_options() -> CollectionOptions:
    return CollectionOptions()


_JOB_ITEM: dict[str, Any] = {
    "jobNoticeId": 1001310,
    "careerTypeCode": "B",
    "careerTypeName": "경력",
    "companyCode": "CNS",
    "companyName": "LG CNS",
    "jobNoticeName": "[LG CNS] 보안 분야 전 영역 전문가 모집(경력)",
    "noticeStatus": "POSTING",
    "jobGroupName": "IT서비스",
}

_DETAIL: dict[str, Any] = {
    "jobNoticeId": 1001310,
    "jobNoticeName": "[LG CNS] 보안 분야 전 영역 전문가 모집(경력)",
    "companyCode": "CNS",
    "companyName": "LG CNS",
    "recStartDate": "2026.08.12 09:00",
    "recEndDate": "2026.08.31 23:00",
    "careerTypeCode": "B",
    "careerTypeName": "경력",
    "jobGroupSh": "IT서비스",
    "qualForAppInfo": (
        "▣ 각 모집분야별 업무/ 자격요건/ 우대사항을 확인하시어 지원해주시기 바랍니다."
    ),
}

_RECS_RAW: list[dict[str, Any]] = [
    {
        "jobNoticeId": 1001310,
        "recSectorId": 1,
        "orgName": "보안사업담당",
        "jobGroupName": "AI보안",
        "locationName": "마곡 및 기타",
        "detailContext": "<p>...</p>",
    },
    {
        "jobNoticeId": 1001310,
        "recSectorId": 3,
        "orgName": "보안사업담당",
        "jobGroupName": "정보보안컨설팅",
        "locationName": "마곡 및 기타",
        "detailContext": "<p>...</p>",
    },
]

_INTERN_ITEM: dict[str, Any] = {
    "jobNoticeId": 1000834,
    "careerTypeCode": "C",
    "careerTypeName": "인턴",
    "companyCode": "CNS",
    "companyName": "LG CNS",
    "jobNoticeName": "[인턴] LG CNS Global Internship(외국인) 상시 인재 등록 공고",
    "jobGroupName": "IT서비스",
}

_MIXED_ITEM: dict[str, Any] = {
    "jobNoticeId": 1000409,
    "careerTypeCode": "D",
    "careerTypeName": "신입/경력",
    "companyCode": "CNS",
    "companyName": "LG CNS",
    "jobNoticeName": "[LG CNS] Global 해외 석박사 AX 인재 상시 등록 공고",
    "jobGroupName": "IT서비스",
}


def _list_resp(jobs: list[dict[str, Any]]) -> MagicMock:
    m = MagicMock()
    m.raise_for_status = MagicMock()
    m.status_code = 200
    m.json.return_value = {
        "status": "S",
        "data": {
            "jobNoticeList": jobs,
            "listCount": len(jobs),
        },
    }
    return m


def _detail_resp(detail: dict[str, Any], recs: list[dict[str, Any]]) -> MagicMock:
    m = MagicMock()
    m.raise_for_status = MagicMock()
    m.status_code = 200
    m.json.return_value = {
        "status": "S",
        "data": {
            "jobNoticesDetail": {
                "jobNoticesDetail": detail,
                "recList": recs,
            }
        },
    }
    return m


# ---------------------------------------------------------------------------
# TestParseConfig
# ---------------------------------------------------------------------------


class TestParseConfig:
    def test_defaults_when_null(self) -> None:
        max_d, max_f = _parse_config(None)
        assert max_d == 50
        assert max_f == 30

    def test_defaults_when_empty_string(self) -> None:
        max_d, max_f = _parse_config("")
        assert max_d == 50
        assert max_f == 30

    def test_custom_values(self) -> None:
        cfg = json.dumps({"max_discover": 10, "max_fetch": 5})
        max_d, max_f = _parse_config(cfg)
        assert max_d == 10
        assert max_f == 5

    def test_invalid_json_falls_back_to_defaults(self) -> None:
        max_d, max_f = _parse_config("{not valid json")
        assert max_d == 50
        assert max_f == 30


# ---------------------------------------------------------------------------
# TestParseDate
# ---------------------------------------------------------------------------


class TestParseDate:
    def test_datetime_format(self) -> None:
        assert _parse_date("2026.08.31 23:00") == date(2026, 8, 31)

    def test_date_only_format(self) -> None:
        assert _parse_date("2026.08.12") == date(2026, 8, 12)

    def test_none_input(self) -> None:
        assert _parse_date(None) is None

    def test_empty_string(self) -> None:
        assert _parse_date("") is None

    def test_non_date_string(self) -> None:
        assert _parse_date("채용시 마감") is None

    def test_start_date(self) -> None:
        assert _parse_date("2026.08.12 09:00") == date(2026, 8, 12)


# ---------------------------------------------------------------------------
# TestExperienceLevel
# ---------------------------------------------------------------------------


class TestExperienceLevel:
    def test_career_code_b(self) -> None:
        assert _experience_level("B", "경력") == "경력"

    def test_career_code_c_intern(self) -> None:
        assert _experience_level("C", "인턴") == "인턴"

    def test_career_code_d_mixed(self) -> None:
        assert _experience_level("D", "신입/경력") == "신입·경력"

    def test_career_code_a_newcomer(self) -> None:
        assert _experience_level("A", "신입") == "신입"

    def test_career_code_e_scholarship(self) -> None:
        assert _experience_level("E", "산학장학생") == "산학장학생"

    def test_unknown_code_falls_back_to_name(self) -> None:
        assert _experience_level("Z", "특수계약") == "특수계약"


# ---------------------------------------------------------------------------
# TestEmploymentType
# ---------------------------------------------------------------------------


class TestEmploymentType:
    def test_intern_code(self) -> None:
        assert _employment_type("C") == "인턴"

    def test_career_code(self) -> None:
        assert _employment_type("B") == "정규직"

    def test_newcomer_code(self) -> None:
        assert _employment_type("A") == "정규직"

    def test_mixed_code(self) -> None:
        assert _employment_type("D") == "정규직"


# ---------------------------------------------------------------------------
# TestParseRecList
# ---------------------------------------------------------------------------


class TestParseRecList:
    def test_parses_job_group_and_location(self) -> None:
        recs = _parse_rec_list(_RECS_RAW)
        assert len(recs) == 2
        assert recs[0].job_group_name == "AI보안"
        assert recs[0].location_name == "마곡 및 기타"
        assert recs[1].job_group_name == "정보보안컨설팅"

    def test_empty_location_becomes_none(self) -> None:
        raw = [{"jobGroupName": "AI보안", "locationName": ""}]
        recs = _parse_rec_list(raw)
        assert recs[0].location_name is None

    def test_null_location_becomes_none(self) -> None:
        raw = [{"jobGroupName": "AI보안", "locationName": None}]
        recs = _parse_rec_list(raw)
        assert recs[0].location_name is None

    def test_empty_list(self) -> None:
        assert _parse_rec_list([]) == []


# ---------------------------------------------------------------------------
# TestRolesFromRecs
# ---------------------------------------------------------------------------


class TestRolesFromRecs:
    def test_returns_specific_roles(self) -> None:
        recs = _parse_rec_list(_RECS_RAW)
        roles = _roles_from_recs(recs)
        assert "AI보안" in roles
        assert "정보보안컨설팅" in roles

    def test_excludes_broad_categories(self) -> None:
        raw = [
            {"jobGroupName": "IT서비스", "locationName": None},
            {"jobGroupName": "IT Service", "locationName": None},
            {"jobGroupName": "경영지원", "locationName": None},
            {"jobGroupName": "AI보안", "locationName": None},
        ]
        recs = _parse_rec_list(raw)
        roles = _roles_from_recs(recs)
        assert roles == ["AI보안"]

    def test_deduplicates_roles(self) -> None:
        raw = [
            {"jobGroupName": "AI보안", "locationName": None},
            {"jobGroupName": "AI보안", "locationName": None},
        ]
        recs = _parse_rec_list(raw)
        roles = _roles_from_recs(recs)
        assert roles == ["AI보안"]

    def test_empty_recs_returns_empty(self) -> None:
        assert _roles_from_recs([]) == []

    def test_empty_job_group_name_excluded(self) -> None:
        raw = [{"jobGroupName": "", "locationName": None}]
        recs = _parse_rec_list(raw)
        roles = _roles_from_recs(recs)
        assert roles == []


# ---------------------------------------------------------------------------
# TestLocationFromRecs
# ---------------------------------------------------------------------------


class TestLocationFromRecs:
    def test_returns_first_location(self) -> None:
        recs = _parse_rec_list(_RECS_RAW)
        assert _location_from_recs(recs) == "마곡 및 기타"

    def test_none_when_all_null(self) -> None:
        recs = [_RecInfo("AI보안", None), _RecInfo("보안", None)]
        assert _location_from_recs(recs) is None

    def test_skips_leading_none(self) -> None:
        recs = [
            _RecInfo("AI보안", None),
            _RecInfo("보안", "서울"),
        ]
        assert _location_from_recs(recs) == "서울"

    def test_empty_recs_returns_none(self) -> None:
        assert _location_from_recs([]) is None


# ---------------------------------------------------------------------------
# TestBuildPosting
# ---------------------------------------------------------------------------


class TestBuildPosting:
    def test_basic_fields(self) -> None:
        recs = _parse_rec_list(_RECS_RAW)
        profile = _make_profile()
        p = _build_posting(1001310, _JOB_ITEM, _DETAIL, recs, profile)

        assert p.source == "lg_cns_careers"
        assert p.source_external_id == "1001310"
        assert p.source_url == "https://careers.lg.com/apply/1001310"
        assert p.company_name == "LG CNS"
        assert p.title == "[LG CNS] 보안 분야 전 영역 전문가 모집(경력)"
        assert p.experience_level == "경력"
        assert p.employment_type == "정규직"
        assert p.posted_at == datetime(2026, 8, 12)
        assert p.deadline == date(2026, 8, 31)
        assert "AI보안" in p.roles
        assert "정보보안컨설팅" in p.roles
        assert p.location == "마곡 및 기타"
        assert p.description is not None

    def test_intern_posting(self) -> None:
        intern_detail = {**_DETAIL, **{
            "jobNoticeId": 1000834,
            "careerTypeCode": "C",
            "careerTypeName": "인턴",
            "companyName": "LG CNS",
        }}
        profile = _make_profile()
        p = _build_posting(1000834, _INTERN_ITEM, intern_detail, [], profile)
        assert p.employment_type == "인턴"
        assert p.experience_level == "인턴"

    def test_mixed_career_posting(self) -> None:
        mixed_detail = {**_DETAIL, **{
            "jobNoticeId": 1000409,
            "careerTypeCode": "D",
            "careerTypeName": "신입/경력",
            "companyName": "LG CNS",
        }}
        profile = _make_profile()
        p = _build_posting(1000409, _MIXED_ITEM, mixed_detail, [], profile)
        assert p.experience_level == "신입·경력"
        assert p.employment_type == "정규직"

    def test_empty_rec_list_yields_empty_roles(self) -> None:
        profile = _make_profile()
        p = _build_posting(1001310, _JOB_ITEM, _DETAIL, [], profile)
        assert p.roles == []

    def test_only_broad_roles_yields_empty_roles(self) -> None:
        raw = [{"jobGroupName": "IT서비스", "locationName": None}]
        broad_recs = _parse_rec_list(raw)
        profile = _make_profile()
        p = _build_posting(1001310, _JOB_ITEM, _DETAIL, broad_recs, profile)
        assert p.roles == []

    def test_company_name_from_profile(self) -> None:
        profile = _make_profile("LG CNS")
        recs = _parse_rec_list(_RECS_RAW)
        p = _build_posting(1001310, _JOB_ITEM, _DETAIL, recs, profile)
        assert p.company_name == "LG CNS"

    def test_no_description_when_qual_empty(self) -> None:
        detail_no_qual = {**_DETAIL, "qualForAppInfo": ""}
        profile = _make_profile()
        recs = _parse_rec_list(_RECS_RAW)
        p = _build_posting(1001310, _JOB_ITEM, detail_no_qual, recs, profile)
        assert p.description is None


# ---------------------------------------------------------------------------
# TestLgCnsCareersParserFetch
# ---------------------------------------------------------------------------

_LIST_PATCH = "app.adapters.lg_cns_careers.httpx.AsyncClient"


class TestLgCnsCareersParserFetch:
    @pytest.mark.asyncio
    async def test_happy_path_single_job(self) -> None:
        source = _make_source()
        profile = _make_profile()

        list_mock = _list_resp([_JOB_ITEM])
        detail_mock = _detail_resp(_DETAIL, _RECS_RAW)

        with patch(_LIST_PATCH) as MockClient:
            instance = AsyncMock()
            instance.__aenter__ = AsyncMock(return_value=instance)
            instance.__aexit__ = AsyncMock(return_value=False)
            instance.post = AsyncMock(side_effect=[list_mock, detail_mock])
            MockClient.return_value = instance

            parser = LgCnsCareersParser()
            result = await parser.fetch(
                source, profile, SeedKeywords(), _make_options()
            )

        assert len(result.postings) == 1
        p = result.postings[0]
        assert p.source_external_id == "1001310"
        assert p.company_name == "LG CNS"
        assert result.source_stats.discovered == 1
        assert result.source_stats.parsed == 1

    @pytest.mark.asyncio
    async def test_list_failure_returns_empty(self) -> None:
        source = _make_source()
        profile = _make_profile()

        with patch(_LIST_PATCH) as MockClient:
            instance = AsyncMock()
            instance.__aenter__ = AsyncMock(return_value=instance)
            instance.__aexit__ = AsyncMock(return_value=False)
            err_mock = MagicMock()
            err_mock.raise_for_status.side_effect = Exception("Connection error")
            instance.post = AsyncMock(return_value=err_mock)
            MockClient.return_value = instance

            parser = LgCnsCareersParser()
            result = await parser.fetch(
                source, profile, SeedKeywords(), _make_options()
            )

        assert result.postings == []
        assert any("list failed" in w for w in result.warnings)

    @pytest.mark.asyncio
    async def test_non_cns_company_code_skipped(self) -> None:
        bad_item = {**_JOB_ITEM, "companyCode": "LGE"}
        source = _make_source()
        profile = _make_profile()

        with patch(_LIST_PATCH) as MockClient:
            instance = AsyncMock()
            instance.__aenter__ = AsyncMock(return_value=instance)
            instance.__aexit__ = AsyncMock(return_value=False)
            instance.post = AsyncMock(return_value=_list_resp([bad_item]))
            MockClient.return_value = instance

            parser = LgCnsCareersParser()
            result = await parser.fetch(
                source, profile, SeedKeywords(), _make_options()
            )

        assert result.postings == []
        assert any("companyCode" in w for w in result.warnings)

    @pytest.mark.asyncio
    async def test_non_lg_cns_company_name_in_detail_skipped(self) -> None:
        bad_detail = {**_DETAIL, "companyName": "LG전자"}
        source = _make_source()
        profile = _make_profile()

        with patch(_LIST_PATCH) as MockClient:
            instance = AsyncMock()
            instance.__aenter__ = AsyncMock(return_value=instance)
            instance.__aexit__ = AsyncMock(return_value=False)
            instance.post = AsyncMock(
                side_effect=[_list_resp([_JOB_ITEM]), _detail_resp(bad_detail, [])]
            )
            MockClient.return_value = instance

            parser = LgCnsCareersParser()
            result = await parser.fetch(
                source, profile, SeedKeywords(), _make_options()
            )

        assert result.postings == []
        assert any("companyName" in w for w in result.warnings)

    @pytest.mark.asyncio
    async def test_max_fetch_limit_respected(self) -> None:
        jobs = [
            {**_JOB_ITEM, "jobNoticeId": 1000000 + i} for i in range(5)
        ]
        source = _make_source(max_fetch=2)
        profile = _make_profile()

        def make_detail(call_count: list[int]) -> Any:
            jid = 1000000 + call_count[0]
            call_count[0] += 1
            d = {**_DETAIL, "jobNoticeId": jid}
            return _detail_resp(d, _RECS_RAW)

        call_count = [0]
        side_effects = [_list_resp(jobs)] + [make_detail(call_count) for _ in range(2)]

        with patch(_LIST_PATCH) as MockClient:
            instance = AsyncMock()
            instance.__aenter__ = AsyncMock(return_value=instance)
            instance.__aexit__ = AsyncMock(return_value=False)
            instance.post = AsyncMock(side_effect=side_effects)
            MockClient.return_value = instance

            parser = LgCnsCareersParser()
            result = await parser.fetch(
                source, profile, SeedKeywords(), _make_options()
            )

        assert result.source_stats.discovered == 5
        assert len(result.postings) == 2


# ---------------------------------------------------------------------------
# TestSelfRegistration
# ---------------------------------------------------------------------------


class TestSelfRegistration:
    def test_registered_under_key(self) -> None:
        assert "LG_CNS_CAREERS" in _CUSTOM_REGISTRY_BY_KEY

    def test_registered_instance_is_parser(self) -> None:
        parser = _CUSTOM_REGISTRY_BY_KEY["LG_CNS_CAREERS"]
        assert isinstance(parser, LgCnsCareersParser)


# ---------------------------------------------------------------------------
# Live integration test (skipped in CI — requires external network)
# ---------------------------------------------------------------------------


class TestLgCnsCareersLive:
    @pytest.mark.asyncio
    @pytest.mark.skipif(
        True,
        reason=(
            "live test — remove skipif to run manually: "
            "poetry run pytest tests/adapters/test_lg_cns_careers.py "
            "-k test_list_api_reachable_and_parseable -v"
        ),
    )
    async def test_list_api_reachable_and_parseable(self) -> None:
        import httpx as _httpx

        from app.adapters.lg_cns_careers import _HEADERS, _LIST_BODY, _LIST_EP

        async with _httpx.AsyncClient(headers=_HEADERS, timeout=20) as client:
            r = await client.post(_LIST_EP, json=_LIST_BODY)

        assert r.status_code == 200
        data = r.json().get("data", {})
        jobs = data.get("jobNoticeList", [])
        assert len(jobs) >= 0
        company_codes = {j.get("companyCode") for j in jobs}
        assert company_codes <= {"CNS"} or len(jobs) == 0
        print(f"\nLive: {len(jobs)} LG CNS jobs discovered")


