"""Tests for HYUNDAI_MOTOR_CAREERS adapter (hyundai_motor_careers.py).

Coverage targets:
  - _parse_config: max_fetch, defaults
  - _source_external_id / _source_url: composite key stability
  - _parse_date_yyyymmdd: YYYYMMDD format, edge cases
  - _experience_level: 경력/신입/인턴 mapping
  - _roles: fldCodeNm extraction
  - _build_posting: all fields, detail/no-detail variants
  - HyundaiMotorCareersParser.fetch:
      happy path (both tabs), logoNm mismatch skip, duplicate dedup,
      empty title skip, missing recuCls skip, network error,
      JSON parse error, detail fail (partial), max_fetch cap,
      theme hall inactive (empty list), source_record_key stability
  - Self-registration under "HYUNDAI_MOTOR_CAREERS" key
  - Live smoke test (skipped in CI)
"""

from __future__ import annotations

import json
from datetime import date, datetime
from typing import Any
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from app.adapters.official.hyundai_motor_careers import (
    HyundaiMotorCareersParser,
    _build_posting,
    _experience_level,
    _parse_config,
    _parse_date_yyyymmdd,
    _roles,
    _source_external_id,
    _source_url,
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


def _make_source(max_fetch: int = 50) -> OfficialCompanySource:
    return OfficialCompanySource(
        company_id=300,
        source_type="OFFICIAL_CAREER",
        source_url="https://talent.hyundai.com/theme/hall.hc",
        adapter_type="CUSTOM",
        config_json=json.dumps(
            {
                "parser_key": "HYUNDAI_MOTOR_CAREERS",
                "max_fetch": max_fetch,
            }
        ),
    )


def _make_profile(canonical: str = "현대자동차") -> CompanyProfile:
    return CompanyProfile(
        id=300,
        canonical_name=canonical,
        normalized_name=canonical.lower(),
    )


def _make_options() -> CollectionOptions:
    return CollectionOptions()


def _list_item(
    recu_cls: int = 295,
    recu_yy: str = "2026",
    recu_type: str = "N2",
    title: str = "SDV E&E 통합·배포·검증",
    fld_code_nm: str = "Embedded SW",
    sec_code_nm: str = "연구개발",
    work_place_code_nm: str = "판교",
    channel_code_nm: str = "경력",
    app_disp_ed_dt: str = "20260830",
    logo_nm: str = "현대",
    jd_recu_cate: str = "01",
) -> dict[str, Any]:
    return {
        "recuYy": recu_yy,
        "recuType": recu_type,
        "recuCls": recu_cls,
        "recuNoticeNm": title,
        "fldCodeNm": fld_code_nm,
        "secCodeNm": sec_code_nm,
        "workPlaceCodeNm": work_place_code_nm,
        "channelCodeNm": channel_code_nm,
        "channelCode": "N2",
        "appDispEdDt": app_disp_ed_dt,
        "logoNm": logo_nm,
        "recuNoticeLogo": "02" if logo_nm == "현대" else "01",
        "jdRecuCate": jd_recu_cate,
        "hashTag": "8월 경력 채용",
        "intnsvRecuYn": "Y",
        "collectMark": "8월 경력채용",
        "applyEndTm": "17:05",
        "applyCountDt2": "5",
    }


def _detail_info(
    recu_cls: int = 295,
    title: str = "SDV E&E 통합·배포·검증",
    apply_start_dt: str = "20260815",
    priv_jd_dtl: str = "직무 설명입니다.",
    priv_must_req: str = "자격 요건입니다.",
    recu_notice_secret_yn: str = "N",
) -> dict[str, Any]:
    return {
        "recuYy": "2026",
        "recuType": "N2",
        "recuCls": recu_cls,
        "recuNoticeNm": title,
        "applyStartDt": apply_start_dt,
        "applyEndDt": "20260830",
        "applyStartTm": "0900",
        "applyEndTm": "1705",
        "appDispEdDt": "20260830",
        "appDispEdTm": "1700",
        "secCodeNm": "연구개발",
        "fldCodeNm": "Embedded SW",
        "workPlaceCodeNm": "판교",
        "channelCodeNm": "경력",
        "logoNm": "현대",
        "recuNoticeSecretYn": recu_notice_secret_yn,
        "privJdDtl": priv_jd_dtl,
        "privMustReq": priv_must_req,
        "etc": "",
    }


def _list_resp(items: list[dict[str, Any]], cnt: int | None = None) -> MagicMock:
    m = MagicMock()
    m.raise_for_status = MagicMock()
    m.status_code = 200
    m.json.return_value = {
        "status": 200,
        "message": "OK",
        "data": {
            "applyList": items,
            "cnt": cnt if cnt is not None else len(items),
            "themaInfo": [{"pageUseYn": "Y", "showYn": "Y"}],
            "fldList": [],
            "secList": [],
        },
    }
    return m


def _detail_resp(detail: dict[str, Any]) -> MagicMock:
    m = MagicMock()
    m.raise_for_status = MagicMock()
    m.status_code = 200
    m.json.return_value = {
        "status": 200,
        "message": "OK",
        "data": {"applyInfo": detail, "applyFileList": [], "applyCnt": 0},
    }
    return m


_ITEM_01 = _list_item(295, title="SDV E&E 통합·배포·검증", jd_recu_cate="01")
_ITEM_02 = _list_item(
    297,
    title="[AVP] 모빌리티 플랫폼 사업 개발",
    fld_code_nm="경영전략",
    work_place_code_nm="서울",
    app_disp_ed_dt="20261231",
    jd_recu_cate="02",
)
_DETAIL_295 = _detail_info(295, title="SDV E&E 통합·배포·검증")
_DETAIL_297 = _detail_info(297, title="[AVP] 모빌리티 플랫폼 사업 개발")


def _patch_clients(
    tab01_resp: MagicMock, tab02_resp: MagicMock, *detail_resps: MagicMock
):
    """Patch httpx.AsyncClient to return specific responses per GET call."""
    call_counts = [0]

    async def fake_get(url: str, **kwargs):
        params = kwargs.get("params", {})
        tab = params.get("jdRecuCate", "")
        if tab == "01":
            return tab01_resp
        elif tab == "02":
            return tab02_resp
        else:
            # Detail calls
            idx = call_counts[0]
            call_counts[0] += 1
            if idx < len(detail_resps):
                return detail_resps[idx]
            return (
                detail_resps[-1]
                if detail_resps
                else MagicMock(
                    raise_for_status=MagicMock(),
                    json=MagicMock(
                        return_value={"status": 200, "data": {"applyInfo": {}}}
                    ),
                )
            )

    mock_client = MagicMock()
    mock_client.__aenter__ = AsyncMock(return_value=mock_client)
    mock_client.__aexit__ = AsyncMock(return_value=None)
    mock_client.get = AsyncMock(side_effect=fake_get)

    return patch(
        "app.adapters.official.hyundai_motor_careers.httpx.AsyncClient",
        return_value=mock_client,
    )


# ---------------------------------------------------------------------------
# Unit tests: _parse_config
# ---------------------------------------------------------------------------


class TestParseConfig:
    def test_full_config(self) -> None:
        cfg = json.dumps({"parser_key": "HYUNDAI_MOTOR_CAREERS", "max_fetch": 30})
        c = _parse_config(cfg)
        assert c.max_fetch == 30

    def test_defaults(self) -> None:
        c = _parse_config(json.dumps({}))
        assert c.max_fetch == 50

    def test_none_json(self) -> None:
        c = _parse_config(None)
        assert c.max_fetch == 50

    def test_malformed_json(self) -> None:
        c = _parse_config("not json")
        assert c.max_fetch == 50


# ---------------------------------------------------------------------------
# Unit tests: pure helpers
# ---------------------------------------------------------------------------


class TestPureHelpers:
    def test_source_external_id(self) -> None:
        item = {"recuYy": "2026", "recuType": "N2", "recuCls": 295}
        assert _source_external_id(item) == "2026_N2_295"

    def test_source_url(self) -> None:
        item = {"recuYy": "2026", "recuType": "N2", "recuCls": 295}
        url = _source_url(item)
        assert "recuYy=2026" in url
        assert "recuType=N2" in url
        assert "recuCls=295" in url
        assert url.startswith("https://talent.hyundai.com/apply/applyView.hc")

    def test_parse_date_yyyymmdd_valid(self) -> None:
        assert _parse_date_yyyymmdd("20260830") == date(2026, 8, 30)

    def test_parse_date_yyyymmdd_none(self) -> None:
        assert _parse_date_yyyymmdd(None) is None

    def test_parse_date_yyyymmdd_empty(self) -> None:
        assert _parse_date_yyyymmdd("") is None

    def test_parse_date_yyyymmdd_invalid(self) -> None:
        assert _parse_date_yyyymmdd("invalid") is None

    def test_experience_level_career(self) -> None:
        assert _experience_level("경력") == "경력"

    def test_experience_level_new(self) -> None:
        assert _experience_level("신입") == "신입"

    def test_experience_level_intern(self) -> None:
        assert _experience_level("인턴") == "인턴"

    def test_experience_level_combined(self) -> None:
        assert _experience_level("신입/경력") == "신입/경력"
        assert _experience_level("경력/신입") == "신입/경력"

    def test_experience_level_empty(self) -> None:
        assert _experience_level("") is None

    def test_experience_level_none(self) -> None:
        assert _experience_level(None) is None

    def test_roles_fld_code_nm(self) -> None:
        item = {"fldCodeNm": "Embedded SW"}
        assert _roles(item) == ["Embedded SW"]

    def test_roles_empty(self) -> None:
        item = {"fldCodeNm": ""}
        assert _roles(item) == []

    def test_roles_missing(self) -> None:
        assert _roles({}) == []


# ---------------------------------------------------------------------------
# Unit tests: _build_posting
# ---------------------------------------------------------------------------


class TestBuildPosting:
    def test_full_with_detail(self) -> None:
        p = _build_posting(_ITEM_01, "현대자동차", _DETAIL_295)
        assert p.source == "hyundai_motor_careers"
        assert p.source_external_id == "2026_N2_295"
        assert "applyView.hc" in p.source_url
        assert "recuCls=295" in p.source_url
        assert p.company_name == "현대자동차"
        assert p.title == "SDV E&E 통합·배포·검증"
        assert p.roles == ["Embedded SW"]
        assert p.location == "판교"
        assert p.experience_level == "경력"
        assert p.employment_type == "정규직"
        assert p.deadline == date(2026, 8, 30)
        assert isinstance(p.posted_at, datetime)
        assert p.posted_at.date() == date(2026, 8, 15)
        assert "직무 설명" in (p.description or "")

    def test_without_detail(self) -> None:
        p = _build_posting(_ITEM_01, "현대자동차", None)
        assert p.title == "SDV E&E 통합·배포·검증"
        assert p.deadline == date(2026, 8, 30)
        assert p.posted_at is None
        assert p.description is None

    def test_tab02_item(self) -> None:
        p = _build_posting(_ITEM_02, "현대자동차", None)
        assert p.source_external_id == "2026_N2_297"
        assert p.deadline == date(2026, 12, 31)
        assert p.location == "서울"
        assert p.roles == ["경영전략"]

    def test_employment_type_is_always_정규직(self) -> None:
        p = _build_posting(_ITEM_01, "현대자동차", None)
        assert p.employment_type == "정규직"

    def test_missing_start_date_gives_none_posted_at(self) -> None:
        detail_no_start = dict(_DETAIL_295)
        detail_no_start["applyStartDt"] = None
        p = _build_posting(_ITEM_01, "현대자동차", detail_no_start)
        assert p.posted_at is None


# ---------------------------------------------------------------------------
# Integration tests: HyundaiMotorCareersParser.fetch
# ---------------------------------------------------------------------------


class TestHyundaiMotorCareersParserFetch:
    @pytest.mark.asyncio
    async def test_happy_path_both_tabs(self) -> None:
        source = _make_source()
        profile = _make_profile()
        tab01 = _list_resp([_ITEM_01])
        tab02 = _list_resp([_ITEM_02])

        with _patch_clients(
            tab01, tab02, _detail_resp(_DETAIL_295), _detail_resp(_DETAIL_297)
        ):
            result = await HyundaiMotorCareersParser().fetch(
                source, profile, _make_options(), _COLLECT_DATE
            )

        assert len(result.postings) == 2
        assert result.source_stats.discovered == 2
        assert result.source_stats.parsed == 2
        assert result.warnings == []
        ext_ids = {p.source_external_id for p in result.postings}
        assert ext_ids == {"2026_N2_295", "2026_N2_297"}

    @pytest.mark.asyncio
    async def test_logo_nm_mismatch_skipped(self) -> None:
        """Non-현대 logo (e.g. Genesis) → skip."""
        genesis_item = _list_item(501, title="제네시스 공고", logo_nm="제네시스")
        tab01 = _list_resp([genesis_item])
        tab02 = _list_resp([])

        with _patch_clients(tab01, tab02):
            result = await HyundaiMotorCareersParser().fetch(
                _make_source(), None, _make_options(), _COLLECT_DATE
            )

        assert result.postings == []
        assert any("logoNm" in w for w in result.warnings)

    @pytest.mark.asyncio
    async def test_duplicate_across_tabs_deduped(self) -> None:
        """Same recuCls in both tabs → only one posting."""
        item_dup = _list_item(295, jd_recu_cate="02")
        tab01 = _list_resp([_ITEM_01])
        tab02 = _list_resp([item_dup])

        with _patch_clients(tab01, tab02, _detail_resp(_DETAIL_295)):
            result = await HyundaiMotorCareersParser().fetch(
                _make_source(), _make_profile(), _make_options(), _COLLECT_DATE
            )

        assert len(result.postings) == 1
        assert result.source_stats.discovered == 2
        assert any("duplicate" in w for w in result.warnings)

    @pytest.mark.asyncio
    async def test_empty_title_skipped(self) -> None:
        item_no_title = _list_item(295, title="  ")
        tab01 = _list_resp([item_no_title])
        tab02 = _list_resp([])

        with _patch_clients(tab01, tab02):
            result = await HyundaiMotorCareersParser().fetch(
                _make_source(), None, _make_options(), _COLLECT_DATE
            )

        assert result.postings == []
        assert any("empty title" in w for w in result.warnings)

    @pytest.mark.asyncio
    async def test_theme_hall_inactive_empty_lists(self) -> None:
        tab01 = _list_resp([])
        tab02 = _list_resp([])

        with _patch_clients(tab01, tab02):
            result = await HyundaiMotorCareersParser().fetch(
                _make_source(), None, _make_options(), _COLLECT_DATE
            )

        assert result.postings == []
        assert result.source_stats.discovered == 0
        assert result.warnings == []

    @pytest.mark.asyncio
    async def test_list_network_error_tab01(self) -> None:
        mock_client = MagicMock()
        mock_client.__aenter__ = AsyncMock(return_value=mock_client)
        mock_client.__aexit__ = AsyncMock(return_value=None)
        mock_client.get = AsyncMock(side_effect=Exception("connection refused"))

        with patch(
            "app.adapters.official.hyundai_motor_careers.httpx.AsyncClient",
            return_value=mock_client,
        ):
            result = await HyundaiMotorCareersParser().fetch(
                _make_source(), None, _make_options(), _COLLECT_DATE
            )

        assert result.postings == []
        assert any("list failed" in w for w in result.warnings)

    @pytest.mark.asyncio
    async def test_list_json_parse_error(self) -> None:
        bad_resp = MagicMock()
        bad_resp.raise_for_status = MagicMock()
        bad_resp.json.side_effect = ValueError("bad json")

        with _patch_clients(bad_resp, bad_resp):
            result = await HyundaiMotorCareersParser().fetch(
                _make_source(), None, _make_options(), _COLLECT_DATE
            )

        assert result.postings == []
        assert any("JSON parse failed" in w for w in result.warnings)

    @pytest.mark.asyncio
    async def test_detail_failure_still_parses_listing_data(self) -> None:
        """Detail fetch failure → posting built from listing data (no desc)."""
        tab01 = _list_resp([_ITEM_01])
        tab02 = _list_resp([])

        err_resp = MagicMock()
        err_resp.raise_for_status = AsyncMock(side_effect=Exception("detail 500"))
        err_resp.raise_for_status.side_effect = Exception("detail 500")

        async def fake_get(url: str, **kwargs):
            params = kwargs.get("params", {})
            tab = params.get("jdRecuCate", "")
            if tab == "01":
                return tab01
            elif tab == "02":
                return tab02
            else:
                raise Exception("detail network error")

        mock_client = MagicMock()
        mock_client.__aenter__ = AsyncMock(return_value=mock_client)
        mock_client.__aexit__ = AsyncMock(return_value=None)
        mock_client.get = AsyncMock(side_effect=fake_get)

        with patch(
            "app.adapters.official.hyundai_motor_careers.httpx.AsyncClient",
            return_value=mock_client,
        ):
            result = await HyundaiMotorCareersParser().fetch(
                _make_source(), _make_profile(), _make_options(), _COLLECT_DATE
            )

        # Posting still built from listing data
        assert len(result.postings) == 1
        assert result.postings[0].description is None
        assert result.postings[0].title == "SDV E&E 통합·배포·검증"
        assert any("detail failed" in w for w in result.warnings)

    @pytest.mark.asyncio
    async def test_max_fetch_cap(self) -> None:
        """max_fetch=1 → only 1 of 2 items processed."""
        tab01 = _list_resp([_ITEM_01, _ITEM_02])
        tab02 = _list_resp([])

        with _patch_clients(tab01, tab02, _detail_resp(_DETAIL_295)):
            result = await HyundaiMotorCareersParser().fetch(
                _make_source(max_fetch=1),
                _make_profile(),
                _make_options(),
                _COLLECT_DATE,
            )

        assert result.source_stats.discovered == 2
        assert len(result.postings) == 1

    @pytest.mark.asyncio
    async def test_profile_name_takes_priority(self) -> None:
        source = _make_source()
        profile = _make_profile("현대자동차 주식회사")
        tab01 = _list_resp([_ITEM_01])
        tab02 = _list_resp([])

        with _patch_clients(tab01, tab02, _detail_resp(_DETAIL_295)):
            result = await HyundaiMotorCareersParser().fetch(
                source, profile, _make_options(), _COLLECT_DATE
            )

        assert result.postings[0].company_name == "현대자동차 주식회사"

    @pytest.mark.asyncio
    async def test_no_profile_uses_default_name(self) -> None:
        tab01 = _list_resp([_ITEM_01])
        tab02 = _list_resp([])

        with _patch_clients(tab01, tab02, _detail_resp(_DETAIL_295)):
            result = await HyundaiMotorCareersParser().fetch(
                _make_source(), None, _make_options(), _COLLECT_DATE
            )

        assert result.postings[0].company_name == "현대자동차"

    @pytest.mark.asyncio
    async def test_source_record_key_stability(self) -> None:
        """source_external_id must be stable across two runs."""
        tab01 = _list_resp([_ITEM_01])
        tab02 = _list_resp([_ITEM_02])

        detail_pair = (_detail_resp(_DETAIL_295), _detail_resp(_DETAIL_297))
        with _patch_clients(tab01, tab02, *detail_pair):
            r1 = await HyundaiMotorCareersParser().fetch(
                _make_source(), _make_profile(), _make_options(), _COLLECT_DATE
            )
        with _patch_clients(tab01, tab02, *detail_pair):
            r2 = await HyundaiMotorCareersParser().fetch(
                _make_source(), _make_profile(), _make_options(), _COLLECT_DATE
            )

        ids1 = {p.source_external_id for p in r1.postings}
        ids2 = {p.source_external_id for p in r2.postings}
        assert ids1 == ids2 == {"2026_N2_295", "2026_N2_297"}


# ---------------------------------------------------------------------------
# Self-registration test
# ---------------------------------------------------------------------------


class TestRegistration:
    def test_hyundai_motor_careers_registered(self) -> None:
        assert "HYUNDAI_MOTOR_CAREERS" in _CUSTOM_REGISTRY_BY_KEY
        assert isinstance(
            _CUSTOM_REGISTRY_BY_KEY["HYUNDAI_MOTOR_CAREERS"],
            HyundaiMotorCareersParser,
        )


# ---------------------------------------------------------------------------
# Live smoke tests (skipped in CI)
# ---------------------------------------------------------------------------


@pytest.mark.skipif(True, reason="live — 수동 실행: poetry run pytest -k live -v -s")
class TestHyundaiMotorCareersLive:
    @pytest.mark.asyncio
    async def test_live_full(self) -> None:
        source = _make_source(max_fetch=5)
        profile = _make_profile("현대자동차")
        result = await HyundaiMotorCareersParser().fetch(
            source, profile, _make_options(), _COLLECT_DATE
        )
        print(
            f"\n현대자동차 live: discovered={result.source_stats.discovered} "
            f"parsed={result.source_stats.parsed} "
            f"warnings={result.warnings}"
        )
        for p in result.postings[:3]:
            print(
                f"  [{p.source_external_id}] {p.title!r} | "
                f"loc={p.location} | deadline={p.deadline}"
            )
        assert result.source_stats.discovered >= 0
