import pytest
from httpx import ASGITransport, AsyncClient

from app.main import app

FULL_REQUEST = {
    "userId": 1,
    "category": "JOB_POSTING",
    "preference": {
        "roles": ["백엔드 개발자", "풀스택 개발자"],
        "companies": ["네이버", "카카오", "라인"],
        "skills": ["Spring Boot", "Java", "Kotlin"],
        "locations": ["서울", "판교"],
        "experienceLevels": ["신입", "3년 이상"],
        "employmentTypes": ["정규직"],
    },
    "briefingDate": "2026-06-26",
    "tone": "easy",
}


@pytest.fixture
async def client():
    async with AsyncClient(
        transport=ASGITransport(app=app), base_url="http://test"
    ) as ac:
        yield ac
