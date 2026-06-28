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
    minimal = {
        "userId": 2,
        "category": "JOB_POSTING",
        "preference": {},
        "briefingDate": "2026-06-26",
        "tone": "easy",
    }
    response = await client.post("/briefings/generate", json=minimal)
    assert response.status_code == 200
    body = response.json()
    assert body["title"]
    assert isinstance(body["articles"], list)
    assert len(body["articles"]) > 0


async def test_generate_articles_reference_target_company(client):
    response = await client.post("/briefings/generate", json=FULL_REQUEST)
    titles = [a["title"] for a in response.json()["articles"]]
    assert any("네이버" in t or "카카오" in t or "라인" in t for t in titles)
