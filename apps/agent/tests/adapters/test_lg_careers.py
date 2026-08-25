"""Tests for LG_CAREERS adapter (lg_careers.py).

Coverage targets:
  - _parse_config: company_code, expected_company_name, limits, defaults
  - _build_posting: all fields, edge cases
  - LgCareersParser.fetch: happy path, companyCode filter, companyName filter,
    dev/non-dev roles, experience levels (신입/경력/인턴/신입·경력/경력 무관/연수),
    employment type, locations, deadline, duplicate job IDs, empty list,
    malformed JSON, partial detail failure, max_fetch, source_record_key stability
  - Self-registration under "LG_CAREERS" key
  - LG_CNS_CAREERS regression: existing key still registered and functional
  - Live smoke test (skipped in CI; runs per company with max_fetch=2)
"""

from __future__ import annotations

import json
from datetime import date, datetime
from typing import Any
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from app.adapters.official.lg_careers import (
    LgCareersParser,
    _build_posting,
    _parse_config,
)
from app.adapters.official.lg_cns_careers import (
    LgCnsCareersParser,
    _parse_rec_list,
    _RecInfo,
)
from app.adapters.official_company import _CUSTOM_REGISTRY_BY_KEY
from app.schemas.collection import (
    CollectionOptions,
    CompanyProfile,
    OfficialCompanySource,
)

# ---------------------------------------------------------------------------
# Fixtures helpers
# ---------------------------------------------------------------------------

_COLLECT_DATE = date(2026, 8, 25)


def _make_source(
    company_code: str,
    expected_name: str | None = None,
    **extra: Any,
) -> OfficialCompanySource:
    cfg: dict[str, Any] = {
        "parser_key": "LG_CAREERS",
        "company_code": company_code,
        **extra,
    }
    if expected_name is not None:
        cfg["expected_company_name"] = expected_name
    return OfficialCompanySource(
        company_id=100,
        source_type="OFFICIAL_CAREER",
        source_url="https://careers.lg.com/apply",
        adapter_type="CUSTOM",
        config_json=json.dumps(cfg),
    )


def _make_profile(
    canonical: str,
    normalized: str | None = None,
    company_id: int = 100,
) -> CompanyProfile:
    return CompanyProfile(
        id=company_id,
        canonical_name=canonical,
        normalized_name=normalized or canonical.lower(),
    )


def _make_options() -> CollectionOptions:
    return CollectionOptions()


# ---------------------------------------------------------------------------
# LG API response builders
# ---------------------------------------------------------------------------

def _list_item(
    job_id: int,
    company_code: str,
    company_name: str,
    career_code: str = "B",
    career_name: str = "경력",
    job_name: str = "백엔드 개발자 채용",
) -> dict[str, Any]:
    return {
        "jobNoticeId": job_id,
        "companyCode": company_code,
        "companyName": company_name,
        "careerTypeCode": career_code,
        "careerTypeName": career_name,
        "jobNoticeName": job_name,
        "noticeStatus": "POSTING",
    }


def _detail_data(
    job_id: int,
    company_code: str,
    company_name: str,
    career_code: str = "B",
    career_name: str = "경력",
    job_name: str = "백엔드 개발자 채용",
    start: str = "2026.08.01 09:00",
    end: str = "2026.08.31 23:00",
    qual: str = "관련 분야 경력 3년 이상",
) -> dict[str, Any]:
    return {
        "jobNoticeId": job_id,
        "companyCode": company_code,
        "companyName": company_name,
        "careerTypeCode": career_code,
        "careerTypeName": career_name,
        "jobNoticeName": job_name,
        "recStartDate": start,
        "recEndDate": end,
        "qualForAppInfo": qual,
    }


def _recs(
    job_id: int, group_name: str, location: str | None = "서울"
) -> list[dict[str, Any]]:
    return [{
        "jobNoticeId": job_id,
        "jobGroupName": group_name,
        "locationName": location or "",
    }]


def _list_resp(jobs: list[dict[str, Any]]) -> MagicMock:
    m = MagicMock()
    m.raise_for_status = MagicMock()
    m.status_code = 200
    m.json.return_value = {
        "status": "S",
        "data": {"jobNoticeList": jobs, "listCount": len(jobs)},
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


def _error_resp(status: int = 500) -> MagicMock:
    m = MagicMock()
    m.raise_for_status.side_effect = Exception(f"HTTP {status}")
    m.status_code = status
    return m


_PATCH = "app.adapters.official.lg_careers.httpx.AsyncClient"


def _mock_client(*responses: MagicMock) -> tuple[Any, AsyncMock]:
    """Return (MockClient, instance) where instance.post cycles through responses."""
    instance = AsyncMock()
    instance.__aenter__ = AsyncMock(return_value=instance)
    instance.__aexit__ = AsyncMock(return_value=False)
    instance.post = AsyncMock(side_effect=list(responses))
    return instance


# ---------------------------------------------------------------------------
# TestParseConfig
# ---------------------------------------------------------------------------


class TestParseConfig:
    def test_defaults_when_null(self) -> None:
        cfg = _parse_config(None)
        assert cfg.company_code == ""
        assert cfg.expected_company_name is None
        assert cfg.max_discover == 50
        assert cfg.max_fetch == 30

    def test_company_code_upcased(self) -> None:
        cfg = _parse_config(json.dumps({"company_code": " lge "}))
        assert cfg.company_code == "LGE"

    def test_expected_company_name_parsed(self) -> None:
        cfg = _parse_config(
            json.dumps({"company_code": "LGE", "expected_company_name": "LG전자"})
        )
        assert cfg.expected_company_name == "LG전자"

    def test_empty_expected_name_becomes_none(self) -> None:
        cfg = _parse_config(
            json.dumps({"company_code": "LGE", "expected_company_name": "  "})
        )
        assert cfg.expected_company_name is None

    def test_custom_limits(self) -> None:
        cfg = _parse_config(
            json.dumps({"company_code": "LGU", "max_discover": 10, "max_fetch": 5})
        )
        assert cfg.max_discover == 10
        assert cfg.max_fetch == 5

    def test_invalid_json_defaults(self) -> None:
        cfg = _parse_config("{not valid")
        assert cfg.company_code == ""
        assert cfg.max_discover == 50

    def test_all_four_company_codes(self) -> None:
        for code in ("LGE", "LGU", "LGES", "LGIT"):
            cfg = _parse_config(json.dumps({"company_code": code}))
            assert cfg.company_code == code


# ---------------------------------------------------------------------------
# TestBuildPosting
# ---------------------------------------------------------------------------


class TestBuildPosting:
    def _recs(self) -> list[_RecInfo]:
        return _parse_rec_list(_recs(9001, "백엔드개발", "마곡"))

    def test_source_is_lg_careers(self) -> None:
        p = _build_posting(
            9001, {}, _detail_data(9001, "LGE", "LG전자"), self._recs(), "LG전자"
        )
        assert p.source == "lg_careers"

    def test_source_external_id(self) -> None:
        p = _build_posting(9001, {}, _detail_data(9001, "LGE", "LG전자"), [], "LG전자")
        assert p.source_external_id == "9001"

    def test_source_url(self) -> None:
        p = _build_posting(9001, {}, _detail_data(9001, "LGE", "LG전자"), [], "LG전자")
        assert p.source_url == "https://careers.lg.com/apply/9001"

    def test_company_name_from_arg(self) -> None:
        p = _build_posting(9001, {}, _detail_data(9001, "LGE", "LG전자"), [], "LG전자")
        assert p.company_name == "LG전자"

    def test_title_from_detail(self) -> None:
        detail = _detail_data(9001, "LGE", "LG전자", job_name="AI 백엔드 개발자 채용")
        p = _build_posting(9001, {}, detail, [], "LG전자")
        assert p.title == "AI 백엔드 개발자 채용"

    def test_title_fallback_to_list_item(self) -> None:
        detail = {
            k: v for k, v in _detail_data(9001, "LGE", "LG전자").items()
            if k != "jobNoticeName"
        }
        p = _build_posting(9001, {"jobNoticeName": "목록 직함"}, detail, [], "LG전자")
        assert p.title == "목록 직함"

    def test_roles_from_recs(self) -> None:
        recs = _parse_rec_list(_recs(9001, "클라우드 엔지니어"))
        p = _build_posting(
            9001, {}, _detail_data(9001, "LGE", "LG전자"), recs, "LG전자"
        )
        assert "클라우드 엔지니어" in p.roles

    def test_broad_categories_excluded_from_roles(self) -> None:
        recs = _parse_rec_list([
            {"jobNoticeId": 9001, "jobGroupName": "IT서비스", "locationName": "서울"},
            {
                "jobNoticeId": 9001,
                "jobGroupName": "데이터 엔지니어",
                "locationName": "서울",
            },
        ])
        p = _build_posting(
            9001, {}, _detail_data(9001, "LGE", "LG전자"), recs, "LG전자"
        )
        assert "IT서비스" not in p.roles
        assert "데이터 엔지니어" in p.roles

    def test_location_from_recs(self) -> None:
        recs = _parse_rec_list(_recs(9001, "백엔드개발", "마곡 및 기타"))
        p = _build_posting(
            9001, {}, _detail_data(9001, "LGE", "LG전자"), recs, "LG전자"
        )
        assert p.location == "마곡 및 기타"

    def test_multiple_locations_first_wins(self) -> None:
        recs = _parse_rec_list([
            {"jobNoticeId": 9001, "jobGroupName": "역할A", "locationName": "서울"},
            {"jobNoticeId": 9001, "jobGroupName": "역할B", "locationName": "부산"},
        ])
        p = _build_posting(
            9001, {}, _detail_data(9001, "LGE", "LG전자"), recs, "LG전자"
        )
        assert p.location == "서울"

    def test_experience_newcomer(self) -> None:
        detail = _detail_data(
            9001, "LGE", "LG전자", career_code="A", career_name="신입"
        )
        p = _build_posting(9001, {}, detail, [], "LG전자")
        assert p.experience_level == "신입"

    def test_experience_career(self) -> None:
        detail = _detail_data(
            9001, "LGE", "LG전자", career_code="B", career_name="경력"
        )
        p = _build_posting(9001, {}, detail, [], "LG전자")
        assert p.experience_level == "경력"

    def test_experience_intern(self) -> None:
        detail = _detail_data(
            9001, "LGE", "LG전자", career_code="C", career_name="인턴"
        )
        p = _build_posting(9001, {}, detail, [], "LG전자")
        assert p.experience_level == "인턴"

    def test_experience_mixed(self) -> None:
        detail = _detail_data(
            9001, "LGE", "LG전자", career_code="D", career_name="신입/경력"
        )
        p = _build_posting(9001, {}, detail, [], "LG전자")
        assert p.experience_level == "신입·경력"

    def test_experience_irrelevant_unknown_code_fallback(self) -> None:
        # 경력 무관 같은 미지정 코드는 careerTypeName 그대로 반환
        detail = _detail_data(
            9001, "LGE", "LG전자", career_code="F", career_name="경력 무관"
        )
        p = _build_posting(9001, {}, detail, [], "LG전자")
        assert p.experience_level == "경력 무관"

    def test_description_from_qual(self) -> None:
        detail = _detail_data(9001, "LGE", "LG전자", qual="관련 경력 3년 이상 필수")
        p = _build_posting(9001, {}, detail, [], "LG전자")
        assert p.description == "관련 경력 3년 이상 필수"

    def test_experience_years_in_description_not_experience_level(self) -> None:
        # qualForAppInfo에 경력 연수가 언급되어도 experience_level에는 반영 금지
        detail = _detail_data(
            9001, "LGE", "LG전자",
            career_code="B", career_name="경력",
            qual="클라우드 개발 경력 5년 이상 필수\n우대: 7년 이상",
        )
        p = _build_posting(9001, {}, detail, [], "LG전자")
        assert p.experience_level == "경력"         # careerTypeCode 기준
        assert "5년 이상" in (p.description or "")   # 본문에는 포함

    def test_no_description_when_qual_empty(self) -> None:
        detail = _detail_data(9001, "LGE", "LG전자", qual="")
        p = _build_posting(9001, {}, detail, [], "LG전자")
        assert p.description is None

    def test_employment_type_intern(self) -> None:
        detail = _detail_data(
            9001, "LGE", "LG전자", career_code="C", career_name="인턴"
        )
        p = _build_posting(9001, {}, detail, [], "LG전자")
        assert p.employment_type == "인턴"

    def test_employment_type_regular(self) -> None:
        detail = _detail_data(
            9001, "LGE", "LG전자", career_code="B", career_name="경력"
        )
        p = _build_posting(9001, {}, detail, [], "LG전자")
        assert p.employment_type == "정규직"

    def test_deadline_parsed(self) -> None:
        detail = _detail_data(9001, "LGE", "LG전자", end="2026.09.30 23:59")
        p = _build_posting(9001, {}, detail, [], "LG전자")
        assert p.deadline == date(2026, 9, 30)

    def test_posted_at_from_start_date(self) -> None:
        detail = _detail_data(9001, "LGE", "LG전자", start="2026.08.01 09:00")
        p = _build_posting(9001, {}, detail, [], "LG전자")
        assert p.posted_at == datetime(2026, 8, 1)

    def test_source_record_key_stability(self) -> None:
        """같은 job_id로 두 번 빌드하면 source와 source_external_id가 동일해야 한다."""
        detail = _detail_data(9001, "LGE", "LG전자")
        p1 = _build_posting(9001, {}, detail, [], "LG전자")
        p2 = _build_posting(9001, {}, detail, [], "LG전자")
        assert p1.source == p2.source
        assert p1.source_external_id == p2.source_external_id


# ---------------------------------------------------------------------------
# TestLgCareersParserFetch
# ---------------------------------------------------------------------------


class TestLgCareersParserFetch:

    # ── happy path: 4개 company_code ─────────────────────────────────────────

    @pytest.mark.asyncio
    @pytest.mark.parametrize("code,company", [
        ("LGE", "LG전자"),
        ("LGU", "LG유플러스"),
        ("LGES", "LG에너지솔루션"),
        ("LGIT", "LG이노텍"),
    ])
    async def test_happy_path_per_company_code(self, code: str, company: str) -> None:
        item = _list_item(9001, code, company)
        detail = _detail_data(9001, code, company, qual="관련 업무 경력자")
        source = _make_source(code, company)
        profile = _make_profile(company)

        with patch(_PATCH) as MockClient:
            inst = _mock_client(
                _list_resp([item]),
                _detail_resp(detail, _recs(9001, "백엔드개발", "서울")),
            )
            MockClient.return_value = inst

            result = await LgCareersParser().fetch(
                source, profile, _make_options(), _COLLECT_DATE
            )

        assert len(result.postings) == 1
        p = result.postings[0]
        assert p.company_name == company
        assert p.source == "lg_careers"
        assert p.source_external_id == "9001"
        assert result.source_stats is not None
        assert result.source_stats.discovered == 1
        assert result.source_stats.parsed == 1

    # ── 개발 직무 vs 비개발 직무 ──────────────────────────────────────────────

    @pytest.mark.asyncio
    async def test_dev_role_in_roles(self) -> None:
        item = _list_item(9001, "LGE", "LG전자")
        detail = _detail_data(9001, "LGE", "LG전자")
        source = _make_source("LGE", "LG전자")
        profile = _make_profile("LG전자")
        rec = _recs(9001, "클라우드/DevOps 엔지니어")

        with patch(_PATCH) as MockClient:
            inst = _mock_client(_list_resp([item]), _detail_resp(detail, rec))
            MockClient.return_value = inst
            result = await LgCareersParser().fetch(
                source, profile, _make_options(), _COLLECT_DATE
            )

        assert "클라우드/DevOps 엔지니어" in result.postings[0].roles

    @pytest.mark.asyncio
    async def test_non_dev_role_broad_category_excluded(self) -> None:
        item = _list_item(9001, "LGE", "LG전자")
        detail = _detail_data(9001, "LGE", "LG전자")
        source = _make_source("LGE", "LG전자")
        profile = _make_profile("LG전자")
        # 경영지원 = _BROAD_CATEGORIES
        rec = [
            {"jobNoticeId": 9001, "jobGroupName": "경영지원", "locationName": "서울"}
        ]

        with patch(_PATCH) as MockClient:
            inst = _mock_client(_list_resp([item]), _detail_resp(detail, rec))
            MockClient.return_value = inst
            result = await LgCareersParser().fetch(
                source, profile, _make_options(), _COLLECT_DATE
            )

        assert result.postings[0].roles == []

    # ── 경험 레벨 ────────────────────────────────────────────────────────────

    @pytest.mark.asyncio
    @pytest.mark.parametrize("code,expected", [
        ("A", "신입"),
        ("B", "경력"),
        ("C", "인턴"),
        ("D", "신입·경력"),
        ("E", "산학장학생"),
        ("F", "경력 무관"),  # 미지정 코드 → careerTypeName fallback
    ])
    async def test_experience_levels(self, code: str, expected: str) -> None:
        career_name = {"A": "신입", "B": "경력", "C": "인턴", "D": "신입/경력",
                       "E": "산학장학생", "F": "경력 무관"}[code]
        item = _list_item(
            9001, "LGE", "LG전자", career_code=code, career_name=career_name
        )
        detail = _detail_data(
            9001, "LGE", "LG전자", career_code=code, career_name=career_name
        )
        source = _make_source("LGE", "LG전자")
        profile = _make_profile("LG전자")

        with patch(_PATCH) as MockClient:
            inst = _mock_client(_list_resp([item]), _detail_resp(detail, []))
            MockClient.return_value = inst
            result = await LgCareersParser().fetch(
                source, profile, _make_options(), _COLLECT_DATE
            )

        assert result.postings[0].experience_level == expected

    @pytest.mark.asyncio
    async def test_concrete_years_in_description_not_in_experience_level(self) -> None:
        """본문에 경력 연수가 있어도 experience_level은 careerTypeCode 기준 유지."""
        item = _list_item(9001, "LGE", "LG전자", career_code="B", career_name="경력")
        detail = _detail_data(
            9001, "LGE", "LG전자",
            career_code="B", career_name="경력",
            qual="관련 개발 경력 5년 이상 필수\n우대: MSA 아키텍처 경험 3년 이상",
        )
        source = _make_source("LGE", "LG전자")
        profile = _make_profile("LG전자")

        with patch(_PATCH) as MockClient:
            inst = _mock_client(_list_resp([item]), _detail_resp(detail, []))
            MockClient.return_value = inst
            result = await LgCareersParser().fetch(
                source, profile, _make_options(), _COLLECT_DATE
            )

        p = result.postings[0]
        assert p.experience_level == "경력"
        assert "5년 이상" in (p.description or "")

    # ── 고용 형태 ─────────────────────────────────────────────────────────────

    @pytest.mark.asyncio
    @pytest.mark.parametrize("career_code,expected_type", [
        ("C", "인턴"),
        ("B", "정규직"),
        ("A", "정규직"),
        ("D", "정규직"),
    ])
    async def test_employment_types(self, career_code: str, expected_type: str) -> None:
        career_name = {
            "C": "인턴", "B": "경력", "A": "신입", "D": "신입/경력"
        }[career_code]
        item = _list_item(
            9001, "LGE", "LG전자", career_code=career_code, career_name=career_name
        )
        detail = _detail_data(
            9001, "LGE", "LG전자", career_code=career_code, career_name=career_name
        )
        source = _make_source("LGE", "LG전자")
        profile = _make_profile("LG전자")

        with patch(_PATCH) as MockClient:
            inst = _mock_client(_list_resp([item]), _detail_resp(detail, []))
            MockClient.return_value = inst
            result = await LgCareersParser().fetch(
                source, profile, _make_options(), _COLLECT_DATE
            )

        assert result.postings[0].employment_type == expected_type

    # ── 복수 근무지 ───────────────────────────────────────────────────────────

    @pytest.mark.asyncio
    async def test_multiple_locations_first_non_null_returned(self) -> None:
        item = _list_item(9001, "LGU", "LG유플러스")
        detail = _detail_data(9001, "LGU", "LG유플러스")
        source = _make_source("LGU", "LG유플러스")
        profile = _make_profile("LG유플러스")
        multi_recs = [
            {
                "jobNoticeId": 9001,
                "jobGroupName": "네트워크 엔지니어",
                "locationName": "서울",
            },
            {
                "jobNoticeId": 9001,
                "jobGroupName": "클라우드 개발",
                "locationName": "부산",
            },
        ]

        with patch(_PATCH) as MockClient:
            inst = _mock_client(_list_resp([item]), _detail_resp(detail, multi_recs))
            MockClient.return_value = inst
            result = await LgCareersParser().fetch(
                source, profile, _make_options(), _COLLECT_DATE
            )

        assert result.postings[0].location == "서울"
        assert len(result.postings[0].roles) == 2

    # ── 마감일 ────────────────────────────────────────────────────────────────

    @pytest.mark.asyncio
    async def test_deadline_with_time(self) -> None:
        item = _list_item(9001, "LGE", "LG전자")
        detail = _detail_data(9001, "LGE", "LG전자", end="2026.09.30 23:59")
        source = _make_source("LGE", "LG전자")
        profile = _make_profile("LG전자")

        with patch(_PATCH) as MockClient:
            inst = _mock_client(_list_resp([item]), _detail_resp(detail, []))
            MockClient.return_value = inst
            result = await LgCareersParser().fetch(
                source, profile, _make_options(), _COLLECT_DATE
            )

        assert result.postings[0].deadline == date(2026, 9, 30)

    @pytest.mark.asyncio
    async def test_deadline_none_on_rolling(self) -> None:
        """'채용시 마감' 같은 비날짜 문자열 → deadline=None."""
        item = _list_item(9001, "LGE", "LG전자")
        detail = {
            **_detail_data(9001, "LGE", "LG전자"),
            "recEndDate": "채용시 마감",
        }
        source = _make_source("LGE", "LG전자")
        profile = _make_profile("LG전자")

        with patch(_PATCH) as MockClient:
            inst = _mock_client(_list_resp([item]), _detail_resp(detail, []))
            MockClient.return_value = inst
            result = await LgCareersParser().fetch(
                source, profile, _make_options(), _COLLECT_DATE
            )

        assert result.postings[0].deadline is None

    # ── companyCode 필터 ──────────────────────────────────────────────────────

    @pytest.mark.asyncio
    async def test_wrong_company_code_in_list_skipped(self) -> None:
        wrong_item = _list_item(9001, "CNS", "LG CNS")
        source = _make_source("LGE", "LG전자")
        profile = _make_profile("LG전자")

        with patch(_PATCH) as MockClient:
            inst = _mock_client(_list_resp([wrong_item]))
            MockClient.return_value = inst
            result = await LgCareersParser().fetch(
                source, profile, _make_options(), _COLLECT_DATE
            )

        assert result.postings == []
        assert any("companyCode" in w for w in result.warnings)

    # ── expected_company_name 필터 ────────────────────────────────────────────

    @pytest.mark.asyncio
    async def test_wrong_company_name_in_detail_skipped(self) -> None:
        item = _list_item(9001, "LGE", "LG전자")
        detail = {**_detail_data(9001, "LGE", "LG전자"), "companyName": "LG CNS"}
        source = _make_source("LGE", "LG전자")
        profile = _make_profile("LG전자")

        with patch(_PATCH) as MockClient:
            inst = _mock_client(_list_resp([item]), _detail_resp(detail, []))
            MockClient.return_value = inst
            result = await LgCareersParser().fetch(
                source, profile, _make_options(), _COLLECT_DATE
            )

        assert result.postings == []
        assert any("companyName" in w for w in result.warnings)

    @pytest.mark.asyncio
    async def test_no_expected_name_skips_company_name_check(self) -> None:
        """expected_company_name 미설정 시 companyName 검증 건너뜀."""
        item = _list_item(9001, "LGE", "LG전자")
        detail = {**_detail_data(9001, "LGE", "LG전자"), "companyName": "임의이름"}
        source = _make_source("LGE")  # expected_company_name 없음
        profile = _make_profile("LG전자")

        with patch(_PATCH) as MockClient:
            inst = _mock_client(_list_resp([item]), _detail_resp(detail, []))
            MockClient.return_value = inst
            result = await LgCareersParser().fetch(
                source, profile, _make_options(), _COLLECT_DATE
            )

        assert len(result.postings) == 1

    # ── 중복 jobNoticeId ──────────────────────────────────────────────────────

    @pytest.mark.asyncio
    async def test_duplicate_job_id_deduplicated(self) -> None:
        item1 = _list_item(9001, "LGE", "LG전자")
        item2 = _list_item(9001, "LGE", "LG전자")  # same ID
        detail = _detail_data(9001, "LGE", "LG전자")
        source = _make_source("LGE", "LG전자")
        profile = _make_profile("LG전자")

        with patch(_PATCH) as MockClient:
            inst = _mock_client(_list_resp([item1, item2]), _detail_resp(detail, []))
            MockClient.return_value = inst
            result = await LgCareersParser().fetch(
                source, profile, _make_options(), _COLLECT_DATE
            )

        assert len(result.postings) == 1
        assert any("duplicate" in w for w in result.warnings)

    # ── 정상 0건 ─────────────────────────────────────────────────────────────

    @pytest.mark.asyncio
    async def test_empty_list_returns_zero_postings(self) -> None:
        source = _make_source("LGES", "LG에너지솔루션")
        profile = _make_profile("LG에너지솔루션")

        with patch(_PATCH) as MockClient:
            inst = _mock_client(_list_resp([]))
            MockClient.return_value = inst
            result = await LgCareersParser().fetch(
                source, profile, _make_options(), _COLLECT_DATE
            )

        assert result.postings == []
        assert result.source_stats is not None
        assert result.source_stats.discovered == 0
        assert result.warnings == []

    # ── 목록 API 실패 ────────────────────────────────────────────────────────

    @pytest.mark.asyncio
    async def test_list_api_failure_returns_empty_with_warning(self) -> None:
        source = _make_source("LGE", "LG전자")
        profile = _make_profile("LG전자")

        with patch(_PATCH) as MockClient:
            inst = _mock_client(_error_resp(503))
            MockClient.return_value = inst
            result = await LgCareersParser().fetch(
                source, profile, _make_options(), _COLLECT_DATE
            )

        assert result.postings == []
        assert any("list failed" in w for w in result.warnings)

    # ── malformed JSON ────────────────────────────────────────────────────────

    @pytest.mark.asyncio
    async def test_malformed_list_json_returns_empty_with_warning(self) -> None:
        bad_resp = MagicMock()
        bad_resp.raise_for_status = MagicMock()
        bad_resp.status_code = 200
        bad_resp.json.side_effect = ValueError("invalid json")
        source = _make_source("LGE", "LG전자")
        profile = _make_profile("LG전자")

        with patch(_PATCH) as MockClient:
            inst = _mock_client(bad_resp)
            MockClient.return_value = inst
            result = await LgCareersParser().fetch(
                source, profile, _make_options(), _COLLECT_DATE
            )

        assert result.postings == []
        assert any("JSON parse failed" in w for w in result.warnings)

    @pytest.mark.asyncio
    async def test_malformed_detail_json_skipped_with_warning(self) -> None:
        item = _list_item(9001, "LGE", "LG전자")
        bad_detail = MagicMock()
        bad_detail.raise_for_status = MagicMock()
        bad_detail.status_code = 200
        bad_detail.json.side_effect = ValueError("bad json in detail")
        source = _make_source("LGE", "LG전자")
        profile = _make_profile("LG전자")

        with patch(_PATCH) as MockClient:
            inst = _mock_client(_list_resp([item]), bad_detail)
            MockClient.return_value = inst
            result = await LgCareersParser().fetch(
                source, profile, _make_options(), _COLLECT_DATE
            )

        assert result.postings == []
        assert any("JSON parse failed" in w for w in result.warnings)

    # ── 상세 일부 실패 (partial failure) ─────────────────────────────────────

    @pytest.mark.asyncio
    async def test_partial_detail_failure_does_not_stop_collection(self) -> None:
        """첫 번째 상세 실패 → 건너뛰고 두 번째는 정상 파싱."""
        item1 = _list_item(9001, "LGE", "LG전자")
        item2 = _list_item(9002, "LGE", "LG전자")
        detail2 = _detail_data(9002, "LGE", "LG전자", job_name="두 번째 공고")
        source = _make_source("LGE", "LG전자")
        profile = _make_profile("LG전자")

        with patch(_PATCH) as MockClient:
            inst = _mock_client(
                _list_resp([item1, item2]),
                _error_resp(500),        # detail for 9001 fails
                _detail_resp(detail2, []),  # detail for 9002 succeeds
            )
            MockClient.return_value = inst
            result = await LgCareersParser().fetch(
                source, profile, _make_options(), _COLLECT_DATE
            )

        assert len(result.postings) == 1
        assert result.postings[0].source_external_id == "9002"
        assert any("detail failed" in w for w in result.warnings)

    # ── max_fetch 제한 ────────────────────────────────────────────────────────

    @pytest.mark.asyncio
    async def test_max_fetch_respected(self) -> None:
        items = [_list_item(9000 + i, "LGIT", "LG이노텍") for i in range(5)]
        details = [_detail_data(9000 + i, "LGIT", "LG이노텍") for i in range(2)]
        source = _make_source("LGIT", "LG이노텍", max_fetch=2)
        profile = _make_profile("LG이노텍")

        with patch(_PATCH) as MockClient:
            inst = _mock_client(
                _list_resp(items),
                _detail_resp(details[0], []),
                _detail_resp(details[1], []),
            )
            MockClient.return_value = inst
            result = await LgCareersParser().fetch(
                source, profile, _make_options(), _COLLECT_DATE
            )

        assert result.source_stats is not None
        assert result.source_stats.discovered == 5
        assert len(result.postings) == 2

    # ── max_discover 제한 ────────────────────────────────────────────────────

    @pytest.mark.asyncio
    async def test_max_discover_caps_list(self) -> None:
        items = [_list_item(9000 + i, "LGES", "LG에너지솔루션") for i in range(10)]
        source = _make_source("LGES", "LG에너지솔루션", max_discover=3, max_fetch=10)
        profile = _make_profile("LG에너지솔루션")
        details = [_detail_data(9000 + i, "LGES", "LG에너지솔루션") for i in range(3)]

        with patch(_PATCH) as MockClient:
            inst = _mock_client(
                _list_resp(items),
                *[_detail_resp(d, []) for d in details],
            )
            MockClient.return_value = inst
            result = await LgCareersParser().fetch(
                source, profile, _make_options(), _COLLECT_DATE
            )

        assert result.source_stats is not None
        assert result.source_stats.discovered == 10
        assert len(result.postings) == 3

    # ── company_code 미설정 ───────────────────────────────────────────────────

    @pytest.mark.asyncio
    async def test_missing_company_code_returns_empty_with_warning(self) -> None:
        source = OfficialCompanySource(
            company_id=100,
            source_type="OFFICIAL_CAREER",
            source_url="https://careers.lg.com/apply",
            adapter_type="CUSTOM",
            config_json=json.dumps({"parser_key": "LG_CAREERS"}),  # no company_code
        )
        profile = _make_profile("LG전자")

        with patch(_PATCH) as MockClient:
            MockClient.return_value = _mock_client()
            result = await LgCareersParser().fetch(
                source, profile, _make_options(), _COLLECT_DATE
            )

        assert result.postings == []
        assert any("company_code not configured" in w for w in result.warnings)

    # ── None profile fallback ─────────────────────────────────────────────────

    @pytest.mark.asyncio
    async def test_none_profile_falls_back_to_expected_company_name(self) -> None:
        item = _list_item(9001, "LGE", "LG전자")
        detail = _detail_data(9001, "LGE", "LG전자")
        source = _make_source("LGE", "LG전자")  # expected_company_name in config

        with patch(_PATCH) as MockClient:
            inst = _mock_client(_list_resp([item]), _detail_resp(detail, []))
            MockClient.return_value = inst
            result = await LgCareersParser().fetch(
                source, None, _make_options(), _COLLECT_DATE  # None profile
            )

        assert len(result.postings) == 1
        assert result.postings[0].company_name == "LG전자"


# ---------------------------------------------------------------------------
# TestSelfRegistration
# ---------------------------------------------------------------------------


class TestSelfRegistration:
    def test_lg_careers_registered(self) -> None:
        assert "LG_CAREERS" in _CUSTOM_REGISTRY_BY_KEY

    def test_lg_careers_instance(self) -> None:
        assert isinstance(_CUSTOM_REGISTRY_BY_KEY["LG_CAREERS"], LgCareersParser)

    def test_lg_cns_careers_still_registered(self) -> None:
        """LG_CAREERS 추가 후 기존 LG_CNS_CAREERS 키 유지."""
        assert "LG_CNS_CAREERS" in _CUSTOM_REGISTRY_BY_KEY

    def test_lg_cns_careers_still_functional(self) -> None:
        """LG_CNS_CAREERS 인스턴스가 LgCnsCareersParser 타입인지 확인."""
        assert isinstance(_CUSTOM_REGISTRY_BY_KEY["LG_CNS_CAREERS"], LgCnsCareersParser)


# ---------------------------------------------------------------------------
# LG_CNS_CAREERS 회귀 테스트
# ---------------------------------------------------------------------------


class TestLgCnsCareersRegression:
    """LG_CNS_CAREERS 파서가 LG_CAREERS 추가 후에도 기존대로 동작하는지 확인."""

    @pytest.mark.asyncio
    async def test_lg_cns_careers_still_works(self) -> None:
        from app.adapters.official.lg_cns_careers import (
            LgCnsCareersParser,
        )
        from app.schemas.collection import OfficialCompanySource as OCS

        cns_source = OCS(
            company_id=27,
            source_type="OFFICIAL_CAREER",
            source_url="https://careers.lg.com/apply",
            adapter_type="CUSTOM",
            config_json=(
                '{"parser_key": "LG_CNS_CAREERS", "max_discover": 50, "max_fetch": 30}'
            ),
        )
        cns_profile = CompanyProfile(
            id=27, canonical_name="LG CNS", normalized_name="lg cns"
        )

        cns_item = {
            "jobNoticeId": 1001310,
            "careerTypeCode": "B",
            "careerTypeName": "경력",
            "companyCode": "CNS",
            "companyName": "LG CNS",
            "jobNoticeName": "[LG CNS] 보안 전문가 모집",
        }
        cns_detail = {
            "jobNoticeId": 1001310,
            "jobNoticeName": "[LG CNS] 보안 전문가 모집",
            "companyCode": "CNS",
            "companyName": "LG CNS",
            "recStartDate": "2026.08.12 09:00",
            "recEndDate": "2026.08.31 23:00",
            "careerTypeCode": "B",
            "careerTypeName": "경력",
            "qualForAppInfo": "보안 경력 3년 이상",
        }

        _cns_list_resp = MagicMock()
        _cns_list_resp.raise_for_status = MagicMock()
        _cns_list_resp.json.return_value = {
            "status": "S",
            "data": {"jobNoticeList": [cns_item], "listCount": 1},
        }
        _cns_detail_resp = MagicMock()
        _cns_detail_resp.raise_for_status = MagicMock()
        _cns_detail_resp.json.return_value = {
            "status": "S",
            "data": {
                "jobNoticesDetail": {
                    "jobNoticesDetail": cns_detail,
                    "recList": [],
                }
            },
        }

        _cns_patch = "app.adapters.official.lg_cns_careers.httpx.AsyncClient"
        with patch(_cns_patch) as MockClient:
            inst = AsyncMock()
            inst.__aenter__ = AsyncMock(return_value=inst)
            inst.__aexit__ = AsyncMock(return_value=False)
            inst.post = AsyncMock(side_effect=[_cns_list_resp, _cns_detail_resp])
            MockClient.return_value = inst

            parser = LgCnsCareersParser()
            from app.schemas.collection import SeedKeywords
            result = await parser.fetch(
                cns_source, cns_profile, SeedKeywords(), _make_options()
            )

        assert len(result.postings) == 1
        assert result.postings[0].source == "lg_cns_careers"
        assert result.postings[0].company_name == "LG CNS"
        assert result.postings[0].source_external_id == "1001310"


# ---------------------------------------------------------------------------
# Live smoke test (skipped in CI — requires external network)
# ---------------------------------------------------------------------------


class TestLgCareersLive:
    """LG Careers API 실제 통신 테스트.

    CI에서는 skipif=True 로 건너뜀.
    수동 실행:
      poetry run pytest tests/adapters/test_lg_careers.py -k "live" -v -s
    """

    @pytest.mark.asyncio
    @pytest.mark.skipif(
        True, reason="live — 수동 실행: poetry run pytest -k live -v -s"
    )
    @pytest.mark.parametrize("code,company", [
        ("LGE", "LG전자"),
        ("LGU", "LG유플러스"),
        ("LGES", "LG에너지솔루션"),
        ("LGIT", "LG이노텍"),
    ])
    async def test_live_per_company(self, code: str, company: str) -> None:
        source = _make_source(code, company, max_discover=50, max_fetch=2)
        profile = _make_profile(company)

        result = await LgCareersParser().fetch(
            source, profile, _make_options(), date.today()
        )

        # HTTP/JSON 오류 없는지 확인
        list_failures = [
            w for w in result.warnings
            if "list failed" in w or "JSON parse failed" in w
        ]
        assert not list_failures, f"[{code}] List API error: {list_failures}"

        count = result.source_stats.discovered if result.source_stats else 0
        print(f"\n[{code}] {company}: discovered={count}")
        print(f"  warnings: {result.warnings}")
        for p in result.postings:
            print(
                f"  → {p.title} | {p.experience_level}"
                f" | {p.location} | deadline={p.deadline}"
            )

        # 0건은 허용 (채용 시즌이 아닐 수 있음)
        if result.postings:
            p = result.postings[0]
            assert p.source == "lg_careers"
            assert p.company_name == company
            assert p.source_external_id is not None
            assert p.source_url.startswith("https://careers.lg.com/apply/")
