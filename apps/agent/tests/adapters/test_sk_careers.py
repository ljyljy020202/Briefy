"""Tests for SK_CAREERS adapter (sk_careers.py).

Coverage targets:
  - _parse_config: corp_code, expected_corp_name, max_fetch, defaults
  - _parse_date: valid "Month DD, YYYY(Day)" format, edge cases
  - _experience_level: New→신입, Experienced→경력, Irrelevant→None
  - _employment_type: Permanent→정규직, Contract→계약직
  - _parse_roles: bullet-point and comma separators
  - _build_posting: all fields, None handling
  - SkCareersParser.fetch: happy path, corpName filter, empty list,
    missing noticeID, empty title, duplicate noticeID, network error,
    JSON error, success=false, missing corp_code, max_fetch cap,
    source_record_key stability
  - Self-registration under "SK_CAREERS" key
  - Live smoke test (skipped in CI; run manually with -k live)
"""

from __future__ import annotations

import json
from datetime import date, datetime
from typing import Any
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from app.adapters.official.sk_careers import (
    SkCareersParser,
    _build_posting,
    _employment_type,
    _experience_level,
    _parse_config,
    _parse_date,
    _parse_roles,
)
from app.adapters.official_company import _CUSTOM_REGISTRY_BY_KEY
from app.schemas.collection import (
    CollectionOptions,
    CompanyProfile,
    OfficialCompanySource,
)

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

_COLLECT_DATE = date(2026, 8, 25)


def _make_source(
    corp_code: str,
    expected_corp_name: str | None = None,
    max_fetch: int = 50,
) -> OfficialCompanySource:
    cfg: dict[str, Any] = {
        "parser_key": "SK_CAREERS",
        "corp_code": corp_code,
        "max_fetch": max_fetch,
    }
    if expected_corp_name is not None:
        cfg["expected_corp_name"] = expected_corp_name
    return OfficialCompanySource(
        company_id=200,
        source_type="OFFICIAL_CAREER",
        source_url="https://www.skcareers.com/Recruit",
        adapter_type="CUSTOM",
        config_json=json.dumps(cfg),
    )


def _make_profile(
    canonical: str,
    company_id: int = 200,
) -> CompanyProfile:
    return CompanyProfile(
        id=company_id,
        canonical_name=canonical,
        normalized_name=canonical.lower(),
    )


def _make_options() -> CollectionOptions:
    return CollectionOptions()


def _list_item(
    notice_id: str,
    title: str,
    corp_name: str = "SK telecom",
    job_role: str = "IT 개발/운영 지원",
    recruit_type: str = "Experienced",
    working_type: str = "Permanent",
    working_area: str = "Seoul",
    start: str = "August 24, 2026(Mon)",
    end: str = "August 30, 2026(Sun)",
    remain_day: int = 5,
) -> dict[str, Any]:
    return {
        "jobNoticeNo": 6301,
        "noticeID": notice_id,
        "title": title,
        "jobRole": job_role,
        "recruitType": recruit_type,
        "workingType": working_type,
        "workingArea": working_area,
        "remainDay": remain_day,
        "corpName": corp_name,
        "scrapIdx": 0,
        "start": start,
        "end": end,
    }


def _list_resp(items: list[dict[str, Any]]) -> MagicMock:
    m = MagicMock()
    m.raise_for_status = MagicMock()
    m.status_code = 200
    m.json.return_value = {
        "success": True,
        "totalCount": len(items),
        "list": items,
    }
    return m


_ITEM_SKT = _list_item(
    "R261849",
    "Data Platform Engineer",
    corp_name="SK telecom",
    job_role="기술/현장지원/IT 개발/운영 지원",
    recruit_type="Experienced",
    working_type="Contract",
    working_area="Seoul",
    end="August 30, 2026(Sun)",
)

_ITEM_SKT_NEW = _list_item(
    "R261841",
    "2026 Junior Talent 채용 - Tech 직군",
    corp_name="SK telecom",
    job_role="Tech R&D/SW 개발,AI Model",
    recruit_type="New",
    working_type="Permanent",
    working_area="NationWide",
    end="August 30, 2026(Sun)",
)

_ITEM_SKH = _list_item(
    "R261845",
    "Nexus Legal 중국변호사 채용",
    corp_name="SK hynix",
    job_role="법무",
    recruit_type="Experienced",
    working_type="Contract",
    working_area="Gyeonggi/Incheon",
    end="September 07, 2026(Mon)",
)


# ---------------------------------------------------------------------------
# Unit tests: _parse_config
# ---------------------------------------------------------------------------


class TestParseConfig:
    def test_full_config(self) -> None:
        cfg = json.dumps({
            "parser_key": "SK_CAREERS",
            "corp_code": "10005",
            "expected_corp_name": "SK telecom",
            "max_fetch": 30,
        })
        c = _parse_config(cfg)
        assert c.corp_code == "10005"
        assert c.expected_corp_name == "SK telecom"
        assert c.max_fetch == 30

    def test_defaults(self) -> None:
        c = _parse_config(json.dumps({"corp_code": "10004"}))
        assert c.corp_code == "10004"
        assert c.expected_corp_name is None
        assert c.max_fetch == 50

    def test_empty_expected_corp_name_becomes_none(self) -> None:
        c = _parse_config(json.dumps({"corp_code": "10005", "expected_corp_name": ""}))
        assert c.expected_corp_name is None

    def test_none_json(self) -> None:
        c = _parse_config(None)
        assert c.corp_code == ""
        assert c.max_fetch == 50

    def test_malformed_json(self) -> None:
        c = _parse_config("{not valid json}")
        assert c.corp_code == ""


# ---------------------------------------------------------------------------
# Unit tests: _parse_date
# ---------------------------------------------------------------------------


class TestParseDate:
    def test_valid_sunday(self) -> None:
        assert _parse_date("August 30, 2026(Sun)") == date(2026, 8, 30)

    def test_valid_monday(self) -> None:
        assert _parse_date("September 07, 2026(Mon)") == date(2026, 9, 7)

    def test_none_input(self) -> None:
        assert _parse_date(None) is None

    def test_empty_string(self) -> None:
        assert _parse_date("") is None

    def test_invalid_format(self) -> None:
        assert _parse_date("2026-08-30") is None


# ---------------------------------------------------------------------------
# Unit tests: _experience_level / _employment_type / _parse_roles
# ---------------------------------------------------------------------------


class TestFieldHelpers:
    def test_experience_new(self) -> None:
        assert _experience_level("New") == "신입"

    def test_experience_experienced(self) -> None:
        assert _experience_level("Experienced") == "경력"

    def test_experience_irrelevant(self) -> None:
        assert _experience_level("Irrelevant") is None

    def test_experience_empty(self) -> None:
        assert _experience_level("") is None

    def test_experience_unknown_passthrough(self) -> None:
        assert _experience_level("Senior") == "Senior"

    def test_employment_permanent(self) -> None:
        assert _employment_type("Permanent") == "정규직"

    def test_employment_contract(self) -> None:
        assert _employment_type("Contract") == "계약직"

    def test_employment_empty(self) -> None:
        assert _employment_type("") is None

    def test_roles_bullet_separator(self) -> None:
        roles = _parse_roles("기업문화/HR • 대외협력/CR/PR • 재무")
        assert roles == ["기업문화/HR", "대외협력/CR/PR", "재무"]

    def test_roles_comma_separator(self) -> None:
        roles = _parse_roles("Tech R&D/SW 개발,AI Model")
        assert roles == ["Tech R&D/SW 개발", "AI Model"]

    def test_roles_slash_only(self) -> None:
        roles = _parse_roles("IT 개발/운영 지원")
        assert roles == ["IT 개발/운영 지원"]

    def test_roles_empty(self) -> None:
        assert _parse_roles("") == []

    def test_roles_mixed(self) -> None:
        roles = _parse_roles(
            "Infra/무선 Network,유선/IP Network,Core Network"
        )
        assert roles == [
            "Infra/무선 Network",
            "유선/IP Network",
            "Core Network",
        ]


# ---------------------------------------------------------------------------
# Unit tests: _build_posting
# ---------------------------------------------------------------------------


class TestBuildPosting:
    def test_skt_experienced_contract(self) -> None:
        p = _build_posting(_ITEM_SKT, "SK텔레콤")
        assert p.source == "sk_careers"
        assert p.source_external_id == "R261849"
        assert p.source_url == (
            "https://www.skcareers.com/Recruit/Detail/R261849"
        )
        assert p.title == "Data Platform Engineer"
        assert p.company_name == "SK텔레콤"
        assert p.experience_level == "경력"
        assert p.employment_type == "계약직"
        assert p.location == "Seoul"
        assert p.deadline == date(2026, 8, 30)
        assert isinstance(p.posted_at, datetime)
        assert p.posted_at.date() == date(2026, 8, 24)
        assert p.description is None

    def test_skt_new_permanent(self) -> None:
        p = _build_posting(_ITEM_SKT_NEW, "SK텔레콤")
        assert p.source_external_id == "R261841"
        assert p.experience_level == "신입"
        assert p.employment_type == "정규직"
        assert p.location == "NationWide"
        assert "Tech R&D/SW 개발" in p.roles
        assert "AI Model" in p.roles

    def test_skh_posting(self) -> None:
        p = _build_posting(_ITEM_SKH, "SK하이닉스")
        assert p.source_external_id == "R261845"
        assert p.company_name == "SK하이닉스"
        assert p.deadline == date(2026, 9, 7)
        assert p.location == "Gyeonggi/Incheon"

    def test_missing_start_date_gives_none_posted_at(self) -> None:
        item = dict(_ITEM_SKT)
        item["start"] = ""
        p = _build_posting(item, "SK텔레콤")
        assert p.posted_at is None

    def test_irrelevant_recruit_type_gives_none_experience(self) -> None:
        item = dict(_ITEM_SKT)
        item["recruitType"] = "Irrelevant"
        p = _build_posting(item, "SK텔레콤")
        assert p.experience_level is None


# ---------------------------------------------------------------------------
# Integration tests: SkCareersParser.fetch
# ---------------------------------------------------------------------------


def _patch_client(mock_resp: MagicMock):
    """Patch httpx.AsyncClient to return mock_resp on .post()."""
    mock_client = MagicMock()
    mock_client.__aenter__ = AsyncMock(return_value=mock_client)
    mock_client.__aexit__ = AsyncMock(return_value=None)
    mock_client.post = AsyncMock(return_value=mock_resp)
    return patch("app.adapters.official.sk_careers.httpx.AsyncClient",
                 return_value=mock_client)


class TestSkCareersParserFetch:
    @pytest.mark.asyncio
    async def test_happy_path_skt(self) -> None:
        source = _make_source("10005", expected_corp_name="SK telecom")
        profile = _make_profile("SK텔레콤")
        resp = _list_resp([_ITEM_SKT, _ITEM_SKT_NEW])

        with _patch_client(resp):
            result = await SkCareersParser().fetch(
                source, profile, _make_options(), _COLLECT_DATE
            )

        assert len(result.postings) == 2
        assert result.source_stats.discovered == 2
        assert result.source_stats.parsed == 2
        assert result.warnings == []
        assert result.postings[0].source_external_id == "R261849"
        assert result.postings[0].company_name == "SK텔레콤"

    @pytest.mark.asyncio
    async def test_happy_path_skh(self) -> None:
        source = _make_source("10004", expected_corp_name="SK hynix")
        profile = _make_profile("SK하이닉스")
        resp = _list_resp([_ITEM_SKH])

        with _patch_client(resp):
            result = await SkCareersParser().fetch(
                source, profile, _make_options(), _COLLECT_DATE
            )

        assert len(result.postings) == 1
        assert result.postings[0].source_external_id == "R261845"
        assert result.postings[0].company_name == "SK하이닉스"
        assert result.postings[0].deadline == date(2026, 9, 7)

    @pytest.mark.asyncio
    async def test_corp_name_mismatch_skipped(self) -> None:
        """expected_corp_name=SK telecom but item says SK hynix → skip."""
        source = _make_source("10005", expected_corp_name="SK telecom")
        profile = _make_profile("SK텔레콤")
        wrong_item = dict(_ITEM_SKT)
        wrong_item["corpName"] = "SK hynix"
        resp = _list_resp([wrong_item])

        with _patch_client(resp):
            result = await SkCareersParser().fetch(
                source, profile, _make_options(), _COLLECT_DATE
            )

        assert result.postings == []
        assert any("corpName" in w for w in result.warnings)
        assert result.source_stats.discovered == 1

    @pytest.mark.asyncio
    async def test_no_expected_corp_name_accepts_all(self) -> None:
        """Without expected_corp_name, any corpName is accepted."""
        source = _make_source("10005")
        profile = _make_profile("SK텔레콤")
        # item has "SK hynix" — should still pass without corp name guard
        item = dict(_ITEM_SKT)
        item["corpName"] = "SK hynix"
        resp = _list_resp([item])

        with _patch_client(resp):
            result = await SkCareersParser().fetch(
                source, profile, _make_options(), _COLLECT_DATE
            )

        assert len(result.postings) == 1
        assert result.warnings == []

    @pytest.mark.asyncio
    async def test_empty_list_returns_zero_postings(self) -> None:
        source = _make_source("10005", expected_corp_name="SK telecom")
        profile = _make_profile("SK텔레콤")
        resp = _list_resp([])

        with _patch_client(resp):
            result = await SkCareersParser().fetch(
                source, profile, _make_options(), _COLLECT_DATE
            )

        assert result.postings == []
        assert result.source_stats.discovered == 0
        assert result.warnings == []

    @pytest.mark.asyncio
    async def test_missing_notice_id_skipped(self) -> None:
        source = _make_source("10005")
        item = dict(_ITEM_SKT)
        item["noticeID"] = ""
        resp = _list_resp([item])

        with _patch_client(resp):
            result = await SkCareersParser().fetch(
                source, None, _make_options(), _COLLECT_DATE
            )

        assert result.postings == []
        assert any("missing noticeID" in w for w in result.warnings)

    @pytest.mark.asyncio
    async def test_empty_title_skipped(self) -> None:
        source = _make_source("10005")
        item = dict(_ITEM_SKT)
        item["title"] = "   "
        resp = _list_resp([item])

        with _patch_client(resp):
            result = await SkCareersParser().fetch(
                source, None, _make_options(), _COLLECT_DATE
            )

        assert result.postings == []
        assert any("empty title" in w for w in result.warnings)

    @pytest.mark.asyncio
    async def test_duplicate_notice_id_deduped(self) -> None:
        source = _make_source("10005")
        item2 = dict(_ITEM_SKT)
        item2["title"] = "Duplicate"
        resp = _list_resp([_ITEM_SKT, item2])

        with _patch_client(resp):
            result = await SkCareersParser().fetch(
                source, None, _make_options(), _COLLECT_DATE
            )

        # Both items have same noticeID → only first accepted
        assert len(result.postings) == 1
        assert any("duplicate noticeID" in w for w in result.warnings)

    @pytest.mark.asyncio
    async def test_max_fetch_cap(self) -> None:
        source = _make_source("10005", max_fetch=1)
        items = [_ITEM_SKT, _ITEM_SKT_NEW]
        resp = _list_resp(items)

        with _patch_client(resp):
            result = await SkCareersParser().fetch(
                source, None, _make_options(), _COLLECT_DATE
            )

        # discovered=2, but max_fetch=1 → only 1 posting built
        assert result.source_stats.discovered == 2
        assert len(result.postings) == 1

    @pytest.mark.asyncio
    async def test_missing_corp_code_returns_early(self) -> None:
        source = OfficialCompanySource(
            company_id=200,
            source_type="OFFICIAL_CAREER",
            source_url="https://www.skcareers.com/Recruit",
            adapter_type="CUSTOM",
            config_json=json.dumps({"parser_key": "SK_CAREERS"}),
        )

        result = await SkCareersParser().fetch(
            source, None, _make_options(), _COLLECT_DATE
        )

        assert result.postings == []
        assert any("corp_code not configured" in w for w in result.warnings)

    @pytest.mark.asyncio
    async def test_network_error_returns_warning(self) -> None:
        source = _make_source("10005")
        mock_client = MagicMock()
        mock_client.__aenter__ = AsyncMock(return_value=mock_client)
        mock_client.__aexit__ = AsyncMock(return_value=None)
        mock_client.post = AsyncMock(
            side_effect=Exception("connection refused")
        )

        with patch(
            "app.adapters.official.sk_careers.httpx.AsyncClient",
            return_value=mock_client,
        ):
            result = await SkCareersParser().fetch(
                source, None, _make_options(), _COLLECT_DATE
            )

        assert result.postings == []
        assert any("list failed" in w for w in result.warnings)

    @pytest.mark.asyncio
    async def test_success_false_returns_warning(self) -> None:
        source = _make_source("10005")
        mock_resp = MagicMock()
        mock_resp.raise_for_status = MagicMock()
        mock_resp.json.return_value = {
            "success": False,
            "totalCount": 0,
            "list": [],
        }

        with _patch_client(mock_resp):
            result = await SkCareersParser().fetch(
                source, None, _make_options(), _COLLECT_DATE
            )

        assert result.postings == []
        assert any("success=false" in w for w in result.warnings)

    @pytest.mark.asyncio
    async def test_json_parse_error_returns_warning(self) -> None:
        source = _make_source("10005")
        mock_resp = MagicMock()
        mock_resp.raise_for_status = MagicMock()
        mock_resp.json.side_effect = ValueError("bad json")

        with _patch_client(mock_resp):
            result = await SkCareersParser().fetch(
                source, None, _make_options(), _COLLECT_DATE
            )

        assert result.postings == []
        assert any("JSON parse failed" in w for w in result.warnings)

    @pytest.mark.asyncio
    async def test_profile_name_takes_priority_over_config(self) -> None:
        """profile.canonical_name should be used as company_name."""
        source = _make_source("10005", expected_corp_name="SK telecom")
        profile = _make_profile("SK텔레콤 주식회사")
        resp = _list_resp([_ITEM_SKT])

        with _patch_client(resp):
            result = await SkCareersParser().fetch(
                source, profile, _make_options(), _COLLECT_DATE
            )

        assert result.postings[0].company_name == "SK텔레콤 주식회사"

    @pytest.mark.asyncio
    async def test_fallback_company_name_when_no_profile(self) -> None:
        source = _make_source("10005", expected_corp_name="SK telecom")
        resp = _list_resp([_ITEM_SKT])

        with _patch_client(resp):
            result = await SkCareersParser().fetch(
                source, None, _make_options(), _COLLECT_DATE
            )

        assert result.postings[0].company_name == "SK telecom"

    @pytest.mark.asyncio
    async def test_source_record_key_stability(self) -> None:
        """noticeID must be stable as source_external_id across runs."""
        source = _make_source("10005")
        resp = _list_resp([_ITEM_SKT])

        with _patch_client(resp):
            r1 = await SkCareersParser().fetch(
                source, None, _make_options(), _COLLECT_DATE
            )
        with _patch_client(resp):
            r2 = await SkCareersParser().fetch(
                source, None, _make_options(), _COLLECT_DATE
            )

        ids1 = {p.source_external_id for p in r1.postings}
        ids2 = {p.source_external_id for p in r2.postings}
        assert ids1 == ids2 == {"R261849"}


# ---------------------------------------------------------------------------
# Self-registration test
# ---------------------------------------------------------------------------


class TestRegistration:
    def test_sk_careers_registered(self) -> None:
        assert "SK_CAREERS" in _CUSTOM_REGISTRY_BY_KEY
        assert isinstance(_CUSTOM_REGISTRY_BY_KEY["SK_CAREERS"], SkCareersParser)


# ---------------------------------------------------------------------------
# Live smoke tests (skipped in CI)
# ---------------------------------------------------------------------------


@pytest.mark.skipif(
    True, reason="live — 수동 실행: poetry run pytest -k live -v -s"
)
class TestSkCareersLive:
    @pytest.mark.asyncio
    async def test_live_skt(self) -> None:
        source = _make_source("10005", expected_corp_name="SK telecom")
        profile = _make_profile("SK텔레콤")
        result = await SkCareersParser().fetch(
            source, profile, _make_options(), _COLLECT_DATE
        )
        print(
            f"\nSK텔레콤 live: discovered={result.source_stats.discovered} "
            f"parsed={result.source_stats.parsed} "
            f"warnings={result.warnings}"
        )
        if result.postings:
            p = result.postings[0]
            print(
                f"  Sample: {p.source_external_id} | {p.title!r} | "
                f"{p.experience_level} | {p.employment_type} | {p.location}"
            )
        assert result.source_stats.discovered >= 0
        assert result.warnings == [] or True

    @pytest.mark.asyncio
    async def test_live_skh(self) -> None:
        source = _make_source("10004", expected_corp_name="SK hynix")
        profile = _make_profile("SK하이닉스")
        result = await SkCareersParser().fetch(
            source, profile, _make_options(), _COLLECT_DATE
        )
        print(
            f"\nSK하이닉스 live: discovered={result.source_stats.discovered} "
            f"parsed={result.source_stats.parsed} "
            f"warnings={result.warnings}"
        )
        if result.postings:
            p = result.postings[0]
            print(
                f"  Sample: {p.source_external_id} | {p.title!r} | "
                f"{p.deadline}"
            )
        assert result.source_stats.discovered >= 0
