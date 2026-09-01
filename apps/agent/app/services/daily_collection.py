"""DailyCollectionService — Pipeline V2 orchestrator.

Pipeline stages (all deterministic except Collect):
  1  Collect          — fetch raw postings from adapters (with per-adapter retry)
  2  Normalize        — compute identities, clean fields
  3  Validate         — postings that failed normalize are already dropped
  4  Source-level Dedup  — merge exact same source records
  5  Expired/Stale Filter
  6  Company Canonicalization
  7+8 Cross-source Dedup + Cluster Merge
  9  Collection Relevance Scoring
  10 Diversity/Budget Selection
  →  Response (includes source_outcomes per adapter)

Company and industry collection are not yet implemented (1.5 / 2nd MVP).
"""

import asyncio
import itertools
import logging

import app.adapters.official.ably_careers  # noqa: F401 — triggers ABLY_CAREERS registration
import app.adapters.official.greenhouse  # noqa: F401 — triggers GREENHOUSE + DAANGN_CAREERS registration
import app.adapters.official.greeting  # noqa: F401 — triggers GREETING registration
import app.adapters.official.hyundai_motor_careers  # noqa: F401 — triggers HYUNDAI_MOTOR_CAREERS registration
import app.adapters.official.kakao_careers  # noqa: F401 — triggers KAKAO_CAREERS registration
import app.adapters.official.lg_careers  # noqa: F401 — triggers LG_CAREERS registration
import app.adapters.official.lg_cns_careers  # noqa: F401 — triggers LG_CNS_CAREERS registration
import app.adapters.official.naver_careers  # noqa: F401 — triggers NAVER_CAREERS registration
import app.adapters.official.samsung_careers  # noqa: F401 — triggers SAMSUNG_CAREERS registration
import app.adapters.official.sk_careers  # noqa: F401 — triggers SK_CAREERS registration
import app.adapters.official.toss_careers  # noqa: F401 — triggers TOSS_CAREERS registration
from app.adapters.aggregators.jasoseol import JasoseolAdapter
from app.adapters.aggregators.saramin import SaraminAdapter
from app.adapters.base import AdapterResult, JobBoardAdapter, RawJobPosting
from app.adapters.fixture import FixtureAdapter
from app.adapters.official_company import OfficialCompanyAdapter
from app.core.config import settings
from app.schemas.collection import (
    CollectedJobPosting,
    CollectionOptions,
    CollectionStats,
    DailyCollectRequest,
    DailyCollectResponse,
    SeedKeywords,
    SourceOutcome,
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

# ---------------------------------------------------------------------------
# Per-adapter retry helpers
# ---------------------------------------------------------------------------

_RETRY_MAX_ATTEMPTS = 2   # total tries = 1 initial + 1 retry
_RETRY_SLEEP_SECONDS = 2


def _classify_error(exc: Exception) -> str:
    """Return a brief, non-sensitive error category string.

    Used in SourceOutcome.error_summary — no sensitive data.
    """
    name = type(exc).__name__
    msg = str(exc)
    if isinstance(exc, asyncio.TimeoutError):
        return "timeout"
    # aiohttp connection errors — check by class hierarchy name to avoid hard dependency
    if "ClientConnectionError" in name or "ClientConnectorError" in name:
        return "connection_error"
    # HTTP status codes embedded in the exception message (e.g. "429", "503")
    for code in ("429", "503", "502", "500", "504"):
        if code in msg:
            return f"http_{code}"
    if any(c in msg for c in ("5xx", "500", "502", "503", "504")):
        return "http_5xx"
    return f"error:{name}"


def _is_retryable(exc: Exception) -> bool:
    """Return True when the exception warrants an automatic retry.

    Retryable:  asyncio.TimeoutError, aiohttp ClientConnectionError family,
                HTTP 429 / 5xx responses.
    Non-retryable: parsing errors, clear 4xx (400/401/403/404/422).
    """
    if isinstance(exc, asyncio.TimeoutError):
        return True
    name = type(exc).__name__
    if "ClientConnectionError" in name or "ClientConnectorError" in name:
        return True
    msg = str(exc)
    for non_retry_code in ("400", "401", "403", "404", "422"):
        if non_retry_code in msg:
            return False
    for retry_code in ("429", "500", "502", "503", "504"):
        if retry_code in msg:
            return True
    return False


async def _fetch_with_retry(
    adapter: JobBoardAdapter,
    seed_keywords: SeedKeywords,
    options: CollectionOptions,
    collect_date: object,
) -> AdapterResult | None:
    """Fetch from *adapter* with up to one retry for transient errors.

    Returns an AdapterResult on success, or None on final failure.
    Non-retryable errors (4xx, parse errors) are not retried.
    """
    last_exc: Exception | None = None
    for attempt in range(_RETRY_MAX_ATTEMPTS):
        try:
            return await adapter.fetch(
                seed_keywords=seed_keywords,
                options=options,
                collect_date=collect_date,
            )
        except Exception as exc:
            last_exc = exc
            if not _is_retryable(exc) or attempt + 1 >= _RETRY_MAX_ATTEMPTS:
                break
            log.warning(
                "daily_collection: %s attempt %d failed (%s), retrying in %ds",
                adapter.source_name,
                attempt + 1,
                _classify_error(exc),
                _RETRY_SLEEP_SECONDS,
            )
            await asyncio.sleep(_RETRY_SLEEP_SECONDS)

    log.warning(
        "daily_collection: %s final failure after %d attempt(s): %s",
        adapter.source_name,
        min(_RETRY_MAX_ATTEMPTS, 1 + (1 if last_exc is not None else 0)),
        _classify_error(last_exc) if last_exc else "unknown",
    )
    return None


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
        log.warning(
            "daily_collection: no adapters enabled — "
            "set JOB_COLLECTION_USE_FIXTURE=true or enable another adapter"
        )
    return adapters


def _score(posting: CollectedJobPosting, seed: SeedKeywords) -> float:
    """Collection-phase relevance score (higher = more relevant to seed keywords).

    Priority: roles (3.0) > companies (2.0) > skills (0.5 each, cap 2.0)
              > industries (0.3) > locations (0.2) > experience (0.15)
              > employment (0.1) > description presence (0.3).
    Do NOT call LLMs here — pure keyword matching on normalised fields only.

    Note: each source adapter is fetched with per-source limits
    (discovery_limit_per_source / detail_fetch_limit_per_source) set in
    CollectionOptions.  If a single source dominates the pool, lower its limits
    rather than adjusting scores here.
    """
    score = 0.0
    title_lower = posting.title.lower()
    roles_lower = [r.lower() for r in posting.roles]
    company_lower = posting.company_name.lower()
    skills_lower = [s.lower() for s in posting.skills]

    # Roles: title match or normalised roles list match
    for role in seed.roles:
        rl = role.lower()
        if rl in title_lower or any(rl in r for r in roles_lower):
            score += 3.0

    # Companies: exact or substring match on company name
    for company in seed.companies:
        if company.lower() in company_lower:
            score += 2.0

    # Skills: capped at 2.0 total so a posting with 10 matching skills doesn't
    # dominate over a role/company match
    skill_score = 0.0
    for skill in seed.skills:
        if any(skill.lower() in s for s in skills_lower):
            skill_score += 0.5
    score += min(skill_score, 2.0)

    # Industries: light signal when seed specifies industry preference
    if posting.description:
        desc_lower = posting.description.lower()
        for industry in seed.industries:
            if industry.lower() in desc_lower:
                score += 0.3

    # Locations: posted location matches user location preference
    if posting.location:
        loc_lower = posting.location.lower()
        for location in seed.locations:
            if location.lower() in loc_lower:
                score += 0.2

    # Experience level: mild signal, matching preferred level
    if posting.experience_level:
        exp_lower = posting.experience_level.lower()
        for exp in seed.experience_levels:
            if exp.lower() in exp_lower:
                score += 0.15

    # Employment type: mild signal
    if posting.employment_type:
        emp_lower = posting.employment_type.lower()
        for emp in seed.employment_types:
            if emp.lower() in emp_lower:
                score += 0.1

    # Description presence: tiny bonus for richer postings
    if posting.description:
        score += 0.3

    return score


def _interleave_by_source(
    postings: list[CollectedJobPosting],
) -> list[CollectedJobPosting]:
    """seed 없이 점수가 모두 동일할 때 소스별 round-robin으로 budget 독점을 방지한다.

    진입 순서(adapter 등록 순서)에 따른 소스 내부 순서를 유지하면서,
    소스 간은 교대로 배치한다.
    """
    by_source: dict[str, list[CollectedJobPosting]] = {}
    source_order: list[str] = []
    for p in postings:
        if p.source not in by_source:
            by_source[p.source] = []
            source_order.append(p.source)
        by_source[p.source].append(p)

    result: list[CollectedJobPosting] = []
    for group in itertools.zip_longest(*[by_source[s] for s in source_order]):
        result.extend(p for p in group if p is not None)
    return result


def _score_and_sort(
    postings: list[CollectedJobPosting],
    seed: SeedKeywords,
) -> list[CollectedJobPosting]:
    if not postings:
        return postings
    if not (seed.roles or seed.companies or seed.skills):
        # seed가 없으면 점수가 모두 0 → 소스별 round-robin으로 budget 독점 방지
        return _interleave_by_source(postings)
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
        source_outcomes: list[SourceOutcome] = []

        # Aggregate per-adapter stats before service-level processing
        total_discovered = 0
        total_fetched = 0
        total_parsed = 0

        for adapter in adapters:
            result = await _fetch_with_retry(
                adapter,
                seed_keywords=request.seed_keywords,
                options=request.options,
                collect_date=request.collect_date,
            )
            if result is None:
                # Final failure — record outcome but preserve other adapters' results
                source_outcomes.append(
                    SourceOutcome(
                        source_name=adapter.source_name,
                        success=False,
                        posting_count=0,
                        error_summary="fetch_failed",
                    )
                )
                warnings.append(f"adapter {adapter.source_name} failed after retries")
                continue

            raw_postings.extend(result.postings)
            warnings.extend(result.warnings)
            if result.source_stats:
                posting_count = result.source_stats.parsed
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
                posting_count = len(result.postings)
                total_discovered += posting_count
                total_fetched += posting_count
                total_parsed += posting_count
                log.info(
                    "daily_collection: %s returned %d postings (no source_stats)",
                    adapter.source_name,
                    posting_count,
                )
            source_outcomes.append(
                SourceOutcome(
                    source_name=adapter.source_name,
                    success=True,
                    posting_count=posting_count,
                    error_summary=None,
                )
            )

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

        # 소스별 최종 생존 수 (budget 적용 후)
        source_final_counts: dict[str, int] = {}
        for p in selected:
            source_final_counts[p.source] = source_final_counts.get(p.source, 0) + 1
        source_budget_log = ", ".join(
            f"{s}:{c}" for s, c in source_final_counts.items()
        )

        log.info(
            "daily_collection: discovered=%d fetched=%d parsed=%d "
            "source_exact=%d cross_merged=%d expired=%d stale=%d "
            "truncated=%d final=%d source_budget=[%s]",
            total_discovered, total_fetched, total_parsed,
            source_exact, cross_merged, expired_count, stale_count,
            truncated, len(selected), source_budget_log,
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
            source_outcomes=source_outcomes,
            warnings=warnings,
        )
