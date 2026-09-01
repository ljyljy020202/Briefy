from fastapi import APIRouter, HTTPException

from app.schemas.classification import ClassifyRequest, ClassifyResponse
from app.schemas.collection import DailyCollectRequest, DailyCollectResponse
from app.services.classification import ClassificationService
from app.services.daily_collection import DailyCollectionService

router = APIRouter()

_service = DailyCollectionService()
_classify_service = ClassificationService()


@router.post(
    "/daily",
    response_model=DailyCollectResponse,
    response_model_by_alias=True,
)
async def collect_daily(request: DailyCollectRequest) -> DailyCollectResponse:
    return await _service.collect(request)


@router.post(
    "/classify",
    response_model=ClassifyResponse,
    response_model_by_alias=True,
    summary="Batch-classify job postings (internal — Spring backend only)",
)
async def classify_postings(request: ClassifyRequest) -> ClassifyResponse:
    """Internal endpoint for Spring backend to trigger LLM-based job posting classification.

    Not for public access. Protected by network boundary (agent is not internet-facing).
    Validates input, runs LLM classification in configurable batches, returns results.
    """
    if not request.postings:
        raise HTTPException(status_code=422, detail="postings must not be empty")

    try:
        return await _classify_service.classify(request)
    except ValueError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
