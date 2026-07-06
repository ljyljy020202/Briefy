from pathlib import Path

from pydantic_settings import BaseSettings, SettingsConfigDict

# Reads from the monorepo root .env for both native (poetry run) and Docker runs.
# In Docker, environment variables injected via docker-compose.yml take precedence.
_ROOT_ENV = Path(__file__).resolve().parents[4] / ".env"


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=str(_ROOT_ENV), extra="ignore")

    openai_api_key: str = ""
    backend_url: str = "http://localhost:8080"

    # Job collection
    job_collection_use_fixture: bool = True
    job_collection_enable_real_sources: bool = False
    job_collection_enable_saramin: bool = False
    job_collection_timeout_seconds: int = 10
    jasoseol_base_url: str = "https://jasoseol.com"

    # Saramin
    saramin_access_key: str = ""
    saramin_api_base_url: str = "https://oapi.saramin.co.kr"
    saramin_max_queries_per_collect: int = 10

    # LLM
    llm_model: str = "gpt-4o-mini"
    llm_temperature: float = 0.2
    llm_timeout_seconds: int = 30

    @property
    def briefing_use_llm(self) -> bool:
        return bool(self.openai_api_key)


settings = Settings()
