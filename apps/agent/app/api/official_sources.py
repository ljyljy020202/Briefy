from fastapi import APIRouter

from app.schemas.official_sources import (
    OfficialSourcePreflightRequest,
    OfficialSourcePreflightResponse,
)
from app.services.official_source_preflight import run_preflight

router = APIRouter()


@router.post(
    "/preflight",
    response_model=OfficialSourcePreflightResponse,
    response_model_by_alias=True,
)
async def preflight_source(
    request: OfficialSourcePreflightRequest,
) -> OfficialSourcePreflightResponse:
    return await run_preflight(
        source_id=request.source_id,
        company_id=request.company_id,
        company_name=request.company_name,
        source_type=request.source_type,
        source_url=request.source_url,
        adapter_type=request.adapter_type,
        config_json=request.config_json,
    )
