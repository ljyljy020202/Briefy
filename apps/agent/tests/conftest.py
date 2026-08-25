from unittest.mock import MagicMock

import pytest
from httpx import ASGITransport, AsyncClient

from app.main import app

_POOL_POSTINGS = [
    {
        "id": 1,
        "rank": 1,
        "source": "원티드",
        "sourceUrl": "https://www.wanted.co.kr/wd/00001",
        "companyName": "네이버",
        "title": "네이버 백엔드 개발자",
        "employmentType": "정규직",
        "experienceLevel": "신입",
        "location": "서울",
        "deadline": "2026-07-10",
        "skills": ["Spring Boot", "Java"],
        "roles": ["백엔드 개발자"],
        "description": "네이버 백엔드 개발자 채용합니다.",
        "publishedAt": "2026-06-20T09:00:00",
        "collectedDate": "2026-06-26",
        "isNew": True,
        "isUrgent": False,
        "scoreBreakdown": {
            "roleScore": 30,
            "companyScore": 20,
            "skillScore": 10,
            "experienceScore": 10,
            "industryScore": 0,
            "locationScore": 10,
            "employmentTypeScore": 5,
            "companySizeScore": 0,
            "relevanceScore": 85,
            "exposurePenalty": 0,
            "adjustedScore": 85,
        },
        "matchEvidence": {
            "matchedRoles": ["백엔드 개발자"],
            "matchedCompanies": ["네이버"],
            "matchedSkills": ["Spring Boot", "Java"],
            "matchedLocations": ["서울"],
            "matchedExperienceLevels": ["신입"],
            "matchedEmploymentTypes": ["정규직"],
            "matchedIndustries": [],
            "matchedCompanySizes": [],
        },
    },
    {
        "id": 2,
        "rank": 2,
        "source": "잡코리아",
        "sourceUrl": "https://www.jobkorea.co.kr/Recruit/GI_Read/00002",
        "companyName": "카카오",
        "title": "카카오 백엔드 개발자",
        "employmentType": "정규직",
        "experienceLevel": "3년 이상",
        "location": "판교",
        "deadline": "2026-07-05",
        "skills": ["Spring Boot", "Kotlin"],
        "roles": ["백엔드 개발자"],
        "description": "카카오 백엔드 개발자 채용합니다.",
        "publishedAt": "2026-06-21T09:00:00",
        "collectedDate": "2026-06-26",
        "isNew": True,
        "isUrgent": False,
        "scoreBreakdown": {
            "roleScore": 30,
            "companyScore": 20,
            "skillScore": 10,
            "experienceScore": 10,
            "industryScore": 0,
            "locationScore": 10,
            "employmentTypeScore": 5,
            "companySizeScore": 0,
            "relevanceScore": 75,
            "exposurePenalty": 0,
            "adjustedScore": 75,
        },
        "matchEvidence": {
            "matchedRoles": ["백엔드 개발자"],
            "matchedCompanies": ["카카오"],
            "matchedSkills": ["Spring Boot", "Kotlin"],
            "matchedLocations": ["판교"],
            "matchedExperienceLevels": ["3년 이상"],
            "matchedEmploymentTypes": ["정규직"],
            "matchedIndustries": [],
            "matchedCompanySizes": [],
        },
    },
    {
        "id": 3,
        "rank": 3,
        "source": "LinkedIn",
        "sourceUrl": "https://www.linkedin.com/jobs/view/00003",
        "companyName": "라인",
        "title": "라인 풀스택 개발자",
        "employmentType": "정규직",
        "experienceLevel": "3년 이상",
        "location": "서울",
        "deadline": "2026-07-20",
        "skills": ["Java", "Kotlin", "React"],
        "roles": ["풀스택 개발자"],
        "description": "라인 풀스택 개발자 채용합니다.",
        "publishedAt": "2026-06-22T09:00:00",
        "collectedDate": "2026-06-26",
        "isNew": True,
        "isUrgent": False,
        "scoreBreakdown": {
            "roleScore": 30,
            "companyScore": 20,
            "skillScore": 10,
            "experienceScore": 10,
            "industryScore": 0,
            "locationScore": 10,
            "employmentTypeScore": 5,
            "companySizeScore": 0,
            "relevanceScore": 60,
            "exposurePenalty": 0,
            "adjustedScore": 60,
        },
        "matchEvidence": {
            "matchedRoles": ["풀스택 개발자"],
            "matchedCompanies": ["라인"],
            "matchedSkills": ["Java", "Kotlin"],
            "matchedLocations": ["서울"],
            "matchedExperienceLevels": ["3년 이상"],
            "matchedEmploymentTypes": ["정규직"],
            "matchedIndustries": [],
            "matchedCompanySizes": [],
        },
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


# ---------------------------------------------------------------------------
# Network-call guards (autouse — applied to every test)
# ---------------------------------------------------------------------------


@pytest.fixture(autouse=True)
def _disable_llm_client(monkeypatch):
    """Block real OpenAI calls in all tests.

    Tests that need a live LLM mock override this with
    ``with patch("app.graph.user_briefing_graph.llm_client") as mock_llm:``
    inside the test body; patch() temporarily replaces the monkeypatched
    reference and restores it on exit.
    """
    import app.graph.user_briefing_graph as _graph

    fake = MagicMock()
    fake.enabled = False
    monkeypatch.setattr(_graph, "llm_client", fake)


@pytest.fixture(autouse=True)
def _use_fixture_adapter(monkeypatch):
    """Block real HTTP calls from job-board adapters in all tests.

    Patches the adapter-enable flags on the daily_collection settings object so
    that _build_adapters() uses only FixtureAdapter (deterministic, no network).

    Tests in test_daily_collection.py that verify flag-to-adapter mapping
    override these defaults with their own monkeypatch.setattr calls — the
    last setattr on the same attribute wins within a single test.
    Tests that replace _build_adapters entirely (e.g. via patch()) are
    unaffected because this fixture only touches the settings attributes,
    not the function itself.
    """
    monkeypatch.setattr(
        "app.services.daily_collection.settings.job_collection_use_fixture", True
    )
    monkeypatch.setattr(
        "app.services.daily_collection.settings.job_collection_enable_jasoseol", False
    )
    monkeypatch.setattr(
        "app.services.daily_collection.settings.job_collection_enable_saramin", False
    )


# ---------------------------------------------------------------------------
# FastAPI test client
# ---------------------------------------------------------------------------


@pytest.fixture
async def client():
    async with AsyncClient(
        transport=ASGITransport(app=app), base_url="http://test"
    ) as ac:
        yield ac
