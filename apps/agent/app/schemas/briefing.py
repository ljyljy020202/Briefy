from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel


class JobPostingPreference(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    roles: list[str] = []
    companies: list[str] = []
    skills: list[str] = []
    locations: list[str] = []
    experience_levels: list[str] = []  # JSON: experienceLevels
    employment_types: list[str] = []   # JSON: employmentTypes


class BriefingGenerateRequest(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    user_id: int
    category: str
    preference: JobPostingPreference
    briefing_date: str  # JSON: briefingDate
    tone: str = "easy"


class JobArticle(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    title: str
    source: str | None = None
    url: str | None = None
    summary: str | None = None
    why_it_matters: str | None = None
    published_at: str | None = None


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
