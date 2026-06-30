"""Tests for the candidatePool-based user briefing workflow."""

from tests.conftest import FULL_REQUEST

_RANKING_REQUEST = {
    "userId": 1,
    "category": "JOB_POSTING",
    "preference": {
        "roles": ["백엔드 개발자"],
        "companies": ["네이버"],
        "skills": ["Spring Boot"],
        "locations": ["서울"],
        "experienceLevels": ["신입"],
        "employmentTypes": ["정규직"],
    },
    "briefingDate": "2026-07-01",
    "tone": "easy",
    "candidatePool": {
        "jobPostings": [
            {
                "id": 10,
                "source": "원티드",
                "sourceUrl": "https://www.wanted.co.kr/wd/00010",
                "companyName": "알 수 없는 회사",
                "title": "프론트엔드 개발자",
                "position": "프론트엔드 개발자",
                "employmentType": "계약직",
                "experienceLevel": "5년 이상",
                "location": "부산",
                "deadline": "2026-07-20",
                "skills": ["Vue.js", "TypeScript"],
                "roles": ["프론트엔드 개발자"],
                "description": "프론트엔드 개발자 채용",
                "preScore": 10,
            },
            {
                "id": 11,
                "source": "잡코리아",
                "sourceUrl": "https://www.jobkorea.co.kr/Recruit/GI_Read/00011",
                "companyName": "네이버",
                "title": "네이버 백엔드 개발자",
                "position": "백엔드 개발자",
                "employmentType": "정규직",
                "experienceLevel": "신입",
                "location": "서울",
                "deadline": "2026-07-25",
                "skills": ["Spring Boot", "Java"],
                "roles": ["백엔드 개발자"],
                "description": "네이버 백엔드 개발자 채용",
                "preScore": 70,
            },
        ],
        "companyIssues": [],
        "industryIssues": [],
    },
}


async def test_candidatepool_request_returns_personalized_report(client):
    response = await client.post("/briefings/generate", json=FULL_REQUEST)
    assert response.status_code == 200
    body = response.json()
    assert body["title"]
    assert "백엔드 개발자" in body["title"]
    assert len(body["articles"]) > 0


async def test_ranking_prefers_matching_roles_and_companies(client):
    response = await client.post("/briefings/generate", json=_RANKING_REQUEST)
    assert response.status_code == 200
    articles = response.json()["articles"]
    assert len(articles) == 2
    assert "네이버" in articles[0]["title"]
    assert articles[0]["title"] != articles[1]["title"]


async def test_empty_candidatepool_returns_graceful_report(client):
    request = {
        **FULL_REQUEST,
        "candidatePool": {
            "jobPostings": [],
            "companyIssues": [],
            "industryIssues": [],
        },
    }
    response = await client.post("/briefings/generate", json=request)
    assert response.status_code == 200
    body = response.json()
    assert body["title"]
    assert isinstance(body["articles"], list)
    assert len(body["articles"]) == 0
    assert "없습니다" in body["content"]


async def test_response_schema_compatible_with_backend(client):
    response = await client.post("/briefings/generate", json=FULL_REQUEST)
    assert response.status_code == 200
    body = response.json()
    for field in ("title", "summary", "content", "articles", "tokenUsage"):
        assert field in body, f"missing top-level field: {field}"
    for article in body["articles"]:
        for field in ("title", "url", "summary", "whyItMatters", "publishedAt"):
            assert field in article, f"missing article field: {field}"


async def test_no_llm_calls_made(client):
    response = await client.post("/briefings/generate", json=FULL_REQUEST)
    token_usage = response.json()["tokenUsage"]
    assert token_usage["inputTokens"] == 0
    assert token_usage["outputTokens"] == 0


async def test_past_deadline_postings_are_filtered(client):
    request = {
        **FULL_REQUEST,
        "briefingDate": "2026-07-01",
        "candidatePool": {
            "jobPostings": [
                {
                    "id": 20,
                    "source": "원티드",
                    "sourceUrl": "https://www.wanted.co.kr/wd/00020",
                    "companyName": "테스트회사",
                    "title": "백엔드 개발자",
                    "position": "백엔드 개발자",
                    "deadline": "2026-06-30",
                    "skills": [],
                    "roles": [],
                    "preScore": 50,
                },
                {
                    "id": 21,
                    "source": "잡코리아",
                    "sourceUrl": "https://www.jobkorea.co.kr/Recruit/GI_Read/00021",
                    "companyName": "테스트회사B",
                    "title": "프론트엔드 개발자",
                    "position": "프론트엔드 개발자",
                    "deadline": "2026-07-15",
                    "skills": [],
                    "roles": [],
                    "preScore": 30,
                },
            ],
            "companyIssues": [],
            "industryIssues": [],
        },
    }
    response = await client.post("/briefings/generate", json=request)
    assert response.status_code == 200
    articles = response.json()["articles"]
    assert len(articles) == 1
    assert "프론트엔드" in articles[0]["title"]
