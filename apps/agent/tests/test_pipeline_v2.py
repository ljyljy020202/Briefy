"""Pipeline V2 integration tests.

These tests exercise the full DailyCollectionService.collect() pipeline using
mock adapters.  They verify the new identity/dedup semantics, stat fields, and
budget selection introduced in Pipeline V2.
"""

from datetime import date, datetime
from unittest.mock import AsyncMock, patch

import pytest

from app.adapters.base import AdapterResult, RawJobPosting
from app.schemas.collection import (
    CollectionOptions,
    CompanyProfile,
    DailyCollectRequest,
    SeedKeywords,
)
from app.services.daily_collection import DailyCollectionService

_COLLECT_DATE = date(2026, 7, 2)
_FUTURE = date(2026, 7, 20)
_PAST = date(2026, 6, 1)

_SVC_MODULE = "app.services.daily_collection.settings"
_USE_FIXTURE = f"{_SVC_MODULE}.job_collection_use_fixture"
_USE_REAL = f"{_SVC_MODULE}.job_collection_enable_real_sources"


def _request(
    options: CollectionOptions | None = None,
    company_profiles: list[CompanyProfile] | None = None,
) -> DailyCollectRequest:
    return DailyCollectRequest(
        collection_job_id=1,
        collect_date=_COLLECT_DATE,
        categories=["JOB_POSTING"],
        seed_keywords=SeedKeywords(
            roles=["백엔드 개발자"],
            companies=["네이버"],
            skills=["Java"],
        ),
        options=options or CollectionOptions(),
        company_profiles=company_profiles or [],
    )


def _raw(
    suffix: str = "1",
    company: str = "회사",
    title: str = "백엔드 개발자",
    deadline: date | None = _FUTURE,
    source: str = "mock",
    source_external_id: str | None = None,
) -> RawJobPosting:
    return RawJobPosting(
        source=source,
        source_url=f"https://mock.local/jobs/{suffix}",
        company_name=company,
        title=title,
        deadline=deadline,
        source_external_id=source_external_id,
    )


# ── Stats field names ─────────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_stats_discovered_count_is_raw_adapter_count(monkeypatch):
    monkeypatch.setattr(_USE_FIXTURE, True)
    monkeypatch.setattr(_USE_REAL, False)

    mock_fetch = AsyncMock(return_value=AdapterResult(postings=[_raw("1"), _raw("2")]))
    with patch("app.services.daily_collection.FixtureAdapter") as MockF:
        MockF.return_value.fetch = mock_fetch
        MockF.return_value.source_name = "fixture"
        resp = await DailyCollectionService().collect(_request())

    assert resp.stats.discovered_count == 2


@pytest.mark.asyncio
async def test_stats_final_count_equals_output_count(monkeypatch):
    monkeypatch.setattr(_USE_FIXTURE, True)
    monkeypatch.setattr(_USE_REAL, False)

    postings = [_raw("1"), _raw("2"), _raw("3")]
    mock_fetch = AsyncMock(return_value=AdapterResult(postings=postings))
    with patch("app.services.daily_collection.FixtureAdapter") as MockF:
        MockF.return_value.fetch = mock_fetch
        MockF.return_value.source_name = "fixture"
        resp = await DailyCollectionService().collect(_request())

    assert resp.stats.final_count == len(resp.job_postings)


@pytest.mark.asyncio
async def test_stats_discovered_gt_fetched_when_fetch_budget_applies(monkeypatch):
    """Adapter-level fetch budget: 100 URLs discovered but only 50 fetched."""
    monkeypatch.setattr(_USE_FIXTURE, True)
    monkeypatch.setattr(_USE_REAL, False)

    from app.adapters.base import AdapterSourceStats

    mock_postings = [_raw(str(i)) for i in range(40)]
    mock_result = AdapterResult(
        postings=mock_postings,
        source_stats=AdapterSourceStats(
            discovered=100, fetched=50, parsed=45, selected=40
        ),
    )
    mock_fetch = AsyncMock(return_value=mock_result)
    with patch("app.services.daily_collection.FixtureAdapter") as MockF:
        MockF.return_value.fetch = mock_fetch
        MockF.return_value.source_name = "fixture"
        resp = await DailyCollectionService().collect(_request())

    assert resp.stats.discovered_count == 100
    assert resp.stats.fetched_count == 50
    assert resp.stats.parsed_count == 45
    assert resp.stats.discovered_count > resp.stats.fetched_count


@pytest.mark.asyncio
async def test_stats_fetched_gt_parsed_when_pages_fail(monkeypatch):
    """Some detail pages fail to parse: fetched > parsed."""
    monkeypatch.setattr(_USE_FIXTURE, True)
    monkeypatch.setattr(_USE_REAL, False)

    from app.adapters.base import AdapterSourceStats

    mock_postings = [_raw(str(i)) for i in range(30)]
    mock_result = AdapterResult(
        postings=mock_postings,
        source_stats=AdapterSourceStats(
            discovered=50, fetched=50, parsed=30, selected=30
        ),
    )
    mock_fetch = AsyncMock(return_value=mock_result)
    with patch("app.services.daily_collection.FixtureAdapter") as MockF:
        MockF.return_value.fetch = mock_fetch
        MockF.return_value.source_name = "fixture"
        resp = await DailyCollectionService().collect(_request())

    assert resp.stats.fetched_count == 50
    assert resp.stats.parsed_count == 30
    assert resp.stats.fetched_count > resp.stats.parsed_count


# ── Source-level exact dedup (Stage 4) ───────────────────────────────────────


@pytest.mark.asyncio
async def test_stats_duplicate_count_includes_source_level(monkeypatch):
    monkeypatch.setattr(_USE_FIXTURE, True)
    monkeypatch.setattr(_USE_REAL, False)

    dup = _raw("same")
    mock_fetch = AsyncMock(return_value=AdapterResult(postings=[dup, dup]))
    with patch("app.services.daily_collection.FixtureAdapter") as MockF:
        MockF.return_value.fetch = mock_fetch
        MockF.return_value.source_name = "fixture"
        resp = await DailyCollectionService().collect(_request())

    assert resp.stats.duplicate_count == 1
    assert resp.stats.final_count == 1


# ── Cross-source dedup (Stage 7+8) ────────────────────────────────────────────


@pytest.mark.asyncio
async def test_stats_duplicate_count_includes_cross_source(monkeypatch):
    monkeypatch.setattr(_USE_FIXTURE, True)
    monkeypatch.setattr(_USE_REAL, False)

    # Same company/title/deadline from two different sources → cross-source merge
    wanted = _raw("w1", company="네이버", title="백엔드 개발자", source="wanted")
    jasoseol = _raw("j1", company="네이버", title="백엔드 개발자", source="jasoseol")
    mock_fetch = AsyncMock(return_value=AdapterResult(postings=[wanted, jasoseol]))
    with patch("app.services.daily_collection.FixtureAdapter") as MockF:
        MockF.return_value.fetch = mock_fetch
        MockF.return_value.source_name = "fixture"
        resp = await DailyCollectionService().collect(_request())

    assert resp.stats.duplicate_count == 1
    assert resp.stats.final_count == 1


@pytest.mark.asyncio
async def test_pipeline_v2_cross_source_merge_keeps_all_source_refs(monkeypatch):
    monkeypatch.setattr(_USE_FIXTURE, True)
    monkeypatch.setattr(_USE_REAL, False)

    wanted = _raw("w1", company="네이버", title="백엔드 개발자", source="wanted")
    jasoseol = _raw("j1", company="네이버", title="백엔드 개발자", source="jasoseol")
    mock_fetch = AsyncMock(return_value=AdapterResult(postings=[wanted, jasoseol]))
    with patch("app.services.daily_collection.FixtureAdapter") as MockF:
        MockF.return_value.fetch = mock_fetch
        MockF.return_value.source_name = "fixture"
        resp = await DailyCollectionService().collect(_request())

    assert len(resp.job_postings) == 1
    sources_in_refs = {ref.source for ref in resp.job_postings[0].source_refs}
    assert "wanted" in sources_in_refs
    assert "jasoseol" in sources_in_refs


# ── Expired/stale filter (Stage 5) ────────────────────────────────────────────


@pytest.mark.asyncio
async def test_stats_filtered_count_includes_expired(monkeypatch):
    monkeypatch.setattr(_USE_FIXTURE, True)
    monkeypatch.setattr(_USE_REAL, False)

    valid = _raw("v", deadline=_FUTURE)
    expired = _raw("e", deadline=_PAST)
    mock_fetch = AsyncMock(return_value=AdapterResult(postings=[valid, expired]))
    with patch("app.services.daily_collection.FixtureAdapter") as MockF:
        MockF.return_value.fetch = mock_fetch
        MockF.return_value.source_name = "fixture"
        resp = await DailyCollectionService().collect(_request())

    assert resp.stats.filtered_count == 1
    assert resp.stats.final_count == 1


@pytest.mark.asyncio
async def test_stats_filtered_count_includes_stale(monkeypatch):
    monkeypatch.setattr(_USE_FIXTURE, True)
    monkeypatch.setattr(_USE_REAL, False)

    fresh = _raw("f", deadline=_FUTURE)
    stale = RawJobPosting(
        source="mock",
        source_url="https://mock.local/jobs/stale",
        company_name="회사",
        title="스테일 공고",
        deadline=_FUTURE,
        posted_at=datetime(2026, 5, 1, 9, 0, 0),  # far outside default lookback window
    )
    mock_fetch = AsyncMock(return_value=AdapterResult(postings=[fresh, stale]))
    with patch("app.services.daily_collection.FixtureAdapter") as MockF:
        MockF.return_value.fetch = mock_fetch
        MockF.return_value.source_name = "fixture"
        resp = await DailyCollectionService().collect(_request())

    assert resp.stats.filtered_count == 1
    assert resp.stats.final_count == 1


@pytest.mark.asyncio
async def test_stale_filter_uses_lookback_days(monkeypatch):
    """lookback_days controls the stale cutoff: collect_date - lookback_days."""
    monkeypatch.setattr(_USE_FIXTURE, True)
    monkeypatch.setattr(_USE_REAL, False)

    # collect_date=2026-07-02, lookback_days=7 → cutoff=2026-06-25
    recent = RawJobPosting(
        source="mock", source_url="https://mock.local/jobs/r",
        company_name="회사", title="최신 공고", deadline=_FUTURE,
        posted_at=datetime(2026, 7, 1, 9, 0, 0),  # after cutoff → kept
    )
    old = RawJobPosting(
        source="mock", source_url="https://mock.local/jobs/o",
        company_name="회사2", title="오래된 공고", deadline=_FUTURE,
        posted_at=datetime(2026, 6, 20, 9, 0, 0),  # before 2026-06-25 → stale
    )
    mock_fetch = AsyncMock(return_value=AdapterResult(postings=[recent, old]))
    options = CollectionOptions(lookback_days=7)
    with patch("app.services.daily_collection.FixtureAdapter") as MockF:
        MockF.return_value.fetch = mock_fetch
        MockF.return_value.source_name = "fixture"
        resp = await DailyCollectionService().collect(_request(options=options))

    assert resp.stats.filtered_count == 1
    assert resp.stats.final_count == 1


# ── Budget selection (Stage 10) ───────────────────────────────────────────────


@pytest.mark.asyncio
async def test_budget_selection_truncates_to_max_total_results(monkeypatch):
    monkeypatch.setattr(_USE_FIXTURE, True)
    monkeypatch.setattr(_USE_REAL, False)

    postings = [_raw(str(i), title=f"공고{i}") for i in range(10)]
    mock_fetch = AsyncMock(return_value=AdapterResult(postings=postings))
    options = CollectionOptions(max_total_results=3)
    with patch("app.services.daily_collection.FixtureAdapter") as MockF:
        MockF.return_value.fetch = mock_fetch
        MockF.return_value.source_name = "fixture"
        resp = await DailyCollectionService().collect(_request(options=options))

    assert len(resp.job_postings) <= 3
    assert resp.stats.truncated_count == 7
    assert resp.stats.final_count == 3


@pytest.mark.asyncio
async def test_truncated_count_no_double_counting_with_filtered(monkeypatch):
    """Expired postings count only in filtered_count, not truncated_count."""
    monkeypatch.setattr(_USE_FIXTURE, True)
    monkeypatch.setattr(_USE_REAL, False)

    # 5 valid + 2 expired; budget cap = 3
    valid = [_raw(str(i), title=f"공고{i}") for i in range(5)]
    expired = [_raw(f"ex{i}", deadline=_PAST) for i in range(2)]
    mock_fetch = AsyncMock(return_value=AdapterResult(postings=valid + expired))
    options = CollectionOptions(max_total_results=3)
    with patch("app.services.daily_collection.FixtureAdapter") as MockF:
        MockF.return_value.fetch = mock_fetch
        MockF.return_value.source_name = "fixture"
        resp = await DailyCollectionService().collect(_request(options=options))

    assert resp.stats.filtered_count == 2   # expired only
    assert resp.stats.truncated_count == 2  # 5 valid - 3 cap
    assert resp.stats.final_count == 3
    # No double counting: filtered + truncated + final = discovered
    assert (
        resp.stats.filtered_count + resp.stats.truncated_count + resp.stats.final_count
        <= resp.stats.discovered_count
    )


# ── Company canonicalization (Stage 6) ────────────────────────────────────────


@pytest.mark.asyncio
async def test_company_canonicalization_normalizes_name(monkeypatch):
    monkeypatch.setattr(_USE_FIXTURE, True)
    monkeypatch.setattr(_USE_REAL, False)

    profiles = [
        CompanyProfile(
            id=1,
            canonical_name="네이버",
            normalized_name="naver",
            company_size="대기업",
        )
    ]
    raw = _raw("1", company="naver", title="백엔드 개발자")
    mock_fetch = AsyncMock(return_value=AdapterResult(postings=[raw]))
    with patch("app.services.daily_collection.FixtureAdapter") as MockF:
        MockF.return_value.fetch = mock_fetch
        MockF.return_value.source_name = "fixture"
        resp = await DailyCollectionService().collect(
            _request(company_profiles=profiles)
        )

    assert len(resp.job_postings) == 1
    assert resp.job_postings[0].company_name == "네이버"


# ── source_record_key and canonical_fingerprint populated ────────────────────


@pytest.mark.asyncio
async def test_postings_have_source_record_key_and_fingerprint(monkeypatch):
    monkeypatch.setattr(_USE_FIXTURE, True)
    monkeypatch.setattr(_USE_REAL, False)

    mock_fetch = AsyncMock(return_value=AdapterResult(postings=[_raw("1")]))
    with patch("app.services.daily_collection.FixtureAdapter") as MockF:
        MockF.return_value.fetch = mock_fetch
        MockF.return_value.source_name = "fixture"
        resp = await DailyCollectionService().collect(_request())

    assert len(resp.job_postings) >= 1
    for p in resp.job_postings:
        assert p.source_record_key is not None and len(p.source_record_key) == 64
        assert p.canonical_fingerprint is not None
        assert len(p.canonical_fingerprint) == 64
        assert len(p.source_refs) >= 1


