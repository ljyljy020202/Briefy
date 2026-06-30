from fastapi import APIRouter

from app.graph import user_briefing_graph
from app.schemas.briefing import BriefingGenerateRequest, BriefingGenerateResponse

router = APIRouter()


@router.post(
    "/generate",
    response_model=BriefingGenerateResponse,
    response_model_by_alias=True,
)
async def generate_briefing(
    request: BriefingGenerateRequest,
) -> BriefingGenerateResponse:
    return user_briefing_graph.run(request)
