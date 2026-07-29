"""Pydantic serialization contract tests for collection schemas.

Verifies camelCase serialization of new optional fields and backward-compat
deserialization of old JSON that omits those fields.
"""

from datetime import date

import pytest
from pydantic import ValidationError

from app.schemas.collection import (
    CollectedJobPosting,
    CollectionOptions,
    CollectionStats,
    CompanyProfile,
    DailyCollectRequest,
    OfficialCompanySource,
    SourceRef,
)


def test_company_profile_serializes_to_camel_case():
    profile = CompanyProfile(
        id=1,
        canonical_name="네이버",
        normalized_name="naver",
        company_size="대기업",
        industry_codes=["IT/소프트웨어"],
    )
    data = profile.model_dump(by_alias=True)
    assert data["canonicalName"] == "네이버"
    assert data["normalizedName"] == "naver"
    assert data["companySize"] == "대기업"
    assert data["industryCodes"] == ["IT/소프트웨어"]
    assert "canonical_name" not in data


def test_official_company_source_serializes_to_camel_case():
    source = OfficialCompanySource(
        company_id=1,
        source_type="WANTED",
        adapter_type="WantedAdapter",
    )
    data = source.model_dump(by_alias=True)
    assert data["companyId"] == 1
    assert data["sourceType"] == "WANTED"
    assert data["adapterType"] == "WantedAdapter"
    assert data["configJson"] is None
    assert data["sourceUrl"] is None
    assert "company_id" not in data


def test_official_company_source_source_url_round_trips():
    source = OfficialCompanySource(
        company_id=42,
        source_type="CAREERS_PAGE",
        adapter_type="SITEMAP",
        source_url="https://careers.example.com/sitemap.xml",
    )
    data = source.model_dump(by_alias=True)
    assert data["sourceUrl"] == "https://careers.example.com/sitemap.xml"
    assert "source_url" not in data

    restored = OfficialCompanySource.model_validate(data)
    assert restored.source_url == "https://careers.example.com/sitemap.xml"


def test_official_company_source_no_source_url_backward_compat():
    old_json = {"companyId": 5, "sourceType": "FEED", "adapterType": "RSS"}
    source = OfficialCompanySource.model_validate(old_json)
    assert source.source_url is None


def test_source_ref_serializes_to_camel_case():
    ref = SourceRef(
        source="원티드",
        source_url="https://wanted.co.kr/wd/123",
        source_record_key="a" * 64,
    )
    data = ref.model_dump(by_alias=True)
    assert data["sourceUrl"] == "https://wanted.co.kr/wd/123"
    assert data["sourceRecordKey"] == "a" * 64
    assert data["sourceExternalId"] is None
    assert "source_url" not in data


def test_daily_collect_request_includes_company_profiles():
    profile = CompanyProfile(id=1, canonical_name="네이버", normalized_name="naver")
    official_source = OfficialCompanySource(company_id=1, source_type="WANTED")

    request = DailyCollectRequest(
        collect_date=date(2026, 7, 5),
        categories=["JOB_POSTING"],
        company_profiles=[profile],
        official_company_sources=[official_source],
    )
    data = request.model_dump(by_alias=True)
    assert len(data["companyProfiles"]) == 1
    assert data["companyProfiles"][0]["canonicalName"] == "네이버"
    assert len(data["officialCompanySources"]) == 1
    assert data["officialCompanySources"][0]["sourceType"] == "WANTED"


def test_daily_collect_request_old_json_backward_compat():
    old_json = {
        "collectDate": "2026-07-05",
        "categories": ["JOB_POSTING"],
        "seedKeywords": {"roles": ["백엔드 개발자"]},
    }
    request = DailyCollectRequest.model_validate(old_json)
    assert request.company_profiles == []
    assert request.official_company_sources == []


def test_collected_job_posting_with_source_fields():
    ref = SourceRef(source="원티드", source_url="https://wanted.co.kr/wd/123")
    posting = CollectedJobPosting(
        source="원티드",
        source_url="https://wanted.co.kr/wd/123",
        company_name="네이버",
        title="백엔드 개발자",
        position="백엔드 개발자",
        content_hash="a" * 64,
        source_external_id="ext-001",
        source_record_key="b" * 64,
        canonical_fingerprint="c" * 64,
        source_refs=[ref],
    )
    data = posting.model_dump(by_alias=True)
    assert data["sourceExternalId"] == "ext-001"
    assert data["sourceRecordKey"] == "b" * 64
    assert data["canonicalFingerprint"] == "c" * 64
    assert len(data["sourceRefs"]) == 1
    assert data["sourceRefs"][0]["sourceUrl"] == "https://wanted.co.kr/wd/123"


def test_collected_job_posting_old_json_backward_compat():
    old_json = {
        "source": "원티드",
        "sourceUrl": "https://wanted.co.kr/wd/456",
        "companyName": "카카오",
        "title": "프론트엔드 개발자",
        "position": "프론트엔드 개발자",
        "contentHash": "d" * 64,
    }
    posting = CollectedJobPosting.model_validate(old_json)
    assert posting.source_external_id is None
    assert posting.source_record_key is None
    assert posting.canonical_fingerprint is None
    assert posting.source_refs == []


def test_collected_job_posting_source_fields_not_included_when_none():
    posting = CollectedJobPosting(
        source="fixture",
        source_url="https://example.com/job/1",
        company_name="테스트 회사",
        title="테스트 개발자",
        position="테스트 개발자",
        content_hash="e" * 64,
    )
    assert posting.source_external_id is None
    assert posting.source_record_key is None
    assert posting.canonical_fingerprint is None
    assert posting.source_refs == []


# ── CollectionOptions strict schema ──────────────────────────────────────────


def test_collection_options_rejects_unknown_field():
    """maxItemsPerSource (old Java field) must now raise instead of silently passing."""
    with pytest.raises(ValidationError):
        CollectionOptions.model_validate({"maxItemsPerSource": 100})


def test_collection_options_rejects_dead_deadline_within_days():
    with pytest.raises(ValidationError):
        CollectionOptions.model_validate({"deadlineWithinDays": 14})


def test_collection_options_defaults_match_spring_defaults():
    opts = CollectionOptions()
    assert opts.lookback_days == 7
    assert opts.discovery_limit_per_source == 300
    assert opts.detail_fetch_limit_per_source == 100
    assert opts.max_results_per_source == 100
    assert opts.max_total_results == 500


def test_collection_options_accepts_valid_five_field_spring_request():
    payload = {
        "lookbackDays": 7,
        "discoveryLimitPerSource": 300,
        "detailFetchLimitPerSource": 100,
        "maxResultsPerSource": 100,
        "maxTotalResults": 500,
    }
    opts = CollectionOptions.model_validate(payload)
    assert opts.lookback_days == 7
    assert opts.discovery_limit_per_source == 300


def test_collection_stats_field_names():
    stats = CollectionStats(
        discovered_count=100,
        fetched_count=50,
        parsed_count=45,
        duplicate_count=5,
        filtered_count=2,
        truncated_count=10,
        final_count=28,
    )
    data = stats.model_dump(by_alias=True)
    assert data["discoveredCount"] == 100
    assert data["fetchedCount"] == 50
    assert data["parsedCount"] == 45
    assert data["duplicateCount"] == 5
    assert data["filteredCount"] == 2
    assert data["truncatedCount"] == 10
    assert data["finalCount"] == 28
    # Legacy aliases must no longer exist
    assert "collectedCount" not in data
    assert "deduplicatedCount" not in data
    assert "jobPostingCount" not in data


# ── JobPostingPreference companySizes/industries contract ─────────────────────
# Protects "ALREADY_FIXED: companySizes/industries preference 전달"
# These fields must survive camelCase serialization so the Backend can populate
# them from UserBriefingPreference and the Agent can read them in the fallback
# scoring path.


def test_job_posting_preference_company_sizes_and_industries_serialize_to_camel():
    """companySizes and industries survive round-trip through camelCase JSON."""
    from app.schemas.briefing import JobPostingPreference

    pref = JobPostingPreference(
        company_sizes=["대기업", "중견기업"],
        industries=["IT/소프트웨어", "핀테크"],
    )
    data = pref.model_dump(by_alias=True)
    assert "companySizes" in data, "companySizes must be present in camelCase output"
    assert "industries" in data, "industries must be present in camelCase output"
    assert data["companySizes"] == ["대기업", "중견기업"]
    assert data["industries"] == ["IT/소프트웨어", "핀테크"]
    assert "company_sizes" not in data
    assert "company_sizes" not in data


def test_job_posting_preference_company_sizes_defaults_to_empty_list():
    """Absent companySizes/industries fields default to [] (not None)."""
    from app.schemas.briefing import JobPostingPreference

    pref = JobPostingPreference()
    assert pref.company_sizes == []
    assert pref.industries == []


def test_job_posting_preference_camel_case_deserialization():
    """Backend-sent camelCase JSON with companySizes/industries is parsed correctly."""
    from app.schemas.briefing import JobPostingPreference

    raw = {
        "roles": ["백엔드 개발자"],
        "companySizes": ["스타트업"],
        "industries": ["헬스케어"],
    }
    pref = JobPostingPreference.model_validate(raw)
    assert pref.company_sizes == ["스타트업"]
    assert pref.industries == ["헬스케어"]
