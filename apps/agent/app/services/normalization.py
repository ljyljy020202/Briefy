import hashlib
import re
from datetime import date

from app.adapters.base import RawJobPosting
from app.schemas.collection import CollectedJobPosting


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


def _compute_content_hash(
    source: str,
    source_url: str,
    company_name: str,
    title: str,
    deadline: date | None,
) -> str:
    deadline_str = deadline.isoformat() if deadline is not None else ""
    raw = f"{source}|{source_url}|{company_name}|{title}|{deadline_str}"
    return hashlib.sha256(raw.encode()).hexdigest()


def normalize(raw: RawJobPosting) -> CollectedJobPosting:
    source = _clean_required(raw.source)
    source_url = _clean_required(raw.source_url)
    company_name = _clean_required(raw.company_name)
    title = _clean_required(raw.title)
    position = _clean(raw.position) or title

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
        description=_clean(raw.description),
        posted_at=raw.posted_at,
        content_hash=_compute_content_hash(
            source, source_url, company_name, title, raw.deadline
        ),
    )


def normalize_many(raws: list[RawJobPosting]) -> list[CollectedJobPosting]:
    return [normalize(raw) for raw in raws]
