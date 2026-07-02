from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    openai_api_key: str = ""
    backend_url: str = "http://localhost:8080"

    # Job collection
    job_collection_use_fixture: bool = True
    job_collection_enable_real_sources: bool = False
    job_collection_timeout_seconds: int = 10
    jasoseol_base_url: str = "https://jasoseol.com"


settings = Settings()
