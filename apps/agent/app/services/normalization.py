import re

from app.adapters.base import RawJobPosting
from app.schemas.collection import CollectedJobPosting, SourceRef
from app.utils.identifiers import (
    compute_canonical_fingerprint,
    compute_content_hash,
    compute_source_record_key,
    normalize_company_name,
    normalize_title,
)


def _clean(value: str | None) -> str | None:
    """Strip and collapse whitespace. Returns None for empty or None input."""
    if value is None:
        return None
    cleaned = re.sub(r"\s+", " ", value).strip()
    return cleaned if cleaned else None


def _clean_required(value: str) -> str:
    return re.sub(r"\s+", " ", value).strip()


def _clean_list(values: list[str]) -> list[str]:
    return [c for v in values if (c := (_clean(v) or ""))]


def normalize(raw: RawJobPosting) -> CollectedJobPosting:
    source = _clean_required(raw.source)
    source_url = _clean_required(raw.source_url)
    company_name = _clean_required(raw.company_name)
    title = _clean_required(raw.title)
    position = _clean(raw.position) or title
    description = _clean(raw.description)

    norm_company = normalize_company_name(company_name)
    norm_title = normalize_title(title)

    source_record_key = compute_source_record_key(
        source, raw.source_external_id, source_url
    )
    content_hash = compute_content_hash(
        norm_company, norm_title, raw.deadline, description
    )
    canonical_fingerprint = compute_canonical_fingerprint(
        norm_company, norm_title, raw.deadline
    )

    source_refs = [
        SourceRef(
            source=source,
            source_url=source_url,
            source_external_id=raw.source_external_id,
            source_record_key=source_record_key,
        )
    ]

    return CollectedJobPosting(
        source=source,
        source_url=source_url,
        company_name=company_name,
        title=title,
        position=position,
        employment_type=_clean(raw.employment_type),
        experience_level=_clean(raw.experience_level),
        location=_clean(raw.location),
        deadline=raw.deadline,
        skills=_clean_list(raw.skills),
        roles=_clean_list(raw.roles),
        description=description,
        posted_at=raw.posted_at,
        content_hash=content_hash,
        source_external_id=raw.source_external_id,
        source_record_key=source_record_key,
        canonical_fingerprint=canonical_fingerprint,
        source_refs=source_refs,
    )


def normalize_many(raws: list[RawJobPosting]) -> list[CollectedJobPosting]:
    return [normalize(raw) for raw in raws]
