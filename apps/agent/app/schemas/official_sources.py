from pydantic import BaseModel, ConfigDict
from pydantic.alias_generators import to_camel


class OfficialSourcePreflightRequest(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    source_id: int
    company_id: int
    company_name: str | None = None
    source_type: str
    source_url: str | None = None
    adapter_type: str
    config_json: str | None = None


class OfficialSourcePreflightResponse(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)

    reachable: bool = False
    adapter_supported: bool = False
    parser_registered: bool = False
    discovered_count: int = 0
    sample_parsed_count: int = 0
    required_fields_valid: bool = False
    warnings: list[str] = []
    failure_code: str | None = None
    failure_message: str | None = None
