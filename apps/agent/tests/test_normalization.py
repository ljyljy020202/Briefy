from datetime import date, datetime

from app.adapters.base import RawJobPosting
from app.schemas.collection import CollectedJobPosting
from app.services.normalization import normalize, normalize_many

# ── helpers ───────────────────────────────────────────────────────────────────

_BASE_DATE = date(2026, 7, 2)


def _raw(**overrides) -> RawJobPosting:
    defaults = dict(
        source="fixture",
        source_url="https://fixture.local/jobs/00001",
        company_name="네이버",
        title="백엔드 개발자",
        position="백엔드 개발자",
        deadline=_BASE_DATE,
    )
    defaults.update(overrides)
    return RawJobPosting(**defaults)


# ── string trimming ───────────────────────────────────────────────────────────


def test_normalize_strips_leading_trailing_whitespace():
    result = normalize(_raw(title="  백엔드 개발자  ", company_name="  네이버  "))
    assert result.title == "백엔드 개발자"
    assert result.company_name == "네이버"


def test_normalize_collapses_internal_whitespace():
    result = normalize(_raw(title="백엔드  개발자"))
    assert result.title == "백엔드 개발자"


def test_normalize_trims_optional_string_fields():
    result = normalize(_raw(
        employment_type="  정규직  ",
        experience_level="  신입  ",
        location="  서울  ",
        description="  채용합니다.  ",
    ))
    assert result.employment_type == "정규직"
    assert result.experience_level == "신입"
    assert result.location == "서울"
    assert result.description == "채용합니다."


# ── missing optional fields ───────────────────────────────────────────────────


def test_normalize_none_optional_string_fields_stay_none():
    result = normalize(_raw(
        employment_type=None,
        experience_level=None,
        location=None,
        description=None,
    ))
    assert result.employment_type is None
    assert result.experience_level is None
    assert result.location is None
    assert result.description is None


def test_normalize_whitespace_only_optional_field_becomes_none():
    result = normalize(_raw(location="   "))
    assert result.location is None


def test_normalize_empty_skills_stay_empty():
    result = normalize(_raw(skills=[]))
    assert result.skills == []


def test_normalize_empty_roles_stay_empty():
    result = normalize(_raw(roles=[]))
    assert result.roles == []


def test_normalize_none_deadline_stays_none():
    result = normalize(_raw(deadline=None))
    assert result.deadline is None


def test_normalize_none_posted_at_stays_none():
    result = normalize(_raw(posted_at=None))
    assert result.posted_at is None


# ── position fallback ─────────────────────────────────────────────────────────


def test_normalize_position_preserved_when_present():
    result = normalize(_raw(position="시니어 엔지니어"))
    assert result.position == "시니어 엔지니어"


def test_normalize_position_falls_back_to_title_when_none():
    result = normalize(_raw(title="백엔드 개발자", position=None))
    assert result.position == "백엔드 개발자"


def test_normalize_position_whitespace_only_falls_back_to_title():
    result = normalize(_raw(title="백엔드 개발자", position="   "))
    assert result.position == "백엔드 개발자"


# ── skills and roles ──────────────────────────────────────────────────────────


def test_normalize_skills_are_trimmed():
    result = normalize(_raw(skills=["  Java  ", "  Spring Boot  "]))
    assert result.skills == ["Java", "Spring Boot"]


def test_normalize_empty_skill_strings_are_removed():
    result = normalize(_raw(skills=["Java", "  ", ""]))
    assert result.skills == ["Java"]


def test_normalize_roles_are_trimmed():
    result = normalize(_raw(roles=["  백엔드 개발자  "]))
    assert result.roles == ["백엔드 개발자"]


# ── field preservation ────────────────────────────────────────────────────────


def test_normalize_source_is_preserved():
    result = normalize(_raw(source="jasoseol"))
    assert result.source == "jasoseol"


def test_normalize_source_url_is_preserved():
    result = normalize(_raw(source_url="https://jasoseol.com/recruit/12345"))
    assert result.source_url == "https://jasoseol.com/recruit/12345"


def test_normalize_deadline_is_preserved():
    d = date(2026, 8, 1)
    result = normalize(_raw(deadline=d))
    assert result.deadline == d


def test_normalize_posted_at_is_preserved():
    dt = datetime(2026, 7, 1, 9, 0, 0)
    result = normalize(_raw(posted_at=dt))
    assert result.posted_at == dt


# ── content_hash ──────────────────────────────────────────────────────────────


def test_normalize_content_hash_is_64_hex_chars():
    result = normalize(_raw())
    assert len(result.content_hash) == 64
    assert all(c in "0123456789abcdef" for c in result.content_hash)


def test_normalize_content_hash_is_stable():
    r1 = normalize(_raw())
    r2 = normalize(_raw())
    assert r1.content_hash == r2.content_hash


def test_normalize_content_hash_same_for_same_content_different_urls():
    # content_hash no longer includes source URL — same content → same hash
    r1 = normalize(_raw(source_url="https://fixture.local/jobs/00001"))
    r2 = normalize(_raw(source_url="https://fixture.local/jobs/00002"))
    assert r1.content_hash == r2.content_hash


def test_normalize_source_record_key_differs_for_different_source_url():
    r1 = normalize(_raw(source_url="https://fixture.local/jobs/00001"))
    r2 = normalize(_raw(source_url="https://fixture.local/jobs/00002"))
    assert r1.source_record_key != r2.source_record_key


def test_normalize_content_hash_differs_for_different_company():
    r1 = normalize(_raw(company_name="네이버"))
    r2 = normalize(_raw(company_name="카카오"))
    assert r1.content_hash != r2.content_hash


def test_normalize_content_hash_differs_for_different_title():
    r1 = normalize(_raw(title="백엔드 개발자"))
    r2 = normalize(_raw(title="프론트엔드 개발자"))
    assert r1.content_hash != r2.content_hash


def test_normalize_content_hash_differs_for_different_deadline():
    r1 = normalize(_raw(deadline=date(2026, 7, 10)))
    r2 = normalize(_raw(deadline=date(2026, 7, 20)))
    assert r1.content_hash != r2.content_hash


def test_normalize_content_hash_differs_for_none_vs_set_deadline():
    r1 = normalize(_raw(deadline=None))
    r2 = normalize(_raw(deadline=date(2026, 7, 10)))
    assert r1.content_hash != r2.content_hash


def test_normalize_content_hash_same_for_same_content_different_sources():
    # content_hash no longer includes source — same content → same hash
    r1 = normalize(_raw(source="wanted"))
    r2 = normalize(_raw(source="jasoseol"))
    assert r1.content_hash == r2.content_hash


def test_normalize_source_record_key_differs_for_different_source():
    r1 = normalize(_raw(source="wanted"))
    r2 = normalize(_raw(source="jasoseol"))
    assert r1.source_record_key != r2.source_record_key


def test_normalize_canonical_fingerprint_is_64_hex_chars():
    result = normalize(_raw())
    assert result.canonical_fingerprint is not None
    assert len(result.canonical_fingerprint) == 64
    assert all(c in "0123456789abcdef" for c in result.canonical_fingerprint)


def test_normalize_source_refs_has_one_entry():
    result = normalize(_raw())
    assert len(result.source_refs) == 1
    assert result.source_refs[0].source == "fixture"
    assert result.source_refs[0].source_url == "https://fixture.local/jobs/00001"
    assert result.source_refs[0].source_record_key == result.source_record_key


# ── output type ───────────────────────────────────────────────────────────────


def test_normalize_returns_collected_job_posting():
    result = normalize(_raw())
    assert isinstance(result, CollectedJobPosting)


def test_normalize_many_returns_list_of_collected_job_postings():
    raws = [_raw(source_url=f"https://fixture.local/jobs/{i:05d}") for i in range(3)]
    results = normalize_many(raws)
    assert len(results) == 3
    assert all(isinstance(r, CollectedJobPosting) for r in results)
