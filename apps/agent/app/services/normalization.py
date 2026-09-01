import re

from app.adapters.base import RawJobPosting
from app.core.identifiers import (
    compute_canonical_fingerprint,
    compute_content_hash,
    compute_source_record_key,
    normalize_company_name,
    normalize_title,
)
from app.schemas.collection import CollectedJobPosting, SourceRef

# ── Internship keywords ───────────────────────────────────────────────────────

_INTERN_KEYWORDS: frozenset[str] = frozenset(
    {"인턴", "intern", "internship", "채용연계형"}
)
_NEW_GRAD_TITLE_KEYWORDS: frozenset[str] = frozenset(
    {"신입", "new grad", "졸업예정"}
)

# ── Role inference from title ─────────────────────────────────────────────────
# Ordered: more specific phrases before shorter keywords to avoid false matches.
_DEV_ROLE_TITLE_MAP: list[tuple[str, str]] = [
    ("데이터 엔지니어", "데이터 엔지니어"),
    ("data engineer", "데이터 엔지니어"),
    ("analytics engineer", "데이터 엔지니어"),
    ("서버 개발", "백엔드"),
    ("platform backend", "백엔드"),
    ("플랫폼 서버", "백엔드"),
    ("백엔드", "백엔드"),
    ("backend", "백엔드"),
    ("back-end", "백엔드"),
    ("server engineer", "백엔드"),
    ("프론트엔드", "프론트엔드"),
    ("frontend", "프론트엔드"),
    ("front-end", "프론트엔드"),
    ("풀스택", "풀스택"),
    ("fullstack", "풀스택"),
    ("full-stack", "풀스택"),
    ("ml engineer", "ML"),
    ("머신러닝", "ML"),
    ("machine learning", "ML"),
    ("인프라 엔지니어", "인프라"),
    ("infrastructure engineer", "인프라"),
    ("devops", "DevOps"),
    ("sre", "SRE"),
    ("android", "Android"),
    ("ios", "iOS"),
    ("모바일 개발", "모바일"),
]

# Non-dev title keywords — postings whose title contains these should NOT
# receive inferred dev roles, even when other dev keywords are present.
_NON_DEV_TITLE_KEYWORDS: frozenset[str] = frozenset(
    {
        "마케팅", "marketing",
        "영업", "sales",
        "인사 담당", "인사팀", "hr manager", "human resource",
        "재무", "finance",
        "회계", "accounting",
        "product manager", "서비스 기획",
        "콘텐츠 에디터", "content writer", "content editor",
        "법무", "legal",
        "graphic designer", "ui designer", "ux designer",
    }
)

# Experience extraction from description text
_EXP_REQUIRED_RE = re.compile(r"(\d+)\s*년\s*이상\s*(?:경력\s*)?필수")
_EXP_MIN_RE = re.compile(r"(\d+)\s*년\s*이상")


# ── Helper functions ──────────────────────────────────────────────────────────


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


def _has_intern_keyword(text: str) -> bool:
    t = text.lower()
    return any(kw in t for kw in _INTERN_KEYWORDS)


def _is_non_dev_title(title_lower: str) -> bool:
    return any(kw in title_lower for kw in _NON_DEV_TITLE_KEYWORDS)


def _infer_role_from_title(title: str) -> list[str]:
    """Infer canonical role names from job title when the adapter provides none.

    Returns an empty list for non-dev titles (e.g. "마케팅 인턴") so that
    marketing postings are never misclassified as developer roles.
    """
    title_lower = title.lower()
    if _is_non_dev_title(title_lower):
        return []
    inferred: list[str] = []
    for kw, canonical in _DEV_ROLE_TITLE_MAP:
        if kw in title_lower and canonical not in inferred:
            inferred.append(canonical)
    return inferred


def _extract_experience_from_description(description: str | None) -> str | None:
    """Extract an explicit minimum-year experience requirement from description text.

    Checks for explicit 'N년 이상 필수' patterns first, then 'N년 이상'.
    """
    if not description:
        return None
    m = _EXP_REQUIRED_RE.search(description)
    if m:
        return f"{m.group(1)}년 이상"
    m = _EXP_MIN_RE.search(description)
    if m:
        return f"{m.group(1)}년 이상"
    return None


def _infer_classification(
    title: str,
    cleaned_employment_type: str | None,
    cleaned_experience_level: str | None,
    cleaned_roles: list[str],
    description: str | None,
) -> tuple[list[str], str | None, str | None]:
    """Separate roles, experience_level, and employment_type from raw fields.

    Returns (roles, experience_level, employment_type).

    Rules:
    - "인턴" always goes to employment_type, not experience_level.
    - "신입 인턴" → experience_level="신입", employment_type="인턴".
    - "백엔드 인턴" → roles=["백엔드"], employment_type="인턴".
    - "마케팅 인턴" → roles=[], employment_type="인턴" (no dev role inferred).
    - Adapter-provided experience_level (non-intern) takes precedence over
      description-inferred values.
    - Explicit N년 이상 patterns in description are extracted when no adapter
      experience is available.
    """
    title_lower = title.lower()

    # ── employment_type ───────────────────────────────────────────────────────
    title_is_intern = any(kw in title_lower for kw in _INTERN_KEYWORDS)
    raw_type_is_intern = (
        cleaned_employment_type is not None
        and _has_intern_keyword(cleaned_employment_type)
    )

    if title_is_intern or raw_type_is_intern:
        employment_type = "인턴"
    else:
        employment_type = cleaned_employment_type  # preserve adapter value

    # ── experience_level ──────────────────────────────────────────────────────
    raw_exp_is_intern = (
        cleaned_experience_level is not None
        and _has_intern_keyword(cleaned_experience_level)
    )

    if cleaned_experience_level and not raw_exp_is_intern:
        # Adapter provided an explicit, non-intern experience value — use it.
        experience_level: str | None = cleaned_experience_level
    else:
        # Infer: title "신입" keywords take priority; fall back to description.
        if any(kw in title_lower for kw in _NEW_GRAD_TITLE_KEYWORDS):
            experience_level = "신입"
        else:
            experience_level = _extract_experience_from_description(description)

    # ── roles ─────────────────────────────────────────────────────────────────
    if cleaned_roles:
        roles = cleaned_roles  # adapter-provided roles always take precedence
    else:
        roles = _infer_role_from_title(title)

    return roles, experience_level, employment_type


# ── Public API ────────────────────────────────────────────────────────────────


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

    roles, experience_level, employment_type = _infer_classification(
        title=title,
        cleaned_employment_type=_clean(raw.employment_type),
        cleaned_experience_level=_clean(raw.experience_level),
        cleaned_roles=_clean_list(raw.roles),
        description=description,
    )

    return CollectedJobPosting(
        source=source,
        source_url=source_url,
        company_name=company_name,
        title=title,
        position=position,
        employment_type=employment_type,
        experience_level=experience_level,
        location=_clean(raw.location),
        deadline=raw.deadline,
        skills=_clean_list(raw.skills),
        roles=roles,
        description=description,
        posted_at=raw.posted_at,
        content_hash=content_hash,
        source_external_id=raw.source_external_id,
        source_record_key=source_record_key,
        canonical_fingerprint=canonical_fingerprint,
        source_refs=source_refs,
        description_truncated=raw.description_truncated,
    )


def normalize_many(raws: list[RawJobPosting]) -> list[CollectedJobPosting]:
    return [normalize(raw) for raw in raws]
