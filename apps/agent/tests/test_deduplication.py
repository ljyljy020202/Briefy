from datetime import date, datetime

from app.schemas.collection import CollectedJobPosting
from app.services.deduplication import (
    DeduplicationStats,
    deduplicate,
    filter_postings,
)

# ── helpers ───────────────────────────────────────────────────────────────────

_COLLECT_DATE = date(2026, 7, 2)
_FUTURE = date(2026, 7, 20)
_PAST = date(2026, 6, 30)


def _posting(
    *,
    source: str = "fixture",
    source_url: str = "https://fixture.local/jobs/00001",
    company_name: str = "네이버",
    title: str = "백엔드 개발자",
    deadline: date | None = _FUTURE,
    posted_at: datetime | None = None,
    content_hash: str | None = None,
    **kwargs,
) -> CollectedJobPosting:
    if content_hash is None:
        import hashlib

        deadline_str = deadline.isoformat() if deadline is not None else ""
        raw = f"{source}|{source_url}|{company_name}|{title}|{deadline_str}"
        content_hash = hashlib.sha256(raw.encode()).hexdigest()

    return CollectedJobPosting(
        source=source,
        source_url=source_url,
        company_name=company_name,
        title=title,
        position=kwargs.get("position", title),
        deadline=deadline,
        posted_at=posted_at,
        content_hash=content_hash,
    )


def _with_hash(h: str, **overrides) -> CollectedJobPosting:
    return _posting(content_hash=h, **overrides)


# ── deduplicate — empty / no-op ───────────────────────────────────────────────


def test_deduplicate_empty_list_returns_empty():
    unique, stats = deduplicate([])
    assert unique == []
    assert stats.unique_count == 0
    assert stats.duplicate_count == 0


def test_deduplicate_no_duplicates_returns_all():
    postings = [
        _posting(source_url="https://fixture.local/jobs/00001"),
        _posting(
            source_url="https://fixture.local/jobs/00002", title="프론트엔드 개발자"
        ),
        _posting(source_url="https://fixture.local/jobs/00003", company_name="카카오"),
    ]
    unique, stats = deduplicate(postings)
    assert len(unique) == 3
    assert stats.unique_count == 3
    assert stats.duplicate_count == 0


# ── level 1: source_url ───────────────────────────────────────────────────────


def test_deduplicate_source_url_duplicate_is_removed():
    url = "https://fixture.local/jobs/00001"
    postings = [_posting(source_url=url), _posting(source_url=url)]
    unique, stats = deduplicate(postings)
    assert len(unique) == 1
    assert stats.duplicate_count == 1


def test_deduplicate_source_url_keeps_first_occurrence():
    url = "https://fixture.local/jobs/00001"
    a = _posting(source_url=url, title="첫 번째")
    b = _posting(source_url=url, title="두 번째")
    unique, _ = deduplicate([a, b])
    assert unique[0].title == "첫 번째"


def test_deduplicate_different_source_urls_both_kept():
    a = _posting(source_url="https://fixture.local/jobs/00001")
    b = _posting(source_url="https://fixture.local/jobs/00002", title="다른 공고")
    unique, stats = deduplicate([a, b])
    assert len(unique) == 2
    assert stats.duplicate_count == 0


# ── level 2: content_hash ─────────────────────────────────────────────────────


def test_deduplicate_content_hash_duplicate_is_removed():
    fixed_hash = "a" * 64
    a = _with_hash(fixed_hash, source_url="https://a.com/1")
    b = _with_hash(fixed_hash, source_url="https://b.com/2")
    unique, stats = deduplicate([a, b])
    assert len(unique) == 1
    assert stats.duplicate_count == 1


def test_deduplicate_different_hashes_both_kept():
    # distinct URLs, distinct hashes, distinct titles → all three levels pass
    a = _with_hash("a" * 64, source_url="https://a.com/1", title="백엔드 개발자")
    b = _with_hash("b" * 64, source_url="https://b.com/2", title="프론트엔드 개발자")
    unique, stats = deduplicate([a, b])
    assert len(unique) == 2
    assert stats.duplicate_count == 0


# ── level 3: normalized triple ────────────────────────────────────────────────


def test_deduplicate_triple_duplicate_is_removed():
    a = _posting(
        source_url="https://a.com/1",
        company_name="네이버",
        title="백엔드 개발자",
        deadline=_FUTURE,
    )
    # same company/title/deadline but different URL and hash
    b = _posting(
        source_url="https://b.com/2",
        company_name="네이버",
        title="백엔드 개발자",
        deadline=_FUTURE,
    )
    unique, stats = deduplicate([a, b])
    assert len(unique) == 1
    assert stats.duplicate_count == 1


def test_deduplicate_triple_is_case_insensitive():
    a = _posting(
        source_url="https://a.com/1",
        company_name="Naver",
        title="Backend Engineer",
        deadline=_FUTURE,
    )
    b = _posting(
        source_url="https://b.com/2",
        company_name="naver",
        title="backend engineer",
        deadline=_FUTURE,
    )
    unique, stats = deduplicate([a, b])
    assert len(unique) == 1
    assert stats.duplicate_count == 1


def test_deduplicate_triple_is_whitespace_insensitive():
    a = _posting(
        source_url="https://a.com/1",
        company_name="네이버",
        title="백엔드  개발자",
        deadline=_FUTURE,
    )
    b = _posting(
        source_url="https://b.com/2",
        company_name=" 네이버 ",
        title="백엔드 개발자",
        deadline=_FUTURE,
    )
    unique, stats = deduplicate([a, b])
    assert len(unique) == 1
    assert stats.duplicate_count == 1


def test_deduplicate_triple_different_deadline_not_duplicate():
    a = _posting(
        source_url="https://a.com/1",
        company_name="네이버",
        title="백엔드 개발자",
        deadline=date(2026, 7, 10),
    )
    b = _posting(
        source_url="https://b.com/2",
        company_name="네이버",
        title="백엔드 개발자",
        deadline=date(2026, 8, 10),
    )
    unique, stats = deduplicate([a, b])
    assert len(unique) == 2
    assert stats.duplicate_count == 0


def test_deduplicate_triple_none_deadline_treated_consistently():
    a = _posting(source_url="https://a.com/1", deadline=None)
    b = _posting(source_url="https://b.com/2", deadline=None)
    unique, stats = deduplicate([a, b])
    assert len(unique) == 1
    assert stats.duplicate_count == 1


# ── stats ─────────────────────────────────────────────────────────────────────


def test_deduplicate_stats_type():
    _, stats = deduplicate([])
    assert isinstance(stats, DeduplicationStats)


def test_deduplicate_stats_sum_equals_input_count():
    postings = [
        _posting(source_url="https://fixture.local/jobs/00001"),
        _posting(source_url="https://fixture.local/jobs/00001"),  # dup
        _posting(source_url="https://fixture.local/jobs/00002", title="다른 공고"),
    ]
    unique, stats = deduplicate(postings)
    assert stats.unique_count + stats.duplicate_count == len(postings)


# ── filter_postings — deadline ────────────────────────────────────────────────


def test_filter_removes_past_deadline():
    p = _posting(deadline=_PAST)
    result = filter_postings([p], _COLLECT_DATE, lookback_days=3)
    assert result == []


def test_filter_keeps_future_deadline():
    p = _posting(deadline=_FUTURE)
    result = filter_postings([p], _COLLECT_DATE, lookback_days=3)
    assert len(result) == 1


def test_filter_keeps_deadline_equal_to_collect_date():
    p = _posting(deadline=_COLLECT_DATE)
    result = filter_postings([p], _COLLECT_DATE, lookback_days=3)
    assert len(result) == 1


def test_filter_keeps_none_deadline():
    p = _posting(deadline=None)
    result = filter_postings([p], _COLLECT_DATE, lookback_days=3)
    assert len(result) == 1


# ── filter_postings — posted_at ───────────────────────────────────────────────


def test_filter_removes_posted_at_older_than_lookback():
    old_dt = datetime(2026, 6, 25, 9, 0, 0)  # 7 days before 2026-07-02
    p = _posting(posted_at=old_dt)
    result = filter_postings([p], _COLLECT_DATE, lookback_days=3)
    assert result == []


def test_filter_keeps_posted_at_within_lookback():
    recent_dt = datetime(2026, 7, 1, 9, 0, 0)  # 1 day before collect_date
    p = _posting(posted_at=recent_dt)
    result = filter_postings([p], _COLLECT_DATE, lookback_days=3)
    assert len(result) == 1


def test_filter_keeps_none_posted_at():
    p = _posting(posted_at=None)
    result = filter_postings([p], _COLLECT_DATE, lookback_days=3)
    assert len(result) == 1


# ── filter_postings — missing optional fields ─────────────────────────────────


def test_filter_does_not_discard_posting_with_missing_optional_fields():
    p = CollectedJobPosting(
        source="fixture",
        source_url="https://fixture.local/jobs/99999",
        company_name="네이버",
        title="백엔드 개발자",
        position="백엔드 개발자",
        employment_type=None,
        experience_level=None,
        location=None,
        deadline=None,
        skills=[],
        roles=[],
        description=None,
        posted_at=None,
        content_hash="a" * 64,
    )
    result = filter_postings([p], _COLLECT_DATE, lookback_days=3)
    assert len(result) == 1
