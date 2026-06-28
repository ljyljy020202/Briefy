from fastapi import APIRouter

from app.schemas.briefing import BriefingGenerateRequest, BriefingGenerateResponse
from app.services import dummy_briefing

router = APIRouter()


@router.post(
    "/generate",
    response_model=BriefingGenerateResponse,
    response_model_by_alias=True,
)
async def generate_briefing(
    request: BriefingGenerateRequest,
) -> BriefingGenerateResponse:
    return dummy_briefing.generate(request)
