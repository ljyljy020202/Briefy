import re
from dataclasses import dataclass
from datetime import date, timedelta

from app.schemas.collection import CollectedJobPosting


@dataclass
class DeduplicationStats:
    unique_count: int = 0
    duplicate_count: int = 0


def _key(value: str) -> str:
    """Lowercase + collapse whitespace for case/space-insensitive comparison."""
    return re.sub(r"\s+", " ", value).strip().lower()


def deduplicate(
    postings: list[CollectedJobPosting],
) -> tuple[list[CollectedJobPosting], DeduplicationStats]:
    """Remove duplicates in three passes: source_url → content_hash → triple.

    Returns the deduplicated list and a stats object with counts.
    The first occurrence of each posting is kept.
    """
    seen_urls: set[str] = set()
    seen_hashes: set[str] = set()
    seen_triples: set[tuple[str, str, str]] = set()

    unique: list[CollectedJobPosting] = []
    dup_count = 0

    for posting in postings:
        if posting.source_url in seen_urls:
            dup_count += 1
            continue

        if posting.content_hash in seen_hashes:
            dup_count += 1
            continue

        triple = (
            _key(posting.company_name),
            _key(posting.title),
            posting.deadline.isoformat() if posting.deadline is not None else "",
        )
        if triple in seen_triples:
            dup_count += 1
            continue

        seen_urls.add(posting.source_url)
        seen_hashes.add(posting.content_hash)
        seen_triples.add(triple)
        unique.append(posting)

    return unique, DeduplicationStats(
        unique_count=len(unique), duplicate_count=dup_count
    )


def filter_postings(
    postings: list[CollectedJobPosting],
    collect_date: date,
    lookback_days: int,
) -> list[CollectedJobPosting]:
    """Filter out expired or out-of-range postings.

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
