"""DailyCollectionService — orchestrates the full job collection pipeline.

Workflow:
  1. Skip if JOB_POSTING is not in requested categories.
  2. Build adapter list from config flags.
  3. Fetch raw postings from each adapter (sequentially to keep error isolation).
  4. Normalize → deduplicate → filter expired/out-of-range postings.
  5. Return DailyCollectResponse with stats and accumulated warnings.

Company and industry collection are not yet implemented (1.5 / 2nd MVP).
"""

import logging

from app.adapters.base import JobBoardAdapter, RawJobPosting
from app.adapters.fixture import FixtureAdapter
from app.adapters.jasoseol import JasoseolAdapter
from app.core.config import settings
from app.schemas.collection import (
    CollectionStats,
    DailyCollectRequest,
    DailyCollectResponse,
)
from app.services.deduplication import deduplicate, filter_postings
from app.services.normalization import normalize_many

log = logging.getLogger(__name__)

_JOB_POSTING_CATEGORY = "JOB_POSTING"


def _build_adapters() -> list[JobBoardAdapter]:
    adapters: list[JobBoardAdapter] = []
    if settings.job_collection_use_fixture:
        adapters.append(FixtureAdapter())
    if settings.job_collection_enable_real_sources:
        adapters.append(JasoseolAdapter())
    if not adapters:
        log.info("daily_collection: both flags off — using FixtureAdapter as fallback")
        adapters.append(FixtureAdapter())
    return adapters


class DailyCollectionService:
    async def collect(self, request: DailyCollectRequest) -> DailyCollectResponse:
        if _JOB_POSTING_CATEGORY not in request.categories:
            return DailyCollectResponse(
                collection_job_id=request.collection_job_id,
                collect_date=request.collect_date,
            )

        adapters = _build_adapters()
        raw_postings: list[RawJobPosting] = []
        warnings: list[str] = []

        for adapter in adapters:
            try:
                result = await adapter.fetch(
                    seed_keywords=request.seed_keywords,
                    options=request.options,
                    collect_date=request.collect_date,
                )
                raw_postings.extend(result.postings)
                warnings.extend(result.warnings)
                log.info(
                    "daily_collection: %s returned %d postings",
                    adapter.source_name,
                    len(result.postings),
                )
            except Exception as exc:
                msg = f"adapter {adapter.source_name} raised unexpectedly: {exc}"
                log.warning(msg)
                warnings.append(msg)

        collected_count = len(raw_postings)

        normalized = normalize_many(raw_postings)
        unique, dedup_stats = deduplicate(normalized)
        filtered = filter_postings(
            unique, request.collect_date, request.options.lookback_days
        )

        log.info(
            "daily_collection: collected=%d deduped=%d filtered=%d",
            collected_count,
            dedup_stats.duplicate_count,
            len(filtered),
        )

        return DailyCollectResponse(
            collection_job_id=request.collection_job_id,
            collect_date=request.collect_date,
            job_postings=filtered,
            company_issues=[],
            industry_issues=[],
            stats=CollectionStats(
                collected_count=collected_count,
                deduplicated_count=dedup_stats.duplicate_count,
                job_posting_count=len(filtered),
            ),
            warnings=warnings,
        )
