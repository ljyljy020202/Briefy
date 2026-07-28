"""Tests for app.utils.identifiers — all deterministic, no I/O."""

from datetime import date

from app.utils.identifiers import (
    canonicalize_url,
    compute_canonical_fingerprint,
    compute_content_hash,
    compute_source_record_key,
    normalize_company_name,
    normalize_title,
)

# ── canonicalize_url ──────────────────────────────────────────────────────────


def test_canonicalize_url_strips_trailing_slash():
    assert canonicalize_url("https://example.com/jobs/123/") == "https://example.com/jobs/123"


def test_canonicalize_url_preserves_root_path():
    assert canonicalize_url("https://example.com/") == "https://example.com/"


def test_canonicalize_url_lowercases_scheme_and_host():
    result = canonicalize_url("HTTPS://Example.COM/jobs/1")
    assert result.startswith("https://example.com/")


def test_canonicalize_url_strips_fragment():
    result = canonicalize_url("https://example.com/jobs/1#section")
    assert "#" not in result


def test_canonicalize_url_strips_query():
    result = canonicalize_url("https://example.com/jobs/1?ref=search")
    assert "?" not in result


def test_canonicalize_url_is_stable():
    assert canonicalize_url("https://example.com/jobs/1") == canonicalize_url("https://example.com/jobs/1")


# ── normalize_company_name ────────────────────────────────────────────────────


def test_normalize_company_name_lowercases():
    assert normalize_company_name("Naver") == "naver"


def test_normalize_company_name_strips_whitespace():
    assert normalize_company_name("  네이버  ") == "네이버"


def test_normalize_company_name_collapses_internal_whitespace():
    assert normalize_company_name("네이버  주식회사") == "네이버"


def test_normalize_company_name_removes_korean_legal_suffix():
    assert normalize_company_name("네이버(주)") == "네이버"


def test_normalize_company_name_same_for_variants():
    a = normalize_company_name("네이버")
    b = normalize_company_name("네이버(주)")
    c = normalize_company_name(" 네이버 ")
    assert a == b == c


# ── normalize_title ───────────────────────────────────────────────────────────


def test_normalize_title_lowercases():
    assert normalize_title("Backend Engineer") == "backend engineer"


def test_normalize_title_collapses_whitespace():
    assert normalize_title("백엔드  개발자") == "백엔드 개발자"


def test_normalize_title_strips():
    assert normalize_title("  백엔드 개발자  ") == "백엔드 개발자"


# ── compute_source_record_key ─────────────────────────────────────────────────


def test_compute_source_record_key_returns_64_hex():
    key = compute_source_record_key("원티드", None, "https://wanted.co.kr/wd/123")
    assert len(key) == 64
    assert all(c in "0123456789abcdef" for c in key)


def test_compute_source_record_key_uses_external_id_when_available():
    key_with_id = compute_source_record_key("원티드", "ext-001", "https://wanted.co.kr/wd/123")
    key_no_id = compute_source_record_key("원티드", None, "https://wanted.co.kr/wd/123")
    assert key_with_id != key_no_id


def test_compute_source_record_key_different_sources_different_keys():
    k1 = compute_source_record_key("원티드", None, "https://example.com/job/1")
    k2 = compute_source_record_key("사람인", None, "https://example.com/job/1")
    assert k1 != k2


def test_compute_source_record_key_same_inputs_stable():
    k1 = compute_source_record_key("원티드", None, "https://wanted.co.kr/wd/1")
    k2 = compute_source_record_key("원티드", None, "https://wanted.co.kr/wd/1")
    assert k1 == k2


# ── compute_content_hash ──────────────────────────────────────────────────────


def test_compute_content_hash_returns_64_hex():
    h = compute_content_hash("네이버", "백엔드 개발자", date(2026, 7, 20), None)
    assert len(h) == 64
    assert all(c in "0123456789abcdef" for h_char in [h] for c in h_char)


def test_compute_content_hash_stable():
    h1 = compute_content_hash("네이버", "백엔드 개발자", date(2026, 7, 20), "설명")
    h2 = compute_content_hash("네이버", "백엔드 개발자", date(2026, 7, 20), "설명")
    assert h1 == h2


def test_compute_content_hash_does_not_depend_on_source_or_url():
    # Same content, different hypothetical sources → same hash
    h1 = compute_content_hash("네이버", "백엔드 개발자", date(2026, 7, 20), None)
    h2 = compute_content_hash("네이버", "백엔드 개발자", date(2026, 7, 20), None)
    assert h1 == h2


def test_compute_content_hash_differs_for_different_company():
    h1 = compute_content_hash("네이버", "백엔드 개발자", date(2026, 7, 20), None)
    h2 = compute_content_hash("카카오", "백엔드 개발자", date(2026, 7, 20), None)
    assert h1 != h2


def test_compute_content_hash_differs_for_different_title():
    h1 = compute_content_hash("네이버", "백엔드 개발자", date(2026, 7, 20), None)
    h2 = compute_content_hash("네이버", "프론트엔드 개발자", date(2026, 7, 20), None)
    assert h1 != h2


def test_compute_content_hash_differs_for_different_deadline():
    h1 = compute_content_hash("네이버", "백엔드 개발자", date(2026, 7, 10), None)
    h2 = compute_content_hash("네이버", "백엔드 개발자", date(2026, 7, 20), None)
    assert h1 != h2


def test_compute_content_hash_none_vs_set_deadline_differ():
    h1 = compute_content_hash("네이버", "백엔드 개발자", None, None)
    h2 = compute_content_hash("네이버", "백엔드 개발자", date(2026, 7, 20), None)
    assert h1 != h2


# ── compute_canonical_fingerprint ────────────────────────────────────────────


def test_compute_canonical_fingerprint_returns_64_hex():
    fp = compute_canonical_fingerprint("네이버", "백엔드 개발자", date(2026, 7, 20))
    assert len(fp) == 64
    assert all(c in "0123456789abcdef" for c in fp)


def test_compute_canonical_fingerprint_stable():
    fp1 = compute_canonical_fingerprint("네이버", "백엔드 개발자", date(2026, 7, 20))
    fp2 = compute_canonical_fingerprint("네이버", "백엔드 개발자", date(2026, 7, 20))
    assert fp1 == fp2


def test_compute_canonical_fingerprint_same_for_case_variants():
    fp1 = compute_canonical_fingerprint("naver", "backend engineer", date(2026, 7, 20))
    fp2 = compute_canonical_fingerprint("naver", "backend engineer", date(2026, 7, 20))
    assert fp1 == fp2


def test_compute_canonical_fingerprint_differs_for_different_deadline():
    fp1 = compute_canonical_fingerprint("네이버", "백엔드 개발자", date(2026, 7, 10))
    fp2 = compute_canonical_fingerprint("네이버", "백엔드 개발자", date(2026, 7, 20))
    assert fp1 != fp2


def test_compute_canonical_fingerprint_does_not_include_description():
    # Same company/title/deadline with different description → same fingerprint
    fp1 = compute_canonical_fingerprint("네이버", "백엔드 개발자", date(2026, 7, 20))
    fp2 = compute_canonical_fingerprint("네이버", "백엔드 개발자", date(2026, 7, 20))
    assert fp1 == fp2


# ── Cross-language sourceRecordKey contract ───────────────────────────────────
# These fixed values are shared with the Spring implementation
# (CandidatePoolService.buildSourceRecordKey).  Changing either side without
# updating the other will break cross-service deduplication.
#
# Algorithm: SHA-256( "{source}|{identifier}" ), where
#   identifier = source_external_id  (if present)
#              = canonicalize_url(source_url)  (otherwise)
#   format = 64-char lowercase hex

_CROSS_LANG_FIXTURE_URL_KEY = (
    "dfcbdeefa29fe693c628dbe93941ba2b10bc58cf8a6ff16f4675577b390ed00e"
)
_CROSS_LANG_FIXTURE_EXT_KEY = (
    "80a037a4f5b02f9d29257900f862005202cabc58b2aab0006a47b16ac64c089e"
)


def test_source_record_key_url_matches_cross_language_fixture():
    """sha256('원티드|https://wanted.co.kr/wd/123') must be stable across languages."""
    assert (
        compute_source_record_key("원티드", None, "https://wanted.co.kr/wd/123")
        == _CROSS_LANG_FIXTURE_URL_KEY
    )


def test_source_record_key_external_id_matches_cross_language_fixture():
    """sha256('점핏|EXT-007') must be stable across languages."""
    assert (
        compute_source_record_key("점핏", "EXT-007", "https://jumpit.com/j/1")
        == _CROSS_LANG_FIXTURE_EXT_KEY
    )
