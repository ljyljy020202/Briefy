from fastapi import APIRouter

from app.schemas.collection import DailyCollectRequest, DailyCollectResponse
from app.services.daily_collection import DailyCollectionService

router = APIRouter()

_service = DailyCollectionService()


@router.post(
    "/daily",
    response_model=DailyCollectResponse,
    response_model_by_alias=True,
)
async def collect_daily(request: DailyCollectRequest) -> DailyCollectResponse:
    return await _service.collect(request)
