from datetime import date, datetime

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel


class SeedKeywords(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    roles: list[str] = []
    companies: list[str] = []
    company_sizes: list[str] = []
    industries: list[str] = []
    skills: list[str] = []
    locations: list[str] = []
    experience_levels: list[str] = []
    employment_types: list[str] = []
    keywords: list[str] = []


class CollectionOptions(BaseModel):
    # extra="forbid" raises on unknown fields instead of silently ignoring them
    model_config = ConfigDict(
        alias_generator=to_camel, populate_by_name=True, extra="forbid"
    )

    lookback_days: int = 7
    discovery_limit_per_source: int = 300
    detail_fetch_limit_per_source: int = 60
    max_results_per_source: int = 100
    max_total_results: int = 500


class CompanyProfile(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    id: int
    canonical_name: str
    normalized_name: str
    company_size: str | None = None
    industry_codes: list[str] = []


class OfficialCompanySource(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    company_id: int
    source_type: str
    source_url: str | None = None
    adapter_type: str | None = None
    config_json: str | None = None


class DailyCollectRequest(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    collection_job_id: int | None = None
    collect_date: date
    categories: list[str]
    seed_keywords: SeedKeywords = Field(default_factory=SeedKeywords)
    options: CollectionOptions = Field(default_factory=CollectionOptions)
    company_profiles: list[CompanyProfile] = []
    official_company_sources: list[OfficialCompanySource] = []


class SourceRef(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    source: str
    source_external_id: str | None = None
    source_url: str
    source_record_key: str | None = None


class CollectedJobPosting(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    source: str
    source_url: str
    company_name: str
    title: str
    position: str
    employment_type: str | None = None
    experience_level: str | None = None
    location: str | None = None
    deadline: date | None = None
    skills: list[str] = []
    roles: list[str] = []
    description: str | None = None
    posted_at: datetime | None = None
    content_hash: str
    source_external_id: str | None = None
    source_record_key: str | None = None
    canonical_fingerprint: str | None = None
    source_refs: list[SourceRef] = []


class CollectionStats(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    discovered_count: int = 0   # URLs/records discovered before fetch-budget
    fetched_count: int = 0      # actual detail-page/API fetch attempts
    parsed_count: int = 0       # pages that yielded a valid posting (pre-service-dedup)
    duplicate_count: int = 0    # source-level + cross-source duplicates removed
    filtered_count: int = 0     # expired + stale postings removed
    truncated_count: int = 0    # valid candidates dropped by global budget cap
    final_count: int = 0        # postings in jobPostings array


class DailyCollectResponse(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    collection_job_id: int | None = None
    collect_date: date
    job_postings: list[CollectedJobPosting] = []
    company_issues: list = []
    industry_issues: list = []
    stats: CollectionStats = Field(default_factory=CollectionStats)
    warnings: list[str] = []
