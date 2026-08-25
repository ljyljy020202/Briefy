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


class ScoreBreakdown(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    role_score: int = 0
    company_score: int = 0
    skill_score: int = 0
    experience_score: int = 0
    industry_score: int = 0
    location_score: int = 0
    employment_type_score: int = 0
    company_size_score: int = 0
    relevance_score: int = 0
    exposure_penalty: int = 0
    adjusted_score: int = 0


class MatchEvidence(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    matched_roles: list[str] = []
    matched_companies: list[str] = []
    matched_skills: list[str] = []
    matched_industries: list[str] = []
    matched_locations: list[str] = []
    matched_experience_levels: list[str] = []
    matched_employment_types: list[str] = []
    matched_company_sizes: list[str] = []


class CandidateJobPosting(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    id: int | None = None
    rank: int = 0
    source: str | None = None
    source_url: str | None = None
    company_name: str | None = None
    title: str | None = None
    employment_type: str | None = None
    experience_level: str | None = None
    location: str | None = None
    deadline: str | None = None
    skills: list[str] = []
    roles: list[str] = []
    description: str | None = None
    published_at: str | None = None
    collected_date: str | None = None
    is_new: bool = False
    is_urgent: bool = False
    score_breakdown: ScoreBreakdown = Field(default_factory=ScoreBreakdown)
    match_evidence: MatchEvidence = Field(default_factory=MatchEvidence)


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
