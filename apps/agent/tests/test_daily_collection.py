"""Tests for DailyCollectionService.

All tests use mock adapters — no real network calls, no DB access.
"""

from datetime import date
from unittest.mock import AsyncMock, patch

import pytest

from app.adapters.base import AdapterResult, RawJobPosting
from app.schemas.collection import (
    CollectionOptions,
    CollectionStats,
    DailyCollectRequest,
    SeedKeywords,
)
from app.services.daily_collection import DailyCollectionService

_COLLECT_DATE = date(2026, 7, 2)
_SVC_MODULE = "app.services.daily_collection.settings"
_USE_FIXTURE = f"{_SVC_MODULE}.job_collection_use_fixture"
_USE_REAL = f"{_SVC_MODULE}.job_collection_enable_jasoseol"


def _request(
    categories: list[str] | None = None,
    options: CollectionOptions | None = None,
) -> DailyCollectRequest:
    return DailyCollectRequest(
        collection_job_id=42,
        collect_date=_COLLECT_DATE,
        categories=categories if categories is not None else ["JOB_POSTING"],
        seed_keywords=SeedKeywords(),
        options=options or CollectionOptions(),
    )


def _raw(
    suffix: str = "1",
    deadline: date | None = date(2026, 7, 20),
) -> RawJobPosting:
    return RawJobPosting(
        source="mock",
        source_url=f"https://mock.local/jobs/{suffix}",
        company_name=f"회사{suffix}",
        title=f"개발자 채용{suffix}",
        deadline=deadline,
    )


# ── category gate ──────────────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_no_job_posting_category_returns_empty():
    svc = DailyCollectionService()
    resp = await svc.collect(_request(categories=["COMPANY_NEWS"]))
    assert resp.job_postings == []
    assert resp.stats.final_count == 0
    assert resp.stats.discovered_count == 0


@pytest.mark.asyncio
async def test_no_job_posting_category_preserves_collection_job_id():
    svc = DailyCollectionService()
    resp = await svc.collect(_request(categories=[]))
    assert resp.collection_job_id == 42
    assert resp.collect_date == _COLLECT_DATE


# ── adapter config ─────────────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_fixture_flag_uses_fixture_adapter(monkeypatch):
    monkeypatch.setattr(_USE_FIXTURE, True)
    monkeypatch.setattr(_USE_REAL, False)

    mock_fetch = AsyncMock(return_value=AdapterResult(postings=[_raw("1")]))
    with patch("app.services.daily_collection.FixtureAdapter") as MockFixture:
        instance = MockFixture.return_value
        instance.fetch = mock_fetch
        instance.source_name = "fixture"

        svc = DailyCollectionService()
        resp = await svc.collect(_request())

    mock_fetch.assert_called_once()
    assert resp.stats.final_count == 1


@pytest.mark.asyncio
async def test_real_sources_flag_adds_jasoseol_adapter(monkeypatch):
    monkeypatch.setattr(_USE_FIXTURE, False)
    monkeypatch.setattr(_USE_REAL, True)

    mock_fetch = AsyncMock(return_value=AdapterResult(postings=[_raw("j1")]))
    with patch("app.services.daily_collection.JasoseolAdapter") as MockJasoseol:
        instance = MockJasoseol.return_value
        instance.fetch = mock_fetch
        instance.source_name = "jasoseol"

        svc = DailyCollectionService()
        resp = await svc.collect(_request())

    mock_fetch.assert_called_once()
    assert resp.stats.final_count == 1


@pytest.mark.asyncio
async def test_both_flags_true_uses_both_adapters(monkeypatch):
    monkeypatch.setattr(_USE_FIXTURE, True)
    monkeypatch.setattr(_USE_REAL, True)

    fixture_fetch = AsyncMock(return_value=AdapterResult(postings=[_raw("f1")]))
    jasoseol_fetch = AsyncMock(return_value=AdapterResult(postings=[_raw("j1")]))

    with (
        patch("app.services.daily_collection.FixtureAdapter") as MockF,
        patch("app.services.daily_collection.JasoseolAdapter") as MockJ,
    ):
        MockF.return_value.fetch = fixture_fetch
        MockF.return_value.source_name = "fixture"
        MockJ.return_value.fetch = jasoseol_fetch
        MockJ.return_value.source_name = "jasoseol"

        svc = DailyCollectionService()
        resp = await svc.collect(_request())

    fixture_fetch.assert_called_once()
    jasoseol_fetch.assert_called_once()
    assert resp.stats.discovered_count == 2


@pytest.mark.asyncio
async def test_both_flags_false_falls_back_to_fixture(monkeypatch):
    monkeypatch.setattr(_USE_FIXTURE, False)
    monkeypatch.setattr(_USE_REAL, False)

    mock_fetch = AsyncMock(return_value=AdapterResult(postings=[_raw("1")]))
    with patch("app.services.daily_collection.FixtureAdapter") as MockFixture:
        instance = MockFixture.return_value
        instance.fetch = mock_fetch
        instance.source_name = "fixture"

        svc = DailyCollectionService()
        resp = await svc.collect(_request())

    mock_fetch.assert_called_once()
    assert resp.stats.final_count == 1


# ── pipeline stats ─────────────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_stats_discovered_count_is_total_before_dedup(monkeypatch):
    monkeypatch.setattr(_USE_FIXTURE, True)
    monkeypatch.setattr(_USE_REAL, False)

    # Two postings with identical source_url → one will be deduped
    dup = _raw("same")
    mock_fetch = AsyncMock(return_value=AdapterResult(postings=[dup, dup]))
    with patch("app.services.daily_collection.FixtureAdapter") as MockF:
        MockF.return_value.fetch = mock_fetch
        MockF.return_value.source_name = "fixture"

        svc = DailyCollectionService()
        resp = await svc.collect(_request())

    assert resp.stats.discovered_count == 2
    assert resp.stats.duplicate_count == 1
    assert resp.stats.final_count == 1


@pytest.mark.asyncio
async def test_stats_final_count_excludes_expired(monkeypatch):
    monkeypatch.setattr(_USE_FIXTURE, True)
    monkeypatch.setattr(_USE_REAL, False)

    valid = _raw("valid", deadline=date(2026, 7, 20))
    expired = _raw("expired", deadline=date(2026, 6, 1))  # before collect_date
    mock_fetch = AsyncMock(return_value=AdapterResult(postings=[valid, expired]))

    with patch("app.services.daily_collection.FixtureAdapter") as MockF:
        MockF.return_value.fetch = mock_fetch
        MockF.return_value.source_name = "fixture"

        svc = DailyCollectionService()
        resp = await svc.collect(_request())

    assert resp.stats.discovered_count == 2
    assert resp.stats.filtered_count == 1
    assert resp.stats.final_count == 1


# ── warnings ───────────────────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_adapter_warnings_are_propagated(monkeypatch):
    monkeypatch.setattr(_USE_FIXTURE, True)
    monkeypatch.setattr(_USE_REAL, False)

    mock_fetch = AsyncMock(
        return_value=AdapterResult(postings=[], warnings=["sitemap timeout"])
    )
    with patch("app.services.daily_collection.FixtureAdapter") as MockF:
        MockF.return_value.fetch = mock_fetch
        MockF.return_value.source_name = "fixture"

        svc = DailyCollectionService()
        resp = await svc.collect(_request())

    assert "sitemap timeout" in resp.warnings


@pytest.mark.asyncio
async def test_adapter_unexpected_exception_adds_warning(monkeypatch):
    monkeypatch.setattr(_USE_FIXTURE, True)
    monkeypatch.setattr(_USE_REAL, False)

    mock_fetch = AsyncMock(side_effect=RuntimeError("boom"))
    with patch("app.services.daily_collection.FixtureAdapter") as MockF:
        MockF.return_value.fetch = mock_fetch
        MockF.return_value.source_name = "fixture"

        svc = DailyCollectionService()
        resp = await svc.collect(_request())

    assert any("fixture" in w and "boom" in w for w in resp.warnings)
    assert resp.job_postings == []


# ── response shape ─────────────────────────────────────────────────────────────


@pytest.mark.asyncio
async def test_company_and_industry_issues_are_empty(monkeypatch):
    monkeypatch.setattr(_USE_FIXTURE, True)
    monkeypatch.setattr(_USE_REAL, False)

    mock_fetch = AsyncMock(return_value=AdapterResult(postings=[]))
    with patch("app.services.daily_collection.FixtureAdapter") as MockF:
        MockF.return_value.fetch = mock_fetch
        MockF.return_value.source_name = "fixture"

        svc = DailyCollectionService()
        resp = await svc.collect(_request())

    assert resp.company_issues == []
    assert resp.industry_issues == []


@pytest.mark.asyncio
async def test_response_preserves_collection_job_id_and_date(monkeypatch):
    monkeypatch.setattr(_USE_FIXTURE, True)
    monkeypatch.setattr(_USE_REAL, False)

    mock_fetch = AsyncMock(return_value=AdapterResult(postings=[]))
    with patch("app.services.daily_collection.FixtureAdapter") as MockF:
        MockF.return_value.fetch = mock_fetch
        MockF.return_value.source_name = "fixture"

        svc = DailyCollectionService()
        resp = await svc.collect(_request())

    assert resp.collection_job_id == 42
    assert resp.collect_date == _COLLECT_DATE


@pytest.mark.asyncio
async def test_stats_type_is_collection_stats(monkeypatch):
    monkeypatch.setattr(_USE_FIXTURE, True)
    monkeypatch.setattr(_USE_REAL, False)

    mock_fetch = AsyncMock(return_value=AdapterResult(postings=[]))
    with patch("app.services.daily_collection.FixtureAdapter") as MockF:
        MockF.return_value.fetch = mock_fetch
        MockF.return_value.source_name = "fixture"

        svc = DailyCollectionService()
        resp = await svc.collect(_request())

    assert isinstance(resp.stats, CollectionStats)
