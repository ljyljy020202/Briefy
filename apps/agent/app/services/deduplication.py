from dataclasses import dataclass
from datetime import date, timedelta

from app.schemas.collection import CollectedJobPosting, CollectionOptions, SourceRef
from app.utils.identifiers import (
    compute_canonical_fingerprint,
    normalize_company_name,
    normalize_title,
)


@dataclass
class DeduplicationStats:
    unique_count: int = 0
    duplicate_count: int = 0
    source_exact_count: int = 0
    cross_source_count: int = 0


def _source_key(posting: CollectedJobPosting) -> str:
    """Source-level identity: source_record_key if set, else source_url."""
    return posting.source_record_key or posting.source_url


def _canonical_key(posting: CollectedJobPosting) -> str:
    """Cross-source identity: canonical_fingerprint if set, else computed."""
    if posting.canonical_fingerprint:
        return posting.canonical_fingerprint
    return compute_canonical_fingerprint(
        normalize_company_name(posting.company_name),
        normalize_title(posting.title),
        posting.deadline,
    )


def _merge_cluster(cluster: list[CollectedJobPosting]) -> CollectedJobPosting:
    """Merge a cluster into a single representative posting.

    Scalar fields: first non-None value wins (preserves earliest-seen data).
    List fields (skills, roles): union in insertion order.
    source_refs: union all, deduplicated by (source, source_url).
    """
    if len(cluster) == 1:
        return cluster[0]

    def _first(fn):
        for p in cluster:
            v = fn(p)
            if v is not None:
                return v
        return None

    all_skills: list[str] = list(dict.fromkeys(s for p in cluster for s in p.skills))
    all_roles: list[str] = list(dict.fromkeys(r for p in cluster for r in p.roles))

    seen_ref_keys: set[tuple[str, str]] = set()
    all_refs: list[SourceRef] = []
    for p in cluster:
        for ref in p.source_refs:
            k = (ref.source, ref.source_url)
            if k not in seen_ref_keys:
                seen_ref_keys.add(k)
                all_refs.append(ref)

    # If no source_refs on any posting in the cluster (e.g. pre-normalization
    # test fixtures), synthesise them from the posting fields.
    if not all_refs:
        for p in cluster:
            k = (p.source, p.source_url)
            if k not in seen_ref_keys:
                seen_ref_keys.add(k)
                all_refs.append(
                    SourceRef(
                        source=p.source,
                        source_url=p.source_url,
                        source_external_id=p.source_external_id,
                        source_record_key=p.source_record_key,
                    )
                )

    head = cluster[0]
    return CollectedJobPosting(
        source=head.source,
        source_url=head.source_url,
        company_name=head.company_name,
        title=head.title,
        position=_first(lambda p: p.position) or head.position,
        employment_type=_first(lambda p: p.employment_type),
        experience_level=_first(lambda p: p.experience_level),
        location=_first(lambda p: p.location),
        deadline=_first(lambda p: p.deadline),
        skills=all_skills,
        roles=all_roles,
        description=_first(lambda p: p.description),
        posted_at=_first(lambda p: p.posted_at),
        content_hash=head.content_hash,
        source_external_id=head.source_external_id,
        source_record_key=head.source_record_key,
        canonical_fingerprint=(
            head.canonical_fingerprint or _first(lambda p: p.canonical_fingerprint)
        ),
        source_refs=all_refs,
    )


def _cluster_and_merge(
    postings: list[CollectedJobPosting],
    key_fn,
) -> tuple[list[CollectedJobPosting], int]:
    """Group postings by key, merge each group. Returns (merged, n_removed)."""
    groups: dict[str, list[CollectedJobPosting]] = {}
    order: list[str] = []
    for p in postings:
        k = key_fn(p)
        if k not in groups:
            groups[k] = []
            order.append(k)
        groups[k].append(p)

    merged = [_merge_cluster(groups[k]) for k in order]
    return merged, len(postings) - len(merged)


def dedup_source_level(
    postings: list[CollectedJobPosting],
) -> tuple[list[CollectedJobPosting], int]:
    """Stage 4: source-level exact dedup by source_record_key / source_url."""
    return _cluster_and_merge(postings, _source_key)


def dedup_cross_source(
    postings: list[CollectedJobPosting],
) -> tuple[list[CollectedJobPosting], int]:
    """Stage 7+8: cross-source duplicate matching and cluster merge."""
    return _cluster_and_merge(postings, _canonical_key)


def deduplicate(
    postings: list[CollectedJobPosting],
) -> tuple[list[CollectedJobPosting], DeduplicationStats]:
    """Combined dedup: source-level pass then cross-source pass.

    Kept for backward compatibility and standalone use.
    The pipeline calls dedup_source_level / dedup_cross_source separately so
    filtering and canonicalization can run between the two passes.
    """
    after_source, source_dups = dedup_source_level(postings)
    after_cross, cross_dups = dedup_cross_source(after_source)
    total = source_dups + cross_dups
    return after_cross, DeduplicationStats(
        unique_count=len(after_cross),
        duplicate_count=total,
        source_exact_count=source_dups,
        cross_source_count=cross_dups,
    )


def filter_postings(
    postings: list[CollectedJobPosting],
    collect_date: date,
    lookback_days: int,
) -> list[CollectedJobPosting]:
    """Filter out expired or out-of-range postings (backward-compat signature).

    Keeps postings with missing deadline or posted_at — absence of data is not
    a reason to discard.
    """
    earliest_posted = collect_date - timedelta(days=lookback_days)
    result = []

    for posting in postings:
        if posting.deadline is not None and posting.deadline < collect_date:
            continue
        if (
            posting.posted_at is not None
            and posting.posted_at.date() < earliest_posted
        ):
            continue
        result.append(posting)

    return result


def filter_postings_with_stats(
    postings: list[CollectedJobPosting],
    collect_date: date,
    options: CollectionOptions,
) -> tuple[list[CollectedJobPosting], int, int]:
    """Stage 5 filter — returns (active, expired_count, stale_count).

    collect_from is the effective start of the collection window:
    - explicit options.collect_from if set, otherwise
    - collect_date minus min(lookback_days, max_lookback_days).
    """
    from datetime import timedelta

    if options.collect_from is not None:
        collect_from = options.collect_from
    else:
        lookback = min(options.lookback_days, options.max_lookback_days)
        collect_from = collect_date - timedelta(days=lookback)

    active: list[CollectedJobPosting] = []
    expired_count = 0
    stale_count = 0

    for posting in postings:
        if posting.deadline is not None and posting.deadline < collect_date:
            expired_count += 1
            continue
        if (
            posting.posted_at is not None
            and posting.posted_at.date() < collect_from
        ):
            stale_count += 1
            continue
        active.append(posting)

    return active, expired_count, stale_count
