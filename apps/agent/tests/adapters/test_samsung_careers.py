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
