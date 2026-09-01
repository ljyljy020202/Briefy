from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict

# Native dev: /…/briefy/apps/agent/app/core/config.py → parents[4] = monorepo root
# Docker:     /app/app/core/config.py → only 3 ancestors; fall back to Path(".env")
#             (Docker env vars injected by Compose take precedence anyway)
_parents = Path(__file__).resolve().parents
_ROOT_ENV = _parents[4] / ".env" if len(_parents) > 4 else Path(".env")


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=str(_ROOT_ENV), extra="ignore")

    openai_api_key: str = ""
    backend_url: str = "http://localhost:8080"

    # Job collection
    # Set JOB_COLLECTION_USE_FIXTURE=true only for local dev/testing.
    # Defaults to false so that fixture data is never collected in production.
    job_collection_use_fixture: bool = False
    job_collection_enable_jasoseol: bool = False
    job_collection_enable_saramin: bool = False
    job_collection_timeout_seconds: int = 25
    jasoseol_base_url: str = "https://jasoseol.com"

    # Jasoseol-specific
    # Fraction of detail_fetch_limit_per_source reserved for TARGETED search
    # candidates.  Must be in [0.0, 1.0].
    jasoseol_targeted_discovery_ratio: float = 0.8

    # Saramin
    saramin_access_key: str = ""
    saramin_api_base_url: str = "https://oapi.saramin.co.kr"
    saramin_max_queries_per_collect: int = 10

    # LLM
    llm_model: str = "gpt-4o-mini"
    llm_temperature: float = 0.2
    llm_timeout_seconds: int = 30

    # Classification service
    classify_batch_size: int = 5
    classify_max_concurrency: int = 2
    classify_llm_timeout_seconds: int = 30
    classify_max_attempts_per_item: int = 2
    classify_request_budget_seconds: int = 75
    classify_max_items_per_request: int = 50
    classify_max_description_length: int = 4000

    @property
    def briefing_use_llm(self) -> bool:
        return bool(self.openai_api_key)


settings = Settings()
