from dataclasses import dataclass
from datetime import date, timedelta

from app.schemas.collection import CollectedJobPosting, CollectionOptions, SourceRef
from app.utils.identifiers import (
    compute_canonical_fingerprint,
    normalize_company_name,
    normalize_title,
)

# Postings whose deadline falls within this window are kept even when posted_at
# is older than lookback_days.  Not configurable from Spring — agent-internal only.
_DEADLINE_RESCUE_DAYS = 14


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


def _is_stale(
    posting: CollectedJobPosting,
    collect_date: date,
    earliest_posted: date,
    deadline_horizon: date,
) -> bool:
    """Return True if the posting should be treated as stale.

    A posting is stale only when ALL of the following hold:
    - posted_at is known (not None)
    - posted_at is older than earliest_posted
    - deadline is either unknown or beyond the deadline rescue window

    Absence of posted_at is NOT sufficient reason to discard a posting.
    A soon-expiring deadline rescues an otherwise old posting.
    """
    posted_date = posting.posted_at.date() if posting.posted_at else None
    if posted_date is None:
        return False  # unknown age — keep

    if posted_date >= earliest_posted:
        return False  # recently posted — keep

    # posted_at is old; check if deadline rescues it
    if posting.deadline is not None and posting.deadline <= deadline_horizon:
        return False  # deadline is soon — keep despite old posted_at

    return True


def filter_postings(
    postings: list[CollectedJobPosting],
    collect_date: date,
    lookback_days: int,
    deadline_within_days: int = 0,
) -> list[CollectedJobPosting]:
    """Filter out expired or stale postings (backward-compat signature).

    ``deadline_within_days=0`` disables the deadline-rescue window, preserving
    the original behaviour for callers that don't need it.
    Absence of posted_at or deadline is never a reason to discard.
    """
    earliest_posted = collect_date - timedelta(days=lookback_days)
    deadline_horizon = collect_date + timedelta(days=deadline_within_days)
    result = []

    for posting in postings:
        if posting.deadline is not None and posting.deadline < collect_date:
            continue
        if _is_stale(posting, collect_date, earliest_posted, deadline_horizon):
            continue
        result.append(posting)

    return result


def filter_postings_with_stats(
    postings: list[CollectedJobPosting],
    collect_date: date,
    options: CollectionOptions,
) -> tuple[list[CollectedJobPosting], int, int]:
    """Stage 5 filter — returns (active, expired_count, stale_count).

    Stale policy:
    - A posting is kept when posted_at is within lookback_days.
    - A posting is also kept when its deadline falls within deadline_within_days,
      even if posted_at is older than lookback_days.  This prevents dropping
      postings that are still actively closing.
    - A posting with unknown posted_at (None) is always kept.
    - Expired postings (deadline < collect_date) are always dropped.

    Note: ``lookback_days`` here controls the *collection* candidate window and
    is independent of the briefing candidate window on the Backend side.
    """
    earliest_posted = collect_date - timedelta(days=options.lookback_days)
    deadline_horizon = collect_date + timedelta(days=_DEADLINE_RESCUE_DAYS)

    active: list[CollectedJobPosting] = []
    expired_count = 0
    stale_count = 0

    for posting in postings:
        if posting.deadline is not None and posting.deadline < collect_date:
            expired_count += 1
            continue
        if _is_stale(posting, collect_date, earliest_posted, deadline_horizon):
            stale_count += 1
            continue
        active.append(posting)

    return active, expired_count, stale_count
