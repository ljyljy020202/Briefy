from datetime import date, datetime

from pydantic import BaseModel, ConfigDict, Field, computed_field
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
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    lookback_days: int = 3
    deadline_within_days: int = 14

    # Budget controls (replace the single max_items_per_source)
    discovery_limit_per_source: int = 50
    detail_fetch_limit_per_source: int = 50
    max_results_per_source: int = 50
    max_total_results: int = 200

    # Collection window
    collect_from: date | None = None
    max_lookback_days: int = 30


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

    # V2 fields
    discovered: int = 0
    fetched: int = 0
    parsed: int = 0
    exact_duplicates: int = 0
    cross_source_merged: int = 0
    expired_filtered: int = 0
    stale_filtered: int = 0
    truncated: int = 0
    final: int = 0

    # Backward-compat aliases (also appear in serialized JSON as camelCase)
    @computed_field
    @property
    def collected_count(self) -> int:
        return self.discovered

    @computed_field
    @property
    def deduplicated_count(self) -> int:
        return self.exact_duplicates

    @computed_field
    @property
    def job_posting_count(self) -> int:
        return self.final


class DailyCollectResponse(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    collection_job_id: int | None = None
    collect_date: date
    job_postings: list[CollectedJobPosting] = []
    company_issues: list = []
    industry_issues: list = []
    stats: CollectionStats = Field(default_factory=CollectionStats)
    warnings: list[str] = []
