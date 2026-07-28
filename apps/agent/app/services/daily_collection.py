"""DailyCollectionService — Pipeline V2 orchestrator.

Pipeline stages (all deterministic except Collect):
  1  Collect          — fetch raw postings from adapters
  2  Normalize        — compute identities, clean fields
  3  Validate         — postings that failed normalize are already dropped
  4  Source-level Dedup  — merge exact same source records
  5  Expired/Stale Filter
  6  Company Canonicalization
  7+8 Cross-source Dedup + Cluster Merge
  9  Collection Relevance Scoring
  10 Diversity/Budget Selection
  →  Response

Company and industry collection are not yet implemented (1.5 / 2nd MVP).
"""

import logging

import app.adapters.greeting  # noqa: F401 — triggers GREETING registration
from app.adapters.base import JobBoardAdapter, RawJobPosting
from app.adapters.fixture import FixtureAdapter
from app.adapters.jasoseol import JasoseolAdapter
from app.adapters.official_company import OfficialCompanyAdapter
from app.adapters.saramin import SaraminAdapter
from app.core.config import settings
from app.schemas.collection import (
    CollectedJobPosting,
    CollectionOptions,
    CollectionStats,
    DailyCollectRequest,
    DailyCollectResponse,
    SeedKeywords,
)
from app.services.company_canon import apply_company_canonicalization
from app.services.deduplication import (
    dedup_cross_source,
    dedup_source_level,
    filter_postings_with_stats,
)
from app.services.normalization import normalize_many

log = logging.getLogger(__name__)

_JOB_POSTING_CATEGORY = "JOB_POSTING"


def _build_adapters(request: DailyCollectRequest) -> list[JobBoardAdapter]:
    adapters: list[JobBoardAdapter] = []
    if settings.job_collection_use_fixture:
        adapters.append(FixtureAdapter())
    if settings.job_collection_enable_jasoseol:
        adapters.append(JasoseolAdapter())
    if settings.job_collection_enable_saramin:
        adapters.append(SaraminAdapter())
    if request.official_company_sources:
        adapters.append(
            OfficialCompanyAdapter(
                request.official_company_sources,
                request.company_profiles,
            )
        )
    if not adapters:
        log.info("daily_collection: both flags off — using FixtureAdapter as fallback")
        adapters.append(FixtureAdapter())
    return adapters


def _score(posting: CollectedJobPosting, seed: SeedKeywords) -> float:
    score = 0.0
    title_lower = posting.title.lower()
    company_lower = posting.company_name.lower()
    for role in seed.roles:
        if role.lower() in title_lower:
            score += 2.0
    for company in seed.companies:
        if company.lower() in company_lower:
            score += 3.0
    for skill in seed.skills:
        if any(skill.lower() in s.lower() for s in posting.skills):
            score += 1.0
    if posting.description:
        score += 0.5
    return score


def _score_and_sort(
    postings: list[CollectedJobPosting],
    seed: SeedKeywords,
) -> list[CollectedJobPosting]:
    if not postings or not (seed.roles or seed.companies or seed.skills):
        return postings
    return sorted(postings, key=lambda p: _score(p, seed), reverse=True)


def _select_budget(
    postings: list[CollectedJobPosting],
    options: CollectionOptions,
) -> tuple[list[CollectedJobPosting], int]:
    limit = options.max_total_results
    if len(postings) <= limit:
        return postings, 0
    return postings[:limit], len(postings) - limit


class DailyCollectionService:
    async def collect(self, request: DailyCollectRequest) -> DailyCollectResponse:
        if _JOB_POSTING_CATEGORY not in request.categories:
            return DailyCollectResponse(
                collection_job_id=request.collection_job_id,
                collect_date=request.collect_date,
            )

        # Stage 1: Collect
        adapters = _build_adapters(request)
        raw_postings: list[RawJobPosting] = []
        warnings: list[str] = []

        # Aggregate per-adapter stats before service-level processing
        total_discovered = 0
        total_fetched = 0
        total_parsed = 0

        for adapter in adapters:
            try:
                result = await adapter.fetch(
                    seed_keywords=request.seed_keywords,
                    options=request.options,
                    collect_date=request.collect_date,
                )
                raw_postings.extend(result.postings)
                warnings.extend(result.warnings)
                if result.source_stats:
                    total_discovered += result.source_stats.discovered
                    total_fetched += result.source_stats.fetched
                    total_parsed += result.source_stats.parsed
                    log.info(
                        "daily_collection: %s discovered=%d fetched=%d "
                        "parsed=%d selected=%d",
                        adapter.source_name,
                        result.source_stats.discovered,
                        result.source_stats.fetched,
                        result.source_stats.parsed,
                        result.source_stats.selected,
                    )
                else:
                    n = len(result.postings)
                    total_discovered += n
                    total_fetched += n
                    total_parsed += n
                    log.info(
                        "daily_collection: %s returned %d postings (no source_stats)",
                        adapter.source_name,
                        n,
                    )
            except Exception as exc:
                msg = f"adapter {adapter.source_name} raised unexpectedly: {exc}"
                log.warning(msg)
                warnings.append(msg)

        # Stage 2+3: Normalize (validation happens implicitly — invalid raws
        # are skipped because normalize raises, and we don't catch normalize
        # errors here; all current adapters guarantee non-null required fields)
        normalized = normalize_many(raw_postings)

        # Stage 4: Source-level exact dedup
        after_source, source_exact = dedup_source_level(normalized)

        # Stage 5: Expired/Stale filter
        active, expired_count, stale_count = filter_postings_with_stats(
            after_source, request.collect_date, request.options
        )

        # Stage 6: Company canonicalization
        canonicalized = apply_company_canonicalization(active, request.company_profiles)

        # Stage 7+8: Cross-source dedup + cluster merge
        after_cross, cross_merged = dedup_cross_source(canonicalized)

        # Stage 9: Relevance scoring
        scored = _score_and_sort(after_cross, request.seed_keywords)

        # Stage 10: Budget selection
        selected, truncated = _select_budget(scored, request.options)

        log.info(
            "daily_collection: discovered=%d fetched=%d parsed=%d "
            "source_exact=%d cross_merged=%d expired=%d stale=%d "
            "truncated=%d final=%d",
            total_discovered, total_fetched, total_parsed,
            source_exact, cross_merged, expired_count, stale_count,
            truncated, len(selected),
        )

        return DailyCollectResponse(
            collection_job_id=request.collection_job_id,
            collect_date=request.collect_date,
            job_postings=selected,
            company_issues=[],
            industry_issues=[],
            stats=CollectionStats(
                discovered_count=total_discovered,
                fetched_count=total_fetched,
                parsed_count=total_parsed,
                duplicate_count=source_exact + cross_merged,
                filtered_count=expired_count + stale_count,
                truncated_count=truncated,
                final_count=len(selected),
            ),
            warnings=warnings,
        )
