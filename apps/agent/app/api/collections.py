from fastapi import APIRouter

from app.schemas.collection import DailyCollectRequest, DailyCollectResponse
from app.services import dummy_collection

router = APIRouter()


@router.post(
    "/daily",
    response_model=DailyCollectResponse,
    response_model_by_alias=True,
)
async def collect_daily(request: DailyCollectRequest) -> DailyCollectResponse:
    return dummy_collection.collect(request)
