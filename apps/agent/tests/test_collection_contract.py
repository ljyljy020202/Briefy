"""Pydantic serialization contract tests for collection schemas.

Verifies camelCase serialization of new optional fields and backward-compat
deserialization of old JSON that omits those fields.
"""

from datetime import date

from app.schemas.collection import (
    CollectedJobPosting,
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
