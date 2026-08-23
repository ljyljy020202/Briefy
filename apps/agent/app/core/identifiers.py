"""Deterministic identity and hashing utilities for Pipeline V2.

All functions are pure and stable: same inputs always produce the same output.
None of them call LLMs or external services.
"""

import hashlib
import re
from datetime import date
from urllib.parse import urlparse, urlunparse


def canonicalize_url(url: str) -> str:
    """Normalise a URL for identity comparison.

    Lowercases scheme+host, strips trailing slash, drops query and fragment.
    """
    s = url.strip()
    parsed = urlparse(s)
    scheme = parsed.scheme.lower()
    netloc = parsed.netloc.lower()
    path = parsed.path.rstrip("/") or "/"
    # drop query and fragment — identity is path-based
    return urlunparse((scheme, netloc, path, "", "", ""))


def normalize_company_name(name: str) -> str:
    """Return a stable, comparable form of a company name.

    Lowercases, collapses whitespace, strips common Korean and English legal
    suffixes so "네이버(주)" and "네이버" resolve to the same string.
    """
    normalized = re.sub(r"\s+", " ", name).strip().lower()
    _SUFFIXES = [
        "(주)", "주식회사",
        " co.", " co,", " corp.", " corp,",
        " inc.", " inc,", " ltd.", " ltd,",
    ]
    for suffix in _SUFFIXES:
        normalized = normalized.replace(suffix, "")
    return normalized.strip()


def normalize_title(title: str) -> str:
    """Return a stable, comparable form of a job title."""
    return re.sub(r"\s+", " ", title).strip().lower()


def compute_source_record_key(
    source: str,
    source_external_id: str | None,
    source_url: str,
) -> str:
    """Stable identity key for a single source record (64-char hex).

    Uses source_external_id when available (board-assigned stable ID).
    Falls back to the canonical form of the source URL.
    """
    identifier = (
        source_external_id if source_external_id else canonicalize_url(source_url)
    )
    raw = f"{source}|{identifier}"
    return hashlib.sha256(raw.encode()).hexdigest()


def compute_content_hash(
    normalized_company: str,
    normalized_title: str,
    deadline: date | None,
    description: str | None,
) -> str:
    """Content-change detection hash (64-char hex).

    Does NOT include source or source URL — the same posting collected from
    two sources must produce the same content hash so changes can be detected
    regardless of which source reported them.
    """
    deadline_str = deadline.isoformat() if deadline is not None else ""
    # Cap description contribution so minor edits don't invalidate the hash
    desc_str = (description or "").strip()[:500]
    raw = f"{normalized_company}|{normalized_title}|{deadline_str}|{desc_str}"
    return hashlib.sha256(raw.encode()).hexdigest()


def compute_canonical_fingerprint(
    normalized_company: str,
    normalized_title: str,
    deadline: date | None,
) -> str:
    """Cross-source same-posting candidate identity (64-char hex).

    Two postings from different sources are candidate duplicates when their
    canonical fingerprint matches.  Description is intentionally excluded so
    minor wording differences across boards don't prevent merging.
    """
    deadline_str = deadline.isoformat() if deadline is not None else ""
    raw = f"{normalized_company}|{normalized_title}|{deadline_str}"
    return hashlib.sha256(raw.encode()).hexdigest()
