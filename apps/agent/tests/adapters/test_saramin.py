"""Tests for SaraminAdapter.

All tests are offline: no real API calls.
- Pure-function tests (_build_queries, _parse_job, _relevance_score, _select_postings)
  use inline fixtures.
- Adapter-level tests mock httpx.AsyncClient via monkeypatch.
"""

import json
from datetime import date, datetime

import httpx
from httpx import TimeoutException

from app.adapters.aggregators.saramin import (
    SaraminAdapter,
    _build_queries,
    _parse_job,
    _relevance_score,
    _select_postings,
)
from app.adapters.base import AdapterResult, RawJobPosting
from app.schemas.collection import CollectionOptions, SeedKeywords

_COLLECT_DATE = date(2026, 7, 6)
_BASE_URL = "https://oapi.saramin.co.kr"
_JOB_SEARCH_URL = f"{_BASE_URL}/job-search"


# ── helpers ───────────────────────────────────────────────────────────────────


def _seed(**kwargs) -> SeedKeywords:
    return SeedKeywords(**kwargs)


def _options(**kwargs) -> CollectionOptions:
    return CollectionOptions(**kwargs)


def _sample_job(
    job_id: str = "1001",
    url: str = "https://www.saramin.co.kr/job/1001",
    company: str = "카카오",
    title: str = "백엔드 개발자",
    job_type: str = "정규직",
    location: str = "서울",
    job_category: str = "개발·IT",
    exp_level: str = "경력무관",
    keyword: str = "Python,Django",
    expiration_date: str = "2026-08-01T00:00:00+09:00",
    posting_date: str = "2026-07-01T00:00:00+09:00",
) -> dict:
    return {
        "id": job_id,
        "url": url,
        "active": 1,
        "company": {"detail": {"href": "...", "name": company}},
        "position": {
            "title": title,
            "job-type": {"code": "1", "name": job_type},
            "location": {"code": "101000", "name": location},
            "job-category": {"code": "2", "name": job_category},
            "experience-level": {"code": "0", "min": 0, "max": 0, "name": exp_level},
        },
        "keyword": keyword,
        "expiration-date": expiration_date,
        "posting-date": posting_date,
    }


def _jobs_response(jobs: list[dict], total: int | None = None) -> dict:
    return {
        "jobs": {
            "total": str(total if total is not None else len(jobs)),
            "count": len(jobs),
            "start": 0,
            "job": jobs,
        }
    }


class _MockSaraminClient:
    """Sequential response mock for SaraminAdapter."""

    def __init__(self, responses: list):
        self._responses = list(responses)
        self._index = 0
        self.calls: list[tuple[str, dict]] = []

    async def __aenter__(self):
        return self

    async def __aexit__(self, *args):
        pass

    async def get(self, url: str, **kwargs) -> httpx.Response:
        params = kwargs.get("params", {})
        self.calls.append((url, dict(params or {})))
        request = httpx.Request("GET", url)
        if self._index >= len(self._responses):
            return httpx.Response(404, text="Not Found", request=request)
        response = self._responses[self._index]
        self._index += 1
        if isinstance(response, BaseException):
            raise response
        status, body = response
        if isinstance(body, dict):
            return httpx.Response(
                status, text=json.dumps(body), request=request
            )
        return httpx.Response(status, text=body, request=request)


# ── _build_queries ─────────────────────────────────────────────────────────────


def test_build_queries_roles_appear_first():
    seed = _seed(roles=["백엔드 개발자", "프론트엔드 개발자"], companies=["카카오"])
    queries = _build_queries(seed, max_queries=10)
    keywords = [q.keywords for q in queries]
    assert keywords.index("백엔드 개발자") < keywords.index("카카오")
    assert keywords.index("프론트엔드 개발자") < keywords.index("카카오")


def test_build_queries_companies_before_skill_combos():
    seed = _seed(roles=["백엔드 개발자"], companies=["네이버"], skills=["Python"])
    queries = _build_queries(seed, max_queries=10)
    keywords = [q.keywords for q in queries]
    assert keywords.index("네이버") < keywords.index("백엔드 개발자 Python")


def test_build_queries_max_cap_respected():
    seed = _seed(
        roles=["A", "B", "C", "D", "E"],
        companies=["X", "Y", "Z"],
        skills=["s1", "s2"],
    )
    queries = _build_queries(seed, max_queries=4)
    assert len(queries) == 4


def test_build_queries_dedup_equivalent_token_sets():
    seed = _seed(roles=["Python 개발자", "개발자 Python"])
    queries = _build_queries(seed, max_queries=10)
    assert len(queries) == 1


def test_build_queries_role_skill_combinations_generated():
    seed = _seed(roles=["백엔드 개발자"], skills=["Python", "Java"])
    queries = _build_queries(seed, max_queries=10)
    keywords = [q.keywords for q in queries]
    assert "백엔드 개발자 Python" in keywords
    assert "백엔드 개발자 Java" in keywords


def test_build_queries_empty_seed_returns_empty():
    queries = _build_queries(_seed(), max_queries=10)
    assert queries == []


def test_build_queries_zero_max_returns_empty():
    seed = _seed(roles=["백엔드 개발자"])
    queries = _build_queries(seed, max_queries=0)
    assert queries == []


def test_build_queries_default_sort_is_pd():
    seed = _seed(roles=["개발자"])
    queries = _build_queries(seed, max_queries=10)
    assert all(q.sort == "pd" for q in queries)


# ── _parse_job ─────────────────────────────────────────────────────────────────


def test_parse_job_maps_all_fields():
    job = _sample_job()
    result = _parse_job(job)
    assert result is not None
    assert result.source == "saramin"
    assert result.source_external_id == "1001"
    assert result.source_url == "https://www.saramin.co.kr/job/1001"
    assert result.company_name == "카카오"
    assert result.title == "백엔드 개발자"
    assert result.employment_type == "정규직"
    assert result.location == "서울"
    assert result.experience_level == "경력무관"
    assert result.roles == ["개발·IT"]
    assert result.skills == ["Python", "Django"]
    assert result.deadline == date(2026, 8, 1)
    assert isinstance(result.posted_at, datetime)


def test_parse_job_missing_url_returns_none():
    job = _sample_job()
    job["url"] = ""
    assert _parse_job(job) is None


def test_parse_job_missing_company_returns_none():
    job = _sample_job()
    job["company"] = {"detail": {"name": ""}}
    assert _parse_job(job) is None


def test_parse_job_missing_title_returns_none():
    job = _sample_job()
    job["position"]["title"] = ""
    assert _parse_job(job) is None


def test_parse_job_empty_keyword_gives_empty_skills():
    job = _sample_job(keyword="")
    result = _parse_job(job)
    assert result is not None
    assert result.skills == []


def test_parse_job_missing_expiration_date_gives_none_deadline():
    job = _sample_job()
    job.pop("expiration-date", None)
    result = _parse_job(job)
    assert result is not None
    assert result.deadline is None


def test_parse_job_missing_posting_date_gives_none_posted_at():
    job = _sample_job()
    job.pop("posting-date", None)
    result = _parse_job(job)
    assert result is not None
    assert result.posted_at is None


def test_parse_job_no_job_category_gives_empty_roles():
    job = _sample_job()
    job["position"]["job-category"] = {"code": "", "name": ""}
    result = _parse_job(job)
    assert result is not None
    assert result.roles == []


def test_parse_job_none_position_returns_none():
    job = _sample_job()
    job["position"] = None
    assert _parse_job(job) is None


# ── _relevance_score ──────────────────────────────────────────────────────────


def test_relevance_score_company_match():
    posting = RawJobPosting(
        source="saramin",
        source_url="https://saramin.co.kr/1",
        company_name="카카오",
        title="소프트웨어 엔지니어",
    )
    score = _relevance_score(posting, _seed(companies=["카카오"]))
    assert score == 3.0


def test_relevance_score_role_in_title():
    posting = RawJobPosting(
        source="saramin",
        source_url="https://saramin.co.kr/2",
        company_name="네이버",
        title="백엔드 개발자 채용",
    )
    score = _relevance_score(posting, _seed(roles=["백엔드 개발자"]))
    assert score == 2.0


def test_relevance_score_skill_match():
    posting = RawJobPosting(
        source="saramin",
        source_url="https://saramin.co.kr/3",
        company_name="라인",
        title="서버 개발",
        skills=["Python", "FastAPI"],
    )
    score = _relevance_score(posting, _seed(skills=["Python"]))
    assert score == 1.0


def test_relevance_score_no_match_is_zero():
    posting = RawJobPosting(
        source="saramin",
        source_url="https://saramin.co.kr/4",
        company_name="알 수 없는 회사",
        title="회계 담당자",
    )
    score = _relevance_score(
        posting, _seed(roles=["백엔드 개발자"], companies=["카카오"])
    )
    assert score == 0.0


# ── _select_postings ──────────────────────────────────────────────────────────


def _entry(
    company: str,
    title: str,
    last_date: date,
    skills: list[str] | None = None,
    url_suffix: str = "",
) -> tuple[date, RawJobPosting]:
    url = f"https://saramin.co.kr/{company}{url_suffix}"
    return (
        last_date,
        RawJobPosting(
            source="saramin",
            source_url=url,
            company_name=company,
            title=title,
            skills=skills or [],
        ),
    )


def test_select_postings_empty_entries():
    assert _select_postings([], _seed(), 10) == []


def test_select_postings_limit_zero():
    entries = [_entry("카카오", "백엔드 개발자", date(2026, 7, 1))]
    assert _select_postings(entries, _seed(), 0) == []


def test_select_postings_respects_limit():
    entries = [
        _entry("회사", f"직무 {i}", date(2026, 7, i), url_suffix=str(i))
        for i in range(1, 20)
    ]
    result = _select_postings(entries, _seed(), 5)
    assert len(result) <= 5


def test_select_postings_relevance_first():
    seed = _seed(companies=["카카오"])
    entries = [
        _entry("기타회사", "개발자", date(2026, 7, 5)),
        _entry("카카오", "개발자", date(2026, 7, 1)),
    ]
    result = _select_postings(entries, seed, 2)
    assert result[0].company_name == "카카오"


def test_select_postings_exploration_quota_includes_non_seed():
    seed = _seed(companies=["카카오"])
    entries = [
        _entry("카카오", "개발자 A", date(2026, 7, 1), url_suffix="a"),
        _entry("카카오", "개발자 B", date(2026, 7, 2), url_suffix="b"),
        _entry("기타", "최신 공고", date(2026, 7, 5)),
    ]
    result = _select_postings(entries, seed, 3)
    urls = {p.source_url for p in result}
    assert any("기타" in p.company_name for p in result), (
        "exploration quota must include non-seed entry"
    )
    assert len(urls) == len(result), "no duplicate URLs"


# ── SaraminAdapter.fetch — mocked network ─────────────────────────────────────


async def test_adapter_success_returns_postings(monkeypatch):
    jobs = [
        _sample_job(job_id=str(i), url=f"https://saramin.co.kr/job/{i}")
        for i in range(3)
    ]
    mock = _MockSaraminClient([(200, _jobs_response(jobs))])
    _target = "app.adapters.aggregators.saramin.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)
    monkeypatch.setattr(
        "app.adapters.aggregators.saramin.settings.saramin_access_key", "test-key"
    )
    monkeypatch.setattr(
        "app.adapters.aggregators.saramin.settings.saramin_api_base_url", _BASE_URL
    )
    seed = _seed(roles=["백엔드 개발자"])
    result = await SaraminAdapter().fetch(seed, _options(), _COLLECT_DATE)
    assert isinstance(result, AdapterResult)
    assert len(result.postings) == 3
    assert result.postings[0].source == "saramin"


async def test_adapter_empty_results_returns_empty(monkeypatch):
    mock = _MockSaraminClient([(200, _jobs_response([]))])
    _target = "app.adapters.aggregators.saramin.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)
    monkeypatch.setattr(
        "app.adapters.aggregators.saramin.settings.saramin_access_key", "test-key"
    )
    monkeypatch.setattr(
        "app.adapters.aggregators.saramin.settings.saramin_api_base_url", _BASE_URL
    )
    seed = _seed(roles=["백엔드 개발자"])
    result = await SaraminAdapter().fetch(seed, _options(), _COLLECT_DATE)
    assert result.postings == []
    assert result.warnings == []


async def test_adapter_no_access_key_returns_warning(monkeypatch):
    monkeypatch.setattr(
        "app.adapters.aggregators.saramin.settings.saramin_access_key", ""
    )
    seed = _seed(roles=["백엔드 개발자"])
    result = await SaraminAdapter().fetch(seed, _options(), _COLLECT_DATE)
    assert result.postings == []
    assert any("SARAMIN_ACCESS_KEY" in w for w in result.warnings)


async def test_adapter_no_queries_from_empty_seed(monkeypatch):
    monkeypatch.setattr(
        "app.adapters.aggregators.saramin.settings.saramin_access_key", "test-key"
    )
    result = await SaraminAdapter().fetch(_seed(), _options(), _COLLECT_DATE)
    assert result.postings == []
    assert result.warnings == []


async def test_adapter_auth_error_adds_warning(monkeypatch):
    mock = _MockSaraminClient([(401, "{}")])
    _target = "app.adapters.aggregators.saramin.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)
    monkeypatch.setattr(
        "app.adapters.aggregators.saramin.settings.saramin_access_key", "bad-key"
    )
    monkeypatch.setattr(
        "app.adapters.aggregators.saramin.settings.saramin_api_base_url", _BASE_URL
    )
    seed = _seed(roles=["백엔드 개발자"])
    result = await SaraminAdapter().fetch(seed, _options(), _COLLECT_DATE)
    assert result.postings == []
    assert any("401" in w for w in result.warnings)


async def test_adapter_timeout_adds_warning(monkeypatch):
    mock = _MockSaraminClient([TimeoutException("timed out")])
    _target = "app.adapters.aggregators.saramin.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)
    monkeypatch.setattr(
        "app.adapters.aggregators.saramin.settings.saramin_access_key", "test-key"
    )
    monkeypatch.setattr(
        "app.adapters.aggregators.saramin.settings.saramin_api_base_url", _BASE_URL
    )
    seed = _seed(roles=["백엔드 개발자"])
    result = await SaraminAdapter().fetch(seed, _options(), _COLLECT_DATE)
    assert result.postings == []
    assert any("timeout" in w for w in result.warnings)


async def test_adapter_malformed_response_adds_warning(monkeypatch):
    mock = _MockSaraminClient([(200, "not-valid-json{")])
    _target = "app.adapters.aggregators.saramin.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)
    monkeypatch.setattr(
        "app.adapters.aggregators.saramin.settings.saramin_access_key", "test-key"
    )
    monkeypatch.setattr(
        "app.adapters.aggregators.saramin.settings.saramin_api_base_url", _BASE_URL
    )
    seed = _seed(roles=["백엔드 개발자"])
    result = await SaraminAdapter().fetch(seed, _options(), _COLLECT_DATE)
    assert result.postings == []
    assert any("malformed" in w for w in result.warnings)


async def test_adapter_pagination_collects_multiple_pages(monkeypatch):
    """Adapter paginates when total > count per page."""
    page1_jobs = [
        _sample_job(job_id=str(i), url=f"https://saramin.co.kr/job/{i}")
        for i in range(3)
    ]
    page2_jobs = [
        _sample_job(
            job_id=str(i + 3), url=f"https://saramin.co.kr/job/{i + 3}"
        ) for i in range(2)
    ]
    page1 = {
        "jobs": {
            "total": "5",
            "count": 3,
            "start": 0,
            "job": page1_jobs,
        }
    }
    page2 = {
        "jobs": {
            "total": "5",
            "count": 2,
            "start": 3,
            "job": page2_jobs,
        }
    }
    mock = _MockSaraminClient([(200, page1), (200, page2)])
    _target = "app.adapters.aggregators.saramin.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)
    monkeypatch.setattr(
        "app.adapters.aggregators.saramin.settings.saramin_access_key", "test-key"
    )
    monkeypatch.setattr(
        "app.adapters.aggregators.saramin.settings.saramin_api_base_url", _BASE_URL
    )
    monkeypatch.setattr(
        "app.adapters.aggregators.saramin.settings.saramin_max_queries_per_collect", 10
    )
    seed = _seed(roles=["백엔드 개발자"])
    opts = _options(detail_fetch_limit_per_source=10, max_results_per_source=50)
    result = await SaraminAdapter().fetch(seed, opts, _COLLECT_DATE)
    assert len(result.postings) == 5


async def test_adapter_page_budget_limits_api_calls(monkeypatch):
    """detail_fetch_limit_per_source caps total API requests."""
    unlimited_jobs = {
        "jobs": {
            "total": "999",
            "count": 1,
            "start": 0,
            "job": [_sample_job()],
        }
    }
    # 3 responses available but budget is 1 per query
    mock = _MockSaraminClient(
        [(200, unlimited_jobs), (200, unlimited_jobs), (200, unlimited_jobs)]
    )
    _target = "app.adapters.aggregators.saramin.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)
    monkeypatch.setattr(
        "app.adapters.aggregators.saramin.settings.saramin_access_key", "test-key"
    )
    monkeypatch.setattr(
        "app.adapters.aggregators.saramin.settings.saramin_api_base_url", _BASE_URL
    )
    monkeypatch.setattr(
        "app.adapters.aggregators.saramin.settings.saramin_max_queries_per_collect", 10
    )
    seed = _seed(roles=["백엔드 개발자"])
    # 1 page budget total
    opts = _options(detail_fetch_limit_per_source=1, max_results_per_source=50)
    await SaraminAdapter().fetch(seed, opts, _COLLECT_DATE)
    # Only 1 API call should have been made despite total=999
    assert mock._index == 1


async def test_adapter_overlapping_queries_dedup_by_url(monkeypatch):
    """Same URL returned by two queries should be deduplicated."""
    shared_job = _sample_job(job_id="999", url="https://saramin.co.kr/job/999")
    response = _jobs_response([shared_job])
    # Two queries → same job returned by both
    mock = _MockSaraminClient([(200, response), (200, response)])
    _target = "app.adapters.aggregators.saramin.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)
    monkeypatch.setattr(
        "app.adapters.aggregators.saramin.settings.saramin_access_key", "test-key"
    )
    monkeypatch.setattr(
        "app.adapters.aggregators.saramin.settings.saramin_api_base_url", _BASE_URL
    )
    monkeypatch.setattr(
        "app.adapters.aggregators.saramin.settings.saramin_max_queries_per_collect", 10
    )
    seed = _seed(roles=["백엔드 개발자", "서버 개발자"])
    opts = _options(detail_fetch_limit_per_source=10, max_results_per_source=50)
    result = await SaraminAdapter().fetch(seed, opts, _COLLECT_DATE)
    urls = [p.source_url for p in result.postings]
    assert len(urls) == len(set(urls)), "duplicate URLs must be removed within adapter"
    assert len(result.postings) == 1


async def test_adapter_source_stats_correct(monkeypatch):
    jobs = [
        _sample_job(job_id=str(i), url=f"https://saramin.co.kr/job/{i}")
        for i in range(4)
    ]
    mock = _MockSaraminClient([(200, _jobs_response(jobs))])
    _target = "app.adapters.aggregators.saramin.AsyncClient"
    monkeypatch.setattr(_target, lambda **kw: mock)
    monkeypatch.setattr(
        "app.adapters.aggregators.saramin.settings.saramin_access_key", "test-key"
    )
    monkeypatch.setattr(
        "app.adapters.aggregators.saramin.settings.saramin_api_base_url", _BASE_URL
    )
    seed = _seed(roles=["백엔드 개발자"])
    result = await SaraminAdapter().fetch(seed, _options(), _COLLECT_DATE)
    stats = result.source_stats
    assert stats is not None
    assert stats.discovered == 4
    assert stats.fetched == 1
    assert stats.parsed == 4
    assert stats.selected == len(result.postings)


async def test_adapter_duplicate_with_jasoseol_same_canonical_fingerprint(
    monkeypatch,
):
    """Saramin and Jasoseol postings with same company/title/deadline share a
    canonical_fingerprint after pipeline normalization, enabling cross-source dedup."""
    from app.adapters.base import RawJobPosting
    from app.services.normalization import normalize

    saramin_raw = RawJobPosting(
        source="saramin",
        source_url="https://saramin.co.kr/job/42",
        company_name="카카오",
        title="백엔드 개발자",
        deadline=date(2026, 8, 1),
    )
    jasoseol_raw = RawJobPosting(
        source="jasoseol",
        source_url="https://jasoseol.com/recruit/9999",
        company_name="카카오",
        title="백엔드 개발자",
        deadline=date(2026, 8, 1),
    )

    saramin_norm = normalize(saramin_raw)
    jasoseol_norm = normalize(jasoseol_raw)

    assert saramin_norm.canonical_fingerprint is not None
    assert saramin_norm.canonical_fingerprint == jasoseol_norm.canonical_fingerprint
