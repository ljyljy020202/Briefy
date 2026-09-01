"""Live smoke tests for Greeting-powered career sites.

These tests make REAL HTTP requests to external sites.
Run with:
    poetry run pytest tests/adapters/test_live_sites.py -v -m external

They are excluded from the default test run:
    poetry run pytest tests/ --ignore=tests/adapters/test_live_sites.py
"""

from __future__ import annotations

from datetime import date

import pytest

from app.adapters.official.greeting import (
    GreetingParser,
    _extract_next_data_openings,
    _postings_from_next_data,
)
from app.schemas.collection import (
    CollectionOptions,
    CompanyProfile,
    OfficialCompanySource,
)

# ── Site definitions ──────────────────────────────────────────────────────────

_SITES = [
    {
        "company": "무신사",
        "company_id": 1001,
        "source_url": "https://www.musinsacareers.com/ko/home",
    },
    {
        "company": "현대오토에버",
        "company_id": 1002,
        "source_url": "https://career.hyundai-autoever.com/ko/apply",
    },
    {
        "company": "CJ올리브영",
        "company_id": 1003,
        "source_url": "https://oliveyoung.career.greetinghr.com/ko/apply",
    },
]


# ── Helpers ───────────────────────────────────────────────────────────────────


def _make_source(site: dict) -> OfficialCompanySource:
    return OfficialCompanySource(
        company_id=site["company_id"],
        source_type="custom",
        source_url=site["source_url"],
        config_json='{"max_discover": 10, "max_fetch": 3}',
    )


def _make_profile(site: dict) -> CompanyProfile:
    return CompanyProfile(
        id=site["company_id"],
        canonical_name=site["company"],
        normalized_name=site["company"],
    )


# ── Live tests ────────────────────────────────────────────────────────────────


@pytest.mark.external
@pytest.mark.parametrize("site", _SITES, ids=[s["company"] for s in _SITES])
async def test_greeting_live_smoke(site: dict) -> None:
    """Fetch up to 3 postings from each Greeting career site and verify metadata.

    Assertions:
    - at least 1 posting returned
    - all postings have non-empty title
    - roles field is populated (not empty list)
    - experience_level is not None
    - employment_type is not None
    - location is not None
    """
    parser = GreetingParser()
    source = _make_source(site)
    profile = _make_profile(site)
    options = CollectionOptions()
    collect_date = date.today()

    result = await parser.fetch(source, profile, options, collect_date)

    # Report warnings to stdout for visibility
    if result.warnings:
        for w in result.warnings:
            print(f"  WARN [{site['company']}]: {w}")

    postings = result.postings
    stats = result.source_stats
    discovered = stats.discovered if stats else "?"
    print(f"\n[{site['company']}] discovered={discovered}, parsed={len(postings)}")

    assert len(postings) > 0, (
        f"{site['company']}: got 0 postings — site may be unreachable or "
        f"__NEXT_DATA__ structure changed. Warnings: {result.warnings}"
    )

    for p in postings:
        print(
            f"  - {p.title!r}"
            f" | roles={p.roles}"
            f" | exp={p.experience_level!r}"
            f" | emp={p.employment_type!r}"
            f" | loc={p.location!r}"
            f" | url={p.source_url}"
        )

    # At least the first posting should have structured metadata
    first = postings[0]
    assert (
        isinstance(first.title, str) and first.title.strip()
    ), f"{site['company']}: first posting title is empty"
    assert first.roles, (
        f"{site['company']}: first posting has no roles — "
        f"title={first.title!r}, url={first.source_url}"
    )
    assert first.experience_level is not None, (
        f"{site['company']}: first posting has no experience_level — "
        f"title={first.title!r}"
    )
    assert first.employment_type is not None, (
        f"{site['company']}: first posting has no employment_type — "
        f"title={first.title!r}"
    )
    assert first.location is not None, (
        f"{site['company']}: first posting has no location — " f"title={first.title!r}"
    )


@pytest.mark.external
@pytest.mark.parametrize("site", _SITES, ids=[s["company"] for s in _SITES])
async def test_greeting_live_metadata_fill_rate(site: dict) -> None:
    """Measure metadata fill rates across all discovered postings (up to 10).

    Prints a fill-rate table per company. Does not assert on fill rate
    directly, but asserts that at least one posting is returned.
    """
    import httpx

    source_url = site["source_url"]
    # Use internal functions directly (no detail fetch needed for fill-rate check)
    async with httpx.AsyncClient(
        timeout=30,
        headers={"User-Agent": "Briefy-Agent/1.0 (+https://briefy.io)"},
        follow_redirects=True,
    ) as client:
        resp = await client.get(source_url)
        resp.raise_for_status()
        html = resp.text

    from app.adapters.official.greeting import _GreetingConfig

    openings = _extract_next_data_openings(html)
    assert openings is not None, (
        f"{site['company']}: __NEXT_DATA__ openings not found — "
        "site may not use Greeting or SSR structure changed"
    )

    config = _GreetingConfig(max_discover=50, max_fetch=0)
    stubs, warnings = _postings_from_next_data(
        openings, source_url, site["company"], "smoke", config
    )

    total = len(stubs)
    assert total > 0, f"{site['company']}: 0 openings in __NEXT_DATA__"

    roles_filled = sum(1 for s in stubs if s.roles)
    exp_filled = sum(1 for s in stubs if s.experience_level)
    emp_filled = sum(1 for s in stubs if s.employment_type)
    loc_filled = sum(1 for s in stubs if s.location)

    print(
        f"\n[{site['company']}] total={total}"
        f" roles={roles_filled}/{total} ({100*roles_filled//total}%)"
        f" exp={exp_filled}/{total} ({100*exp_filled//total}%)"
        f" emp={emp_filled}/{total} ({100*emp_filled//total}%)"
        f" loc={loc_filled}/{total} ({100*loc_filled//total}%)"
    )
    if warnings:
        for w in warnings:
            print(f"  WARN: {w}")
