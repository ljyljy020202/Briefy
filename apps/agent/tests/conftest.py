import pytest
from httpx import ASGITransport, AsyncClient

from app.main import app

FULL_REQUEST = {
    "userId": 1,
    "topics": [
        {"name": "Target Role", "keywords": ["백엔드 개발자", "풀스택 개발자"]},
        {"name": "Target Companies", "keywords": ["네이버", "카카오", "라인"]},
        {"name": "Skills / Competencies", "keywords": ["Spring Boot", "Java", "Kotlin"]},
        {"name": "Location", "keywords": ["서울", "판교"]},
        {"name": "Experience Level", "keywords": ["신입", "3년 이상"]},
        {"name": "Employment Type", "keywords": ["정규직"]},
    ],
    "date": "2026-06-26",
    "tone": "easy",
}


@pytest.fixture
async def client():
    async with AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as ac:
        yield ac
