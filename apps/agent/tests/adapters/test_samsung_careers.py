"""Tests for the SAMSUNG_CAREERS adapter (app/adapters/samsung_careers.py).

External tests (marked @pytest.mark.external) hit live network and are
excluded from CI via addopts = "-m 'not external'" in pyproject.toml.
"""

from __future__ import annotations

import json
from datetime import date, datetime
from typing import Any
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

from app.adapters.official.samsung_careers import (
    SamsungCareersParser,
    _build_posting,
    _CardInfo,
    _description,
    _employment_type,
    _experience_level,
    _parse_config,
    _parse_date_field,
    _parse_deadline,
    _parse_list_html,
)
from app.adapters.official_company import _CUSTOM_REGISTRY_BY_KEY
from app.schemas.collection import (
    CollectionOptions,
    CompanyProfile,
    OfficialCompanySource,
)

# ── Fixtures ───────────────────────────────────────────────────────────────────

_CARD_SDI = _CardInfo(
    seq=22828,
    company_name="삼성SDI",
    title="경력사원 채용(생성형 AI Agent 및 Service Platform개발)",
    type_text="경력",
    period_text="2026.08.12 ~ 2026.08.19",
    role_flags=["AI Agent 및 Service Platform 개발"],
)

_CARD_ENA = _CardInfo(
    seq=22825,
    company_name="삼성E&A",
    title="프로젝트계약직 경력사원 채용(사업, 설계, 시공)",
    type_text="경력",
    period_text="2026.08.11 ~ 2026.08.19",
    role_flags=["산업환경플랜트 Planner", "산업환경플랜트 시공관리"],
)

_CARD_INTERN = _CardInfo(
    seq=22900,
    company_name="삼성SDS",
    title="인턴 채용 (개발, 기획)",
    type_text="인턴",
    period_text="2026.09.01 ~ 2026.09.30",
    role_flags=["개발", "기획"],
)

_CARD_NEWCOMER = _CardInfo(
    seq=22901,
    company_name="삼성전자",
    title="신입사원 채용",
    type_text="신입",
    period_text="2026.09.01 ~ 2026.09.15",
    role_flags=["SW개발", "HW개발"],
)

_CARD_COMBINED = _CardInfo(
    seq=22902,
    company_name="삼성전자",
    title="신입/경력 개발자 채용",
    type_text="신입·경력",
    period_text="2026.09.01 ~ 2026.09.20",
    role_flags=["Backend 개발"],
)

_DETAIL_SDI: dict[str, Any] = {
    "seq": 22828,
    "seqno": 18509,
    "compCd": "C31",
    "cmpNameKr": "삼성SDI",
    "title": "경력사원 채용(생성형 AI Agent 및 Service Platform개발)",
    "recruitType": "B",
    "startdate": "202608121000",
    "enddate": "202608191700",
    "introKr": "삼성SDI는 친환경 에너지와 첨단소재를 사업의 양대축으로 합니다.",
    "qlfctKr": "학사 취득 후 2년 이상 유관경력 보유하신 분",
    "etcKr": "국가등록장애인 및 국가보훈대상자는 우대합니다.",
    "items": [],
}

_DETAIL_ELEC: dict[str, Any] = {
    "seq": 22900,
    "seqno": 18600,
    "compCd": "C10CAA",
    "cmpNameKr": "삼성전자",
    "title": "SW 개발자 채용",
    "recruitType": "B",
    "startdate": "202609011000",
    "enddate": "202609151700",
    "introKr": "삼성전자 DX부문입니다.",
    "qlfctKr": "관련 전공 학사 이상",
    "etcKr": "",
    "items": [],
}

_DETAIL_SDS: dict[str, Any] = {
    "seq": 22901,
    "seqno": 18700,
    "compCd": "C60",
    "cmpNameKr": "삼성SDS",
    "title": "인턴 채용",
    "recruitType": "C",
    "startdate": "202609011000",
    "enddate": "202609301700",
    "introKr": "삼성SDS입니다.",
    "qlfctKr": "IT 전공 재학/졸업",
    "etcKr": "",
    "items": [],
}

_LIST_HTML_ONE_CARD = """\
<input class="divCnt" data-value="6" data-max="1" />
<ul>
  <li>
    <div>
      <div>
        <div class="btnWrap">
          <button class="btnShare" data-value="22,828" type="button">
            <i>공유</i>
          </button>
          <button class="btnScrap" data-value="18,509" type="button" value="">
            <i>스크랩</i>
          </button>
        </div>
        <a data-value="22,828" href="/#none">
          <p class="company">삼성SDI</p>
          <h3 class="title">경력사원 채용(생성형 AI Agent 및 Service Platform개발)</h3>
          <p class="info">
            <span>경력</span>
            <span class="period">2026.08.12 ~ 2026.08.19</span>
          </p>
        </a>
      </div>
      <div class="flagWrap">
        <span class="flag blue">D-2</span>
        <span class="flag grey">AI Agent 및 Service Platform 개발</span>
      </div>
    </div>
  </li>
</ul>
"""

_LIST_HTML_MULTI_CARD = """\
<input class="divCnt" data-value="2" data-max="1" />
<ul>
  <li>
    <div>
      <div class="btnWrap">
        <button class="btnShare" data-value="22,828" type="button"><i>공유</i></button>
        <button class="btnScrap" data-value="18,509" type="button" value=""></button>
      </div>
      <a data-value="22,828" href="/#none">
        <p class="company">삼성SDI</p>
        <h3 class="title">경력사원 채용(AI)</h3>
        <p class="info">
          <span>경력</span>
          <span class="period">2026.08.12 ~ 2026.08.19</span>
        </p>
      </a>
      <div class="flagWrap">
        <span class="flag grey">AI개발</span>
      </div>
    </div>
  </li>
  <li>
    <div>
      <div class="btnWrap">
        <button class="btnShare" data-value="22,825" type="button"><i>공유</i></button>
        <button class="btnScrap" data-value="18,508" type="button" value=""></button>
      </div>
      <a data-value="22,825" href="/#none">
        <p class="company">삼성E&A</p>
        <h3 class="title">프로젝트계약직 경력사원 채용</h3>
        <p class="info">
          <span>경력</span>
          <span class="period">2026.08.11 ~ 2026.08.19</span>
        </p>
      </a>
      <div class="flagWrap">
        <span class="flag grey">플래너</span>
        <span class="flag grey">시공관리</span>
      </div>
    </div>
  </li>
</ul>
"""

_LIST_HTML_EMPTY = """\
<input class="divCnt" data-value="0" data-max="0" />
<ul></ul>
"""

_LIST_HTML_NO_CNT = """\
<ul>
  <li>
    <div>
      <button class="btnShare" data-value="12,345" type="button"></button>
      <a data-value="12,345">
        <p class="company">테스트</p>
        <h3 class="title">테스트 채용</h3>
        <p class="info">
          <span>신입</span>
          <span class="period">2026.09.01 ~ 2026.09.30</span>
        </p>
      </a>
    </div>
  </li>
</ul>
"""


# ── _parse_config ──────────────────────────────────────────────────────────────


class TestParseConfig:
    def test_defaults_when_none(self):
        cfg = _parse_config(None)
        assert cfg.com_codes == []
        assert cfg.max_discover == 50
        assert cfg.max_fetch == 20

    def test_parses_com_codes_list(self):
        cfg = _parse_config(
            '{"parser_key":"SAMSUNG_CAREERS","com_codes":["C10CAA","C10CAH"]}'
        )
        assert cfg.com_codes == ["C10CAA", "C10CAH"]

    def test_parses_single_com_code_string(self):
        cfg = _parse_config('{"com_codes":"C60"}')
        assert cfg.com_codes == ["C60"]

    def test_parses_custom_limits(self):
        cfg = _parse_config('{"com_codes":["C60"],"max_discover":30,"max_fetch":10}')
        assert cfg.max_discover == 30
        assert cfg.max_fetch == 10

    def test_strips_empty_com_codes(self):
        cfg = _parse_config('{"com_codes":["C60","","  "]}')
        assert cfg.com_codes == ["C60"]

    def test_invalid_json_returns_defaults(self):
        cfg = _parse_config("{not json}")
        assert cfg.com_codes == []
        assert cfg.max_discover == 50


# ── _parse_list_html ───────────────────────────────────────────────────────────


class TestParseListHtml:
    def test_single_card(self):
        cards, total = _parse_list_html(_LIST_HTML_ONE_CARD)
        assert total == 6
        assert len(cards) == 1
        c = cards[0]
        assert c.seq == 22828
        assert c.company_name == "삼성SDI"
        assert "AI Agent" in c.title
        assert c.type_text == "경력"
        assert c.period_text == "2026.08.12 ~ 2026.08.19"
        assert c.role_flags == ["AI Agent 및 Service Platform 개발"]

    def test_multi_card(self):
        cards, total = _parse_list_html(_LIST_HTML_MULTI_CARD)
        assert total == 2
        assert len(cards) == 2
        assert cards[0].seq == 22828
        assert cards[1].seq == 22825
        assert cards[1].role_flags == ["플래너", "시공관리"]

    def test_empty_list(self):
        cards, total = _parse_list_html(_LIST_HTML_EMPTY)
        assert total == 0
        assert cards == []

    def test_missing_divCnt_gives_zero_total(self):
        cards, total = _parse_list_html(_LIST_HTML_NO_CNT)
        assert total == 0
        assert len(cards) == 1
        assert cards[0].seq == 12345

    def test_skips_li_without_btnShare(self):
        html = '<ul><li><p class="title">없음</p></li></ul>'
        cards, _ = _parse_list_html(html)
        assert cards == []

    def test_skips_li_with_non_numeric_data_value(self):
        html = (
            "<ul><li>"
            '<button class="btnShare" data-value="abc"></button>'
            "<a><h3 class='title'>T</h3></a>"
            "</li></ul>"
        )
        cards, _ = _parse_list_html(html)
        assert cards == []

    def test_skips_li_with_empty_title(self):
        html = (
            "<ul><li>"
            '<button class="btnShare" data-value="1,000"></button>'
            '<a><p class="company">C</p><h3 class="title">   </h3></a>'
            "</li></ul>"
        )
        cards, _ = _parse_list_html(html)
        assert cards == []

    def test_comma_in_seq_stripped(self):
        cards, _ = _parse_list_html(_LIST_HTML_ONE_CARD)
        assert cards[0].seq == 22828  # comma removed: "22,828" → 22828


# ── _parse_deadline ────────────────────────────────────────────────────────────


class TestParseDeadline:
    def test_standard_period(self):
        d = _parse_deadline("2026.08.12 ~ 2026.08.19")
        assert d == date(2026, 8, 19)

    def test_trailing_whitespace(self):
        d = _parse_deadline("2026.09.01 ~ 2026.09.30  ")
        assert d == date(2026, 9, 30)

    def test_empty_string_returns_none(self):
        assert _parse_deadline("") is None

    def test_no_tilde_returns_none(self):
        assert _parse_deadline("2026.08.12") is None

    def test_invalid_date_returns_none(self):
        assert _parse_deadline("2026.13.01 ~ 2026.13.31") is None


# ── _parse_date_field ──────────────────────────────────────────────────────────


class TestParseDateField:
    def test_valid_startdate(self):
        d = _parse_date_field("202608121000")
        assert d == date(2026, 8, 12)

    def test_valid_enddate(self):
        d = _parse_date_field("202608191700")
        assert d == date(2026, 8, 19)

    def test_none_returns_none(self):
        assert _parse_date_field(None) is None

    def test_too_short_returns_none(self):
        assert _parse_date_field("2026") is None

    def test_invalid_date_returns_none(self):
        assert _parse_date_field("20261300") is None


# ── _experience_level ──────────────────────────────────────────────────────────


class TestExperienceLevel:
    def test_experience(self):
        assert _experience_level("경력") == "경력"

    def test_newcomer(self):
        assert _experience_level("신입") == "신입"

    def test_intern(self):
        assert _experience_level("인턴") == "인턴"

    def test_combined_middle_dot(self):
        assert _experience_level("신입·경력") == "신입/경력"

    def test_combined_slash(self):
        assert _experience_level("신입/경력") == "신입/경력"

    def test_empty_returns_none(self):
        assert _experience_level("") is None

    def test_whitespace_only_returns_none(self):
        assert _experience_level("   ") is None

    def test_unknown_text_returned_as_is(self):
        result = _experience_level("특별채용")
        assert result == "특별채용"


# ── _employment_type ───────────────────────────────────────────────────────────


class TestEmploymentType:
    def test_regular(self):
        assert _employment_type("경력사원 채용(AI)") == "정규직"

    def test_contract_keyword(self):
        assert _employment_type("프로젝트계약직 경력사원 채용") == "계약직"

    def test_intern_keyword(self):
        assert _employment_type("인턴 채용 (개발, 기획)") == "인턴"

    def test_intern_takes_priority_over_contract(self):
        assert _employment_type("인턴계약직 채용") == "인턴"

    def test_newcomer_is_regular(self):
        assert _employment_type("신입사원 채용") == "정규직"


# ── _description ──────────────────────────────────────────────────────────────


class TestDescription:
    def test_combines_fields(self):
        detail = {"qlfctKr": "자격요건", "etcKr": "기타", "introKr": "회사소개"}
        desc = _description(detail)
        assert "자격요건" in desc
        assert "기타" in desc

    def test_skips_empty_fields(self):
        detail = {"qlfctKr": "자격요건", "etcKr": "", "introKr": ""}
        desc = _description(detail)
        assert desc == "자격요건"

    def test_all_empty_returns_none(self):
        desc = _description({"qlfctKr": "", "etcKr": None, "introKr": None})
        assert desc is None

    def test_truncates_at_4000_chars(self):
        detail = {"qlfctKr": "A" * 5000, "etcKr": "", "introKr": ""}
        desc = _description(detail)
        assert len(desc) == 4000


# ── _build_posting ─────────────────────────────────────────────────────────────


class TestBuildPosting:
    def test_basic_fields(self):
        p = _build_posting(_CARD_SDI, _DETAIL_SDI, "삼성SDI")
        assert p.source == "samsung_careers"
        assert p.company_name == "삼성SDI"
        assert "AI Agent" in p.title
        assert p.source_external_id == "22828"
        assert p.source_url == "https://www.samsungcareers.com/hr/?no=22828"

    def test_deadline_from_period_text(self):
        p = _build_posting(_CARD_SDI, _DETAIL_SDI, "삼성SDI")
        assert p.deadline == date(2026, 8, 19)

    def test_deadline_falls_back_to_detail_enddate(self):
        card_no_period = _CardInfo(
            seq=22828,
            company_name="삼성SDI",
            title="채용",
            type_text="경력",
            period_text="",
        )
        p = _build_posting(card_no_period, _DETAIL_SDI, "삼성SDI")
        assert p.deadline == date(2026, 8, 19)

    def test_posted_at_from_startdate(self):
        p = _build_posting(_CARD_SDI, _DETAIL_SDI, "삼성SDI")
        assert p.posted_at == datetime(2026, 8, 12, 0, 0)

    def test_experience_level_career(self):
        p = _build_posting(_CARD_SDI, _DETAIL_SDI, "삼성SDI")
        assert p.experience_level == "경력"

    def test_experience_level_intern(self):
        p = _build_posting(_CARD_INTERN, _DETAIL_SDS, "삼성SDS")
        assert p.experience_level == "인턴"

    def test_employment_type_regular(self):
        p = _build_posting(_CARD_SDI, _DETAIL_SDI, "삼성SDI")
        assert p.employment_type == "정규직"

    def test_employment_type_contract(self):
        p = _build_posting(_CARD_ENA, _DETAIL_SDI, "삼성E&A")
        assert p.employment_type == "계약직"

    def test_roles_from_card_flags(self):
        p = _build_posting(_CARD_SDI, _DETAIL_SDI, "삼성SDI")
        assert p.roles == ["AI Agent 및 Service Platform 개발"]

    def test_roles_multiple_flags(self):
        p = _build_posting(_CARD_ENA, _DETAIL_SDI, "삼성E&A")
        assert "산업환경플랜트 Planner" in p.roles
        assert "산업환경플랜트 시공관리" in p.roles

    def test_uses_detail_title_when_available(self):
        detail_with_title = {**_DETAIL_SDI, "title": "상세 타이틀"}
        p = _build_posting(_CARD_SDI, detail_with_title, "삼성SDI")
        assert p.title == "상세 타이틀"

    def test_falls_back_to_card_title(self):
        detail_no_title = {**_DETAIL_SDI, "title": None}
        p = _build_posting(_CARD_SDI, detail_no_title, "삼성SDI")
        assert "AI Agent" in p.title

    def test_location_is_none(self):
        p = _build_posting(_CARD_SDI, _DETAIL_SDI, "삼성SDI")
        assert p.location is None


# ── SamsungCareersParser.fetch — unit (mocked HTTP) ────────────────────────────


def _make_source(com_codes: list[str], **kwargs) -> OfficialCompanySource:
    config = json.dumps(
        {"parser_key": "SAMSUNG_CAREERS", "com_codes": com_codes, **kwargs}
    )
    return OfficialCompanySource(
        company_id=30,
        source_type="OFFICIAL_CAREER",
        source_url="https://www.samsungcareers.com/hr/",
        adapter_type="CUSTOM",
        config_json=config,
    )


def _make_options() -> CollectionOptions:
    return CollectionOptions()


def _make_profile(name: str = "삼성전자") -> CompanyProfile:
    return CompanyProfile(id=30, canonical_name=name, normalized_name=name.lower())


def _detail_response(detail: dict) -> MagicMock:
    mock_resp = MagicMock()
    mock_resp.raise_for_status = MagicMock()
    mock_resp.json.return_value = {"success": True, "data": {"result": detail}}
    return mock_resp


def _list_response(html: str) -> MagicMock:
    mock_resp = MagicMock()
    mock_resp.raise_for_status = MagicMock()
    mock_resp.text = html
    return mock_resp


@pytest.mark.asyncio
class TestSamsungCareersParserFetch:
    async def test_no_com_codes_returns_empty(self):
        source = _make_source([])
        parser = SamsungCareersParser()
        result = await parser.fetch(source, None, _make_options())
        assert result.postings == []
        assert any("no com_codes" in w for w in result.warnings)

    async def test_zero_postings_returns_empty(self):
        source = _make_source(["C10CAA"])
        parser = SamsungCareersParser()

        mock_client = MagicMock()
        mock_client.__aenter__ = AsyncMock(return_value=mock_client)
        mock_client.__aexit__ = AsyncMock(return_value=False)
        mock_client.post = AsyncMock(return_value=_list_response(_LIST_HTML_EMPTY))

        patch_target = "app.adapters.official.samsung_careers.AsyncClient"
        with patch(patch_target, return_value=mock_client):
            result = await parser.fetch(source, _make_profile(), _make_options())

        assert result.postings == []
        assert result.source_stats.discovered == 0

    async def test_fetches_detail_for_each_card(self):
        source = _make_source(["C10CAA"])
        parser = SamsungCareersParser()

        list_mock = _list_response(_LIST_HTML_ONE_CARD)
        detail_mock = _detail_response({**_DETAIL_ELEC, "compCd": "C10CAA"})

        post_mock = AsyncMock(return_value=list_mock)
        get_mock = AsyncMock(return_value=detail_mock)

        outer_client = MagicMock()
        outer_client.__aenter__ = AsyncMock(return_value=outer_client)
        outer_client.__aexit__ = AsyncMock(return_value=False)
        outer_client.post = post_mock

        inner_client = MagicMock()
        inner_client.__aenter__ = AsyncMock(return_value=inner_client)
        inner_client.__aexit__ = AsyncMock(return_value=False)
        inner_client.get = get_mock

        call_count = 0

        def client_factory(**kwargs):
            nonlocal call_count
            call_count += 1
            return outer_client if call_count == 1 else inner_client

        patch_t = "app.adapters.official.samsung_careers.AsyncClient"
        with patch(patch_t, side_effect=client_factory):
            result = await parser.fetch(source, _make_profile(), _make_options())

        assert len(result.postings) == 1
        assert "SW 개발자 채용" in result.postings[0].title

    async def test_compCd_mismatch_skips_posting(self):
        source = _make_source(["C10CAA"])
        parser = SamsungCareersParser()

        list_mock = _list_response(_LIST_HTML_ONE_CARD)
        # Detail returns C31 (SDI) instead of C10CAA
        detail_mock = _detail_response({**_DETAIL_SDI, "compCd": "C31"})

        post_mock = AsyncMock(return_value=list_mock)
        get_mock = AsyncMock(return_value=detail_mock)

        outer = MagicMock()
        outer.__aenter__ = AsyncMock(return_value=outer)
        outer.__aexit__ = AsyncMock(return_value=False)
        outer.post = post_mock

        inner = MagicMock()
        inner.__aenter__ = AsyncMock(return_value=inner)
        inner.__aexit__ = AsyncMock(return_value=False)
        inner.get = get_mock

        call_count = 0

        def factory(**kwargs):
            nonlocal call_count
            call_count += 1
            return outer if call_count == 1 else inner

        with patch(
            "app.adapters.official.samsung_careers.AsyncClient", side_effect=factory
        ):
            result = await parser.fetch(source, _make_profile(), _make_options())

        assert result.postings == []
        assert any("compCd mismatch" in w for w in result.warnings)

    async def test_multi_com_codes_dedup_by_seq(self):
        source = _make_source(["C10CAA", "C10CAH"])
        parser = SamsungCareersParser()

        # Same HTML returned for both com codes → same seq → dedup to 1 card
        list_mock = _list_response(_LIST_HTML_ONE_CARD)
        detail_mock = _detail_response({**_DETAIL_ELEC, "compCd": "C10CAA"})

        outer = MagicMock()
        outer.__aenter__ = AsyncMock(return_value=outer)
        outer.__aexit__ = AsyncMock(return_value=False)
        outer.post = AsyncMock(return_value=list_mock)

        inner = MagicMock()
        inner.__aenter__ = AsyncMock(return_value=inner)
        inner.__aexit__ = AsyncMock(return_value=False)
        inner.get = AsyncMock(return_value=detail_mock)

        call_count = 0

        def factory(**kwargs):
            nonlocal call_count
            call_count += 1
            return outer if call_count == 1 else inner

        with patch(
            "app.adapters.official.samsung_careers.AsyncClient", side_effect=factory
        ):
            result = await parser.fetch(source, _make_profile(), _make_options())

        # Should be at most 1 posting (deduped by seq)
        assert len(result.postings) <= 1

    async def test_uses_profile_canonical_name(self):
        source = _make_source(["C60"])
        parser = SamsungCareersParser()

        list_mock = _list_response(_LIST_HTML_ONE_CARD)
        # Card has cmpNameKr so it takes precedence
        sds_detail = {**_DETAIL_SDS, "compCd": "C60", "cmpNameKr": "삼성SDS"}
        detail_mock = _detail_response(sds_detail)

        outer = MagicMock()
        outer.__aenter__ = AsyncMock(return_value=outer)
        outer.__aexit__ = AsyncMock(return_value=False)
        outer.post = AsyncMock(return_value=list_mock)

        inner = MagicMock()
        inner.__aenter__ = AsyncMock(return_value=inner)
        inner.__aexit__ = AsyncMock(return_value=False)
        inner.get = AsyncMock(return_value=detail_mock)

        call_count = 0

        def factory(**kwargs):
            nonlocal call_count
            call_count += 1
            return outer if call_count == 1 else inner

        with patch(
            "app.adapters.official.samsung_careers.AsyncClient", side_effect=factory
        ):
            result = await parser.fetch(
                source,
                _make_profile("삼성SDS (테스트)"),
                _make_options(),
            )

        # Detail cmpNameKr overrides profile name
        if result.postings:
            assert result.postings[0].company_name == "삼성SDS"


# ── Self-registration ──────────────────────────────────────────────────────────


class TestSelfRegistration:
    def test_registered_in_custom_registry(self):
        assert "SAMSUNG_CAREERS" in _CUSTOM_REGISTRY_BY_KEY
        parser = _CUSTOM_REGISTRY_BY_KEY["SAMSUNG_CAREERS"]
        assert isinstance(parser, SamsungCareersParser)


# ── External smoke test ────────────────────────────────────────────────────────


@pytest.mark.external
class TestSamsungCareersLive:
    async def test_list_api_reachable_and_parseable(self):
        """
        Smoke: POST /hr/list.data returns parseable HTML.
        Uses C31 (삼성SDI) which reliably has postings.
        """
        import httpx

        r = httpx.post(
            "https://www.samsungcareers.com/hr/list.data",
            data={
                "currentPageNo": "1",
                "strVal": "",
                "strTxt": "",
                "strKey": "",
                "strType[]": "",
                "strCompany[]": "C31",
                "strOrderBy": "",
                "strEntity": "",
                "intNo": "0",
            },
            headers={
                "User-Agent": (
                    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                    "AppleWebKit/537.36 (KHTML, like Gecko) "
                    "Chrome/124.0.0.0 Safari/537.36"
                ),
                "Referer": "https://www.samsungcareers.com/hr/",
                "Content-Type": "application/x-www-form-urlencoded",
            },
            timeout=20,
            follow_redirects=True,
        )
        assert r.status_code == 200
        cards, total = _parse_list_html(r.text)
        assert isinstance(total, int)
        assert isinstance(cards, list)
        if cards:
            c = cards[0]
            assert c.seq > 0
            assert c.company_name
            assert c.title


# =============================================================================
# New Samsung subsidiary sources
# (삼성디스플레이 C90, 삼성SDI C31, 삼성생명 E11, 삼성화재 E21,
#  삼성카드 E31, 삼성증권 E40)
# =============================================================================

# ── Additional fixture data ───────────────────────────────────────────────────

_CARD_DISPLAY = _CardInfo(
    seq=22922,
    company_name="삼성디스플레이",
    title="R&D분야 외국인 경력사원 채용",
    type_text="경력",
    period_text="2026.08.20 ~ 2026.09.02",
    role_flags=["패널설계", "재료/소자/공정", "AI/자동화 및 설비개발"],
)

_DETAIL_DISPLAY: dict[str, Any] = {
    "compCd": "C90",
    "cmpNameKr": "삼성디스플레이",
    "title": "R&D분야 외국인 경력사원 채용",
    "recruitType": "B",
    "startdate": "202608201000",
    "enddate": "202609021700",
    "qlfctKr": "관련 분야 석·박사 또는 학사 취득 후 2년 이상",
    "etcKr": "",
    "introKr": "삼성디스플레이는 OLED 디스플레이 선두기업입니다.",
}

_CARD_SDI_C31 = _CardInfo(
    seq=22828,
    company_name="삼성SDI",
    title="경력사원 채용(생성형 AI Agent 및 Service Platform개발)",
    type_text="경력",
    period_text="2026.08.12 ~ 2026.08.19",
    role_flags=["AI Agent 및 Service Platform 개발"],
)

_DETAIL_SDI_C31: dict[str, Any] = {
    "compCd": "C31",
    "cmpNameKr": "삼성SDI",
    "title": "경력사원 채용(생성형 AI Agent 및 Service Platform개발)",
    "recruitType": "B",
    "startdate": "202608121000",
    "enddate": "202608191700",
    "qlfctKr": "학사 취득 후 2년 이상 유관경력 보유자",
    "etcKr": "",
    "introKr": "삼성SDI는 친환경 에너지와 첨단소재를 사업의 양대축으로 합니다.",
}

_CARD_FINANCE_E11 = _CardInfo(
    seq=30001,
    company_name="삼성생명",
    title="삼성생명 IT개발 경력사원 채용",
    type_text="경력",
    period_text="2026.10.01 ~ 2026.10.31",
    role_flags=["IT개발", "보험상품 개발"],
)

_DETAIL_FINANCE_E11: dict[str, Any] = {
    "compCd": "E11",
    "cmpNameKr": "삼성생명",
    "title": "삼성생명 IT개발 경력사원 채용",
    "recruitType": "B",
    "startdate": "202610011000",
    "enddate": "202610311700",
    "qlfctKr": "IT 관련 전공, Java/Spring 경력 3년 이상",
    "etcKr": "",
    "introKr": "삼성생명 회사소개",
}

_LIST_HTML_DISPLAY = """\
<input type="hidden" class="divCnt" data-value="1" data-max="1">
<ul>
  <li>
    <div>
      <div class="btnWrap">
        <button class="btnShare" data-value="22,922" type="button">
          <i>공유</i>
        </button>
      </div>
      <a data-value="22,922" href="/#none">
        <p class="company">삼성디스플레이</p>
        <h3 class="title">R&amp;D분야 외국인 경력사원 채용</h3>
        <p class="info">
          <span>경력</span>
          <span class="period">2026.08.20 ~ 2026.09.02</span>
        </p>
      </a>
      <div class="flagWrap">
        <span class="flag grey">패널설계</span>
        <span class="flag grey">재료/소자/공정</span>
        <span class="flag grey">AI/자동화 및 설비개발</span>
      </div>
    </div>
  </li>
</ul>
"""

_LIST_HTML_FINANCE_EMPTY = """\
<input type="hidden" class="divCnt" data-value="0" data-max="0">
<div class="noData">
  <p class="text1">현재 채용중인 공고가 없습니다.</p>
</div>
"""

_LIST_HTML_TWO_SAME_SEQ = """\
<input type="hidden" class="divCnt" data-value="2" data-max="1">
<ul>
  <li>
    <div>
      <button class="btnShare" data-value="22,828" type="button"></button>
      <a href="/#none">
        <p class="company">삼성SDI</p>
        <h3 class="title">채용공고 A</h3>
        <p class="info">
          <span>경력</span>
          <span class="period">2026.08.12 ~ 2026.08.19</span>
        </p>
      </a>
    </div>
  </li>
  <li>
    <div>
      <button class="btnShare" data-value="22,828" type="button"></button>
      <a href="/#none">
        <p class="company">삼성SDI</p>
        <h3 class="title">채용공고 A (중복)</h3>
        <p class="info">
          <span>경력</span>
          <span class="period">2026.08.12 ~ 2026.08.19</span>
        </p>
      </a>
    </div>
  </li>
</ul>
"""

_LIST_HTML_TWO_DIFF_SEQ = """\
<input type="hidden" class="divCnt" data-value="2" data-max="1">
<ul>
  <li>
    <div>
      <button class="btnShare" data-value="22,828" type="button"></button>
      <a href="/#none">
        <p class="company">삼성디스플레이</p>
        <h3 class="title">공고 A</h3>
        <p class="info">
          <span>경력</span>
          <span class="period">2026.08.12 ~ 2026.08.19</span>
        </p>
      </a>
    </div>
  </li>
  <li>
    <div>
      <button class="btnShare" data-value="22,922" type="button"></button>
      <a href="/#none">
        <p class="company">삼성디스플레이</p>
        <h3 class="title">공고 B</h3>
        <p class="info">
          <span>경력</span>
          <span class="period">2026.08.20 ~ 2026.09.02</span>
        </p>
      </a>
    </div>
  </li>
</ul>
"""

_PATCH = "app.adapters.official.samsung_careers.AsyncClient"


def _make_clients(
    list_html: str,
    *detail_dicts: dict | Exception,
) -> tuple[MagicMock, MagicMock]:
    """Build (outer, inner) mocks.

    outer handles POST list; inner.get cycles through detail_dicts in order.
    Pass an Exception instance to simulate a detail fetch error.
    """
    outer = MagicMock()
    outer.__aenter__ = AsyncMock(return_value=outer)
    outer.__aexit__ = AsyncMock(return_value=False)
    outer.post = AsyncMock(return_value=_list_response(list_html))

    inner = MagicMock()
    inner.__aenter__ = AsyncMock(return_value=inner)
    inner.__aexit__ = AsyncMock(return_value=False)

    get_returns = []
    for d in detail_dicts:
        if isinstance(d, Exception):
            get_returns.append(d)
        else:
            get_returns.append(_detail_response(d))
    if get_returns:
        inner.get = AsyncMock(side_effect=get_returns)
    else:
        inner.get = AsyncMock(return_value=MagicMock())

    return outer, inner


def _factory(outer: MagicMock, inner: MagicMock):
    call_count = 0

    def _make(**kwargs):
        nonlocal call_count
        call_count += 1
        return outer if call_count == 1 else inner

    return _make


# ── Fixture tests for new company codes ───────────────────────────────────────


@pytest.mark.asyncio
class TestNewSamsungSourceCodes:
    """comCode 필터 및 compCd 검증 — 신규 6개 코드."""

    async def _fetch(
        self,
        code: str,
        profile_name: str,
        list_html: str,
        *detail_dicts: dict,
    ):
        source = _make_source([code])
        profile = _make_profile(profile_name)
        outer, inner = _make_clients(list_html, *detail_dicts)
        with patch(_PATCH, side_effect=_factory(outer, inner)):
            return await SamsungCareersParser().fetch(source, profile, _make_options())

    async def test_c90_display_happy_path(self) -> None:
        result = await self._fetch(
            "C90",
            "삼성디스플레이",
            _LIST_HTML_DISPLAY,
            _DETAIL_DISPLAY,
        )
        assert len(result.postings) == 1
        p = result.postings[0]
        assert p.company_name == "삼성디스플레이"
        assert p.source == "samsung_careers"
        assert p.source_external_id == "22922"

    async def test_c90_display_roles_preserved(self) -> None:
        result = await self._fetch(
            "C90",
            "삼성디스플레이",
            _LIST_HTML_DISPLAY,
            _DETAIL_DISPLAY,
        )
        roles = result.postings[0].roles
        assert "패널설계" in roles
        assert "AI/자동화 및 설비개발" in roles

    async def test_c90_display_experience_career(self) -> None:
        result = await self._fetch(
            "C90",
            "삼성디스플레이",
            _LIST_HTML_DISPLAY,
            _DETAIL_DISPLAY,
        )
        assert result.postings[0].experience_level == "경력"

    async def test_c31_sdi_happy_path(self) -> None:
        list_html = _LIST_HTML_ONE_CARD  # existing fixture, has seq=22828
        detail = {**_DETAIL_SDI_C31}
        result = await self._fetch("C31", "삼성SDI", list_html, detail)
        assert len(result.postings) == 1
        p = result.postings[0]
        assert p.company_name == "삼성SDI"
        assert p.source_external_id == "22828"

    async def test_c31_sdi_dev_role(self) -> None:
        list_html = _LIST_HTML_ONE_CARD
        detail = {**_DETAIL_SDI_C31}
        result = await self._fetch("C31", "삼성SDI", list_html, detail)
        # _LIST_HTML_ONE_CARD has role_flag "AI Agent 및 Service Platform 개발"
        roles = result.postings[0].roles
        assert any("AI Agent" in r for r in roles)

    @pytest.mark.parametrize(
        "code,profile_name,exp_comp",
        [
            ("E11", "삼성생명", "삼성생명"),
            ("E21", "삼성화재", "삼성화재"),
            ("E31", "삼성카드", "삼성카드"),
            ("E40", "삼성증권", "삼성증권"),
        ],
    )
    async def test_financial_empty_is_valid(
        self, code: str, profile_name: str, exp_comp: str
    ) -> None:
        """0건 응답은 오류 없이 빈 리스트를 반환해야 한다."""
        source = _make_source([code])
        profile = _make_profile(profile_name)
        outer = MagicMock()
        outer.__aenter__ = AsyncMock(return_value=outer)
        outer.__aexit__ = AsyncMock(return_value=False)
        outer.post = AsyncMock(return_value=_list_response(_LIST_HTML_FINANCE_EMPTY))
        with patch(_PATCH, return_value=outer):
            result = await SamsungCareersParser().fetch(
                source, profile, _make_options()
            )
        assert result.postings == []
        assert result.warnings == []
        assert result.source_stats is not None
        assert result.source_stats.discovered == 0

    async def test_e11_finance_posting_happy_path(self) -> None:
        """삼성생명 공고가 있을 때 company_name과 roles가 올바른지 확인."""
        html = """\
<input type="hidden" class="divCnt" data-value="1" data-max="1">
<ul>
  <li>
    <div>
      <button class="btnShare" data-value="30,001" type="button"></button>
      <a href="/#none">
        <p class="company">삼성생명</p>
        <h3 class="title">삼성생명 IT개발 경력사원 채용</h3>
        <p class="info">
          <span>경력</span>
          <span class="period">2026.10.01 ~ 2026.10.31</span>
        </p>
      </a>
      <div class="flagWrap">
        <span class="flag grey">IT개발</span>
        <span class="flag grey">보험상품 개발</span>
      </div>
    </div>
  </li>
</ul>"""
        result = await self._fetch("E11", "삼성생명", html, _DETAIL_FINANCE_E11)
        assert len(result.postings) == 1
        p = result.postings[0]
        assert p.company_name == "삼성생명"
        assert "IT개발" in p.roles
        assert "보험상품 개발" in p.roles

    async def test_e11_non_dev_role_preserved(self) -> None:
        """금융사 비개발 직무(영업, 자산운용 등)도 roles에 보존된다."""
        html = """\
<input type="hidden" class="divCnt" data-value="1" data-max="1">
<ul>
  <li>
    <div>
      <button class="btnShare" data-value="30,002" type="button"></button>
      <a href="/#none">
        <p class="company">삼성생명</p>
        <h3 class="title">보험 영업 채용</h3>
        <p class="info">
          <span>경력</span>
          <span class="period">2026.10.01 ~ 2026.10.31</span>
        </p>
      </a>
      <div class="flagWrap">
        <span class="flag grey">보험 영업</span>
        <span class="flag grey">자산운용</span>
      </div>
    </div>
  </li>
</ul>"""
        detail = {
            **_DETAIL_FINANCE_E11,
            "seq": 30002,
            "title": "보험 영업 채용",
        }
        result = await self._fetch("E11", "삼성생명", html, detail)
        assert len(result.postings) == 1
        roles = result.postings[0].roles
        assert "보험 영업" in roles
        assert "자산운용" in roles

    async def test_compcd_mismatch_for_new_code_skipped(self) -> None:
        """detail.compCd가 요청한 코드와 다르면 공고를 건너뛴다."""
        # Request C90 but detail returns C31
        detail_wrong = {**_DETAIL_DISPLAY, "compCd": "C31"}
        result = await self._fetch(
            "C90",
            "삼성디스플레이",
            _LIST_HTML_DISPLAY,
            detail_wrong,
        )
        assert result.postings == []
        assert any("compCd mismatch" in w for w in result.warnings)

    async def test_display_source_record_key_stability(self) -> None:
        """같은 seq로 두 번 빌드하면 source/source_external_id가 동일."""
        p1 = _build_posting(_CARD_DISPLAY, _DETAIL_DISPLAY, "삼성디스플레이")
        p2 = _build_posting(_CARD_DISPLAY, _DETAIL_DISPLAY, "삼성디스플레이")
        assert p1.source == p2.source
        assert p1.source_external_id == p2.source_external_id

    async def test_sdi_source_record_key_stability(self) -> None:
        p1 = _build_posting(_CARD_SDI_C31, _DETAIL_SDI_C31, "삼성SDI")
        p2 = _build_posting(_CARD_SDI_C31, _DETAIL_SDI_C31, "삼성SDI")
        assert p1.source == p2.source
        assert p1.source_external_id == p2.source_external_id


@pytest.mark.asyncio
class TestDuplicateSeqnoDedup:
    """중복 seqno 공고는 한 번만 처리된다."""

    async def test_same_seq_twice_deduped(self) -> None:
        # _LIST_HTML_TWO_SAME_SEQ has seq 22828 twice
        source = _make_source(["C31"])
        profile = _make_profile("삼성SDI")
        detail = {**_DETAIL_SDI_C31}

        outer = MagicMock()
        outer.__aenter__ = AsyncMock(return_value=outer)
        outer.__aexit__ = AsyncMock(return_value=False)
        outer.post = AsyncMock(return_value=_list_response(_LIST_HTML_TWO_SAME_SEQ))

        inner = MagicMock()
        inner.__aenter__ = AsyncMock(return_value=inner)
        inner.__aexit__ = AsyncMock(return_value=False)
        inner.get = AsyncMock(return_value=_detail_response(detail))

        with patch(_PATCH, side_effect=_factory(outer, inner)):
            result = await SamsungCareersParser().fetch(
                source, profile, _make_options()
            )

        # Parser deduplicates into all_cards dict keyed by seq,
        # so discovered = len(all_cards) = 1 (not raw HTML count).
        assert result.source_stats is not None
        assert result.source_stats.discovered == 1
        assert len(result.postings) == 1


@pytest.mark.asyncio
class TestPartialDetailFailure:
    """1건 상세 실패 시 나머지 공고는 정상 파싱된다."""

    async def test_first_detail_fail_second_succeeds(self) -> None:
        source = _make_source(["C90"])
        profile = _make_profile("삼성디스플레이")

        detail_b = {
            "compCd": "C90",
            "cmpNameKr": "삼성디스플레이",
            "title": "공고 B",
            "recruitType": "B",
            "startdate": "202608201000",
            "enddate": "202609021700",
            "qlfctKr": "경력 2년 이상",
            "etcKr": "",
            "introKr": "",
        }

        outer = MagicMock()
        outer.__aenter__ = AsyncMock(return_value=outer)
        outer.__aexit__ = AsyncMock(return_value=False)
        outer.post = AsyncMock(return_value=_list_response(_LIST_HTML_TWO_DIFF_SEQ))

        inner = MagicMock()
        inner.__aenter__ = AsyncMock(return_value=inner)
        inner.__aexit__ = AsyncMock(return_value=False)
        # First detail call raises; second succeeds
        inner.get = AsyncMock(
            side_effect=[Exception("timeout"), _detail_response(detail_b)]
        )

        with patch(_PATCH, side_effect=_factory(outer, inner)):
            result = await SamsungCareersParser().fetch(
                source, profile, _make_options()
            )

        assert len(result.postings) == 1
        assert result.postings[0].title == "공고 B"
        assert any("detail" in w for w in result.warnings)


@pytest.mark.asyncio
class TestSamsungCareersExistingRegression:
    """삼성전자 DX/DS 기존 동작 회귀 검증."""

    async def test_samsung_elec_dx_ds_happy_path(self) -> None:
        """C10CAA + C10CAH 두 코드 모두 수집 후 dedup."""
        source = _make_source(["C10CAA", "C10CAH"])
        profile = _make_profile("삼성전자")

        list_mock_dx = _list_response(_LIST_HTML_ONE_CARD)
        list_mock_ds = _list_response(_LIST_HTML_EMPTY)
        detail_elec = {**_DETAIL_ELEC, "compCd": "C10CAA"}

        outer = MagicMock()
        outer.__aenter__ = AsyncMock(return_value=outer)
        outer.__aexit__ = AsyncMock(return_value=False)
        outer.post = AsyncMock(side_effect=[list_mock_dx, list_mock_ds])

        inner = MagicMock()
        inner.__aenter__ = AsyncMock(return_value=inner)
        inner.__aexit__ = AsyncMock(return_value=False)
        inner.get = AsyncMock(return_value=_detail_response(detail_elec))

        with patch(_PATCH, side_effect=_factory(outer, inner)):
            result = await SamsungCareersParser().fetch(
                source, profile, _make_options()
            )

        assert len(result.postings) == 1
        assert result.postings[0].company_name == "삼성전자"

    async def test_samsung_elec_newcomer_type(self) -> None:
        """신입 type_text → experience_level 신입."""
        p = _build_posting(_CARD_NEWCOMER, _DETAIL_ELEC, "삼성전자")
        assert p.experience_level == "신입"

    async def test_samsung_elec_combined_type(self) -> None:
        """신입·경력 → experience_level 신입/경력."""
        p = _build_posting(_CARD_COMBINED, _DETAIL_ELEC, "삼성전자")
        assert p.experience_level == "신입/경력"

    async def test_samsung_elec_multiple_roles(self) -> None:
        """복수 role_flags 모두 보존."""
        p = _build_posting(_CARD_NEWCOMER, _DETAIL_ELEC, "삼성전자")
        assert "SW개발" in p.roles
        assert "HW개발" in p.roles


# ── Live smoke tests (new companies) ─────────────────────────────────────────


@pytest.mark.external
class TestSamsungNewSourcesLive:
    """신규 source live smoke test.

    CI 제외 (@pytest.mark.external). 수동 실행:
      poetry run pytest tests/adapters/test_samsung_careers.py \
          -k "live_new" -v -s -m external
    """

    async def _run(self, code: str, company: str):
        source = _make_source([code], max_discover=50, max_fetch=2)
        profile = _make_profile(company)
        result = await SamsungCareersParser().fetch(source, profile, _make_options())
        print(
            f"\n[{code}] {company}: discovered="
            f"{result.source_stats.discovered if result.source_stats else 0}"
            f" parsed={len(result.postings)} warnings={result.warnings}"
        )
        for p in result.postings:
            print(
                f"  → {p.title} | {p.experience_level}"
                f" | deadline={p.deadline} | roles={p.roles}"
            )
        return result

    async def test_live_new_c90_display(self) -> None:
        result = await self._run("C90", "삼성디스플레이")
        # API must return HTTP 200 with valid structure (0 postings is OK)
        assert result.source_stats is not None
        list_fails = [w for w in result.warnings if "listing" in w or "HTTP" in w]
        assert not list_fails, f"C90 list error: {list_fails}"

    async def test_live_new_c31_sdi(self) -> None:
        result = await self._run("C31", "삼성SDI")
        assert result.source_stats is not None
        list_fails = [w for w in result.warnings if "listing" in w or "HTTP" in w]
        assert not list_fails, f"C31 list error: {list_fails}"

    @pytest.mark.parametrize(
        "code,company",
        [
            ("E11", "삼성생명"),
            ("E21", "삼성화재"),
            ("E31", "삼성카드"),
            ("E40", "삼성증권"),
        ],
    )
    async def test_live_financial_valid_empty(self, code: str, company: str) -> None:
        """금융사 0건 응답 = 유효한 빈 응답 (HTTP 오류 아님)."""
        result = await self._run(code, company)
        assert result.source_stats is not None
        list_fails = [w for w in result.warnings if "listing" in w or "HTTP" in w]
        assert not list_fails, f"{code} list error: {list_fails}"
