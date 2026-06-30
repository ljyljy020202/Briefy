import pytest
from httpx import ASGITransport, AsyncClient

from app.main import app

_POOL_POSTINGS = [
    {
        "id": 1,
        "source": "원티드",
        "sourceUrl": "https://www.wanted.co.kr/wd/00001",
        "companyName": "네이버",
        "title": "네이버 백엔드 개발자",
        "position": "백엔드 개발자",
        "employmentType": "정규직",
        "experienceLevel": "신입",
        "location": "서울",
        "deadline": "2026-07-10",
        "skills": ["Spring Boot", "Java"],
        "roles": ["백엔드 개발자"],
        "description": "네이버 백엔드 개발자 채용합니다.",
        "postedAt": "2026-06-20T09:00:00",
        "collectedDate": "2026-06-26",
        "contentHash": "a" * 64,
        "preScore": 75,
    },
    {
        "id": 2,
        "source": "잡코리아",
        "sourceUrl": "https://www.jobkorea.co.kr/Recruit/GI_Read/00002",
        "companyName": "카카오",
        "title": "카카오 백엔드 개발자",
        "position": "백엔드 개발자",
        "employmentType": "정규직",
        "experienceLevel": "3년 이상",
        "location": "판교",
        "deadline": "2026-07-05",
        "skills": ["Spring Boot", "Kotlin"],
        "roles": ["백엔드 개발자"],
        "description": "카카오 백엔드 개발자 채용합니다.",
        "postedAt": "2026-06-21T09:00:00",
        "collectedDate": "2026-06-26",
        "contentHash": "b" * 64,
        "preScore": 70,
    },
    {
        "id": 3,
        "source": "LinkedIn",
        "sourceUrl": "https://www.linkedin.com/jobs/view/00003",
        "companyName": "라인",
        "title": "라인 풀스택 개발자",
        "position": "풀스택 개발자",
        "employmentType": "정규직",
        "experienceLevel": "3년 이상",
        "location": "서울",
        "deadline": "2026-07-20",
        "skills": ["Java", "Kotlin", "React"],
        "roles": ["풀스택 개발자"],
        "description": "라인 풀스택 개발자 채용합니다.",
        "postedAt": "2026-06-22T09:00:00",
        "collectedDate": "2026-06-26",
        "contentHash": "c" * 64,
        "preScore": 60,
    },
]

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
    "candidatePool": {
        "jobPostings": _POOL_POSTINGS,
        "companyIssues": [],
        "industryIssues": [],
    },
}


@pytest.fixture
async def client():
    async with AsyncClient(
        transport=ASGITransport(app=app), base_url="http://test"
    ) as ac:
        yield ac
