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
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    lookback_days: int = 3
    deadline_within_days: int = 14
    max_items_per_source: int = 50


class DailyCollectRequest(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    collection_job_id: int | None = None
    collect_date: date
    categories: list[str]
    seed_keywords: SeedKeywords = Field(default_factory=SeedKeywords)
    options: CollectionOptions = Field(default_factory=CollectionOptions)


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


class CollectionStats(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    collected_count: int = 0
    deduplicated_count: int = 0
    job_posting_count: int = 0
    company_issue_count: int = 0
    industry_issue_count: int = 0


class DailyCollectResponse(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    collection_job_id: int | None = None
    collect_date: date
    job_postings: list[CollectedJobPosting] = []
    company_issues: list = []
    industry_issues: list = []
    stats: CollectionStats = Field(default_factory=CollectionStats)
    warnings: list[str] = []
