from tests.conftest import FULL_REQUEST


async def test_generate_returns_200(client):
    response = await client.post("/briefings/generate", json=FULL_REQUEST)
    assert response.status_code == 200


async def test_generate_response_has_required_fields(client):
    response = await client.post("/briefings/generate", json=FULL_REQUEST)
    body = response.json()
    for field in ("title", "summary", "content", "articles", "tokenUsage"):
        assert field in body, f"missing field: {field}"


async def test_generate_title_contains_role_keyword(client):
    response = await client.post("/briefings/generate", json=FULL_REQUEST)
    assert "백엔드 개발자" in response.json()["title"]


async def test_generate_articles_is_non_empty_list(client):
    response = await client.post("/briefings/generate", json=FULL_REQUEST)
    articles = response.json()["articles"]
    assert isinstance(articles, list)
    assert len(articles) > 0


async def test_generate_article_fields_are_camelcase(client):
    response = await client.post("/briefings/generate", json=FULL_REQUEST)
    article = response.json()["articles"][0]
    assert "whyItMatters" in article
    assert "why_it_matters" not in article
    assert "publishedAt" in article
    assert "published_at" not in article


async def test_generate_content_is_markdown(client):
    response = await client.post("/briefings/generate", json=FULL_REQUEST)
    content = response.json()["content"]
    assert "##" in content


async def test_generate_token_usage_is_camelcase(client):
    response = await client.post("/briefings/generate", json=FULL_REQUEST)
    body = response.json()
    assert "tokenUsage" in body
    assert "token_usage" not in body
    token_usage = body["tokenUsage"]
    assert "inputTokens" in token_usage
    assert "outputTokens" in token_usage


async def test_generate_content_references_company_keyword(client):
    response = await client.post("/briefings/generate", json=FULL_REQUEST)
    content = response.json()["content"]
    assert "네이버" in content


async def test_generate_with_empty_preference_returns_valid_response(client):
    request = {
        "userId": 2,
        "category": "JOB_POSTING",
        "preference": {},
        "briefingDate": "2026-06-26",
        "tone": "easy",
        "candidatePool": {
            "jobPostings": [
                {
                    "id": 99,
                    "rank": 1,
                    "source": "원티드",
                    "sourceUrl": "https://www.wanted.co.kr/wd/00099",
                    "companyName": "스타트업A",
                    "title": "백엔드 개발자",
                    "employmentType": "정규직",
                    "experienceLevel": "신입",
                    "location": "서울",
                    "deadline": "2026-07-15",
                    "skills": ["Python"],
                    "roles": ["백엔드 개발자"],
                    "description": "백엔드 개발자 채용",
                    "publishedAt": "2026-07-01T10:00:00",
                    "isNew": False,
                    "isUrgent": False,
                    "scoreBreakdown": {
                        "roleScore": 30, "companyScore": 0, "skillScore": 5,
                        "experienceScore": 0, "industryScore": 0,
                        "locationScore": 10, "employmentTypeScore": 10,
                        "companySizeScore": 0, "relevanceScore": 55,
                        "exposurePenalty": 0, "adjustedScore": 55,
                    },
                    "matchEvidence": {
                        "matchedRoles": ["백엔드 개발자"], "matchedCompanies": [],
                        "matchedSkills": [], "matchedLocations": ["서울"],
                        "matchedExperienceLevels": [],
                        "matchedEmploymentTypes": [],
                        "matchedIndustries": [], "matchedCompanySizes": [],
                    },
                }
            ],
            "companyIssues": [],
            "industryIssues": [],
        },
    }
    response = await client.post("/briefings/generate", json=request)
    assert response.status_code == 200
    body = response.json()
    assert body["title"]
    assert isinstance(body["articles"], list)
    assert len(body["articles"]) > 0


async def test_generate_articles_reference_target_company(client):
    response = await client.post("/briefings/generate", json=FULL_REQUEST)
    titles = [a["title"] for a in response.json()["articles"]]
    assert any("네이버" in t or "카카오" in t or "라인" in t for t in titles)


async def test_generate_content_has_top_level_heading(client):
    response = await client.post("/briefings/generate", json=FULL_REQUEST)
    content = response.json()["content"]
    assert content.startswith("# 오늘의 채용 브리핑")


async def test_generate_articles_have_non_empty_why_it_matters(client):
    response = await client.post("/briefings/generate", json=FULL_REQUEST)
    for article in response.json()["articles"]:
        assert article.get("whyItMatters"), "whyItMatters must be non-empty"
