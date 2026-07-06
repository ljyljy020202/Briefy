from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel


class JobPostingPreference(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    roles: list[str] = []
    companies: list[str] = []
    company_sizes: list[str] = []
    industries: list[str] = []
    skills: list[str] = []
    locations: list[str] = []
    experience_levels: list[str] = []
    employment_types: list[str] = []


class CandidateJobPosting(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    id: int | None = None
    source: str | None = None
    source_url: str | None = None
    company_name: str | None = None
    title: str | None = None
    position: str | None = None
    employment_type: str | None = None
    experience_level: str | None = None
    location: str | None = None
    deadline: str | None = None
    skills: list[str] = []
    roles: list[str] = []
    description: str | None = None
    posted_at: str | None = None
    collected_date: str | None = None
    content_hash: str | None = None
    pre_score: int = 0


class CandidatePool(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    job_postings: list[CandidateJobPosting] = []
    company_issues: list = []
    industry_issues: list = []


class BriefingGenerateRequest(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    user_id: int
    category: str
    preference: JobPostingPreference
    briefing_date: str
    tone: str = "easy"
    candidate_pool: CandidatePool = Field(default_factory=CandidatePool)


class JobArticle(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    title: str
    source: str | None = None
    url: str | None = None
    summary: str | None = None
    why_it_matters: str | None = None
    published_at: str | None = None
    company_name: str | None = None


class TokenUsage(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    input_tokens: int = 0
    output_tokens: int = 0


class BriefingGenerateResponse(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    title: str
    summary: str
    content: str
    articles: list[JobArticle]
    token_usage: TokenUsage = Field(default_factory=TokenUsage)
