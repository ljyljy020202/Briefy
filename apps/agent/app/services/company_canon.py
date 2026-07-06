"""Company canonicalization — Stage 6 of Pipeline V2.

Matches raw company names against the CompanyProfile registry provided in the
collection request and replaces them with canonical names.  Falls back to the
raw name unchanged when no profile matches.
"""

from app.schemas.collection import CollectedJobPosting, CompanyProfile
from app.utils.identifiers import normalize_company_name


def canonicalize_company(raw_name: str, profiles: list[CompanyProfile]) -> str:
    """Return canonical name for raw_name using the profile registry.

    Matching is done on the normalized form so casing / spacing / suffix
    variations all resolve to the same profile.
    """
    if not profiles:
        return raw_name

    normalized_raw = normalize_company_name(raw_name)

    for profile in profiles:
        if normalize_company_name(profile.canonical_name) == normalized_raw:
            return profile.canonical_name
        if profile.normalized_name == normalized_raw:
            return profile.canonical_name

    return raw_name


def apply_company_canonicalization(
    postings: list[CollectedJobPosting],
    profiles: list[CompanyProfile],
) -> list[CollectedJobPosting]:
    """Apply company canonicalization to a list of postings in place.

    Returns the same list if profiles is empty (fast path).
    """
    if not profiles:
        return postings

    result: list[CollectedJobPosting] = []
    for p in postings:
        canonical = canonicalize_company(p.company_name, profiles)
        if canonical != p.company_name:
            p = p.model_copy(update={"company_name": canonical})
        result.append(p)
    return result
