from datetime import date
from unittest.mock import AsyncMock, patch

from app.schemas.collection import CollectionStats, DailyCollectResponse

COLLECT_REQUEST = {
    "collectDate": "2026-06-30",
    "categories": ["JOB_POSTING"],
    "seedKeywords": {
        "roles": ["백엔드 개발자", "풀스택 개발자"],
        "companies": ["네이버", "카카오"],
        "skills": ["Spring Boot", "Java"],
        "locations": ["서울"],
        "experienceLevels": ["신입"],
        "employmentTypes": ["정규직"],
    },
}


async def test_collect_daily_returns_200(client):
    response = await client.post("/collections/daily", json=COLLECT_REQUEST)
    assert response.status_code == 200


async def test_collect_daily_response_has_required_fields(client):
    response = await client.post("/collections/daily", json=COLLECT_REQUEST)
    body = response.json()
    for field in (
        "collectDate", "jobPostings", "companyIssues", "industryIssues", "stats"
    ):
        assert field in body, f"missing field: {field}"


async def test_collect_daily_job_postings_non_empty_for_job_posting_category(client):
    response = await client.post("/collections/daily", json=COLLECT_REQUEST)
    job_postings = response.json()["jobPostings"]
    assert isinstance(job_postings, list)
    assert len(job_postings) >= 3


async def test_collect_daily_each_posting_has_content_hash(client):
    response = await client.post("/collections/daily", json=COLLECT_REQUEST)
    for posting in response.json()["jobPostings"]:
        assert "contentHash" in posting
        assert len(posting["contentHash"]) == 64  # SHA-256 hex digest


async def test_collect_daily_company_issues_is_empty_array(client):
    response = await client.post("/collections/daily", json=COLLECT_REQUEST)
    assert response.json()["companyIssues"] == []


async def test_collect_daily_industry_issues_is_empty_array(client):
    response = await client.post("/collections/daily", json=COLLECT_REQUEST)
    assert response.json()["industryIssues"] == []


async def test_collect_daily_posting_fields_are_camelcase(client):
    response = await client.post("/collections/daily", json=COLLECT_REQUEST)
    posting = response.json()["jobPostings"][0]
    for field in (
        "sourceUrl", "companyName", "contentHash",
        "employmentType", "experienceLevel", "postedAt",
    ):
        assert field in posting, f"missing camelCase field: {field}"
    assert "source_url" not in posting
    assert "company_name" not in posting
    assert "content_hash" not in posting


async def test_collect_daily_stats_match_posting_count(client):
    response = await client.post("/collections/daily", json=COLLECT_REQUEST)
    body = response.json()
    assert body["stats"]["jobPostingCount"] == len(body["jobPostings"])
    # collectedCount is the raw total before dedup/filter, so it can be >= final count
    assert body["stats"]["collectedCount"] >= len(body["jobPostings"])


async def test_collect_daily_non_job_posting_category_returns_no_postings(client):
    request = {**COLLECT_REQUEST, "categories": ["COMPANY_NEWS"]}
    response = await client.post("/collections/daily", json=request)
    assert response.status_code == 200
    assert response.json()["jobPostings"] == []


async def test_collect_daily_collection_job_id_echoed_in_response(client):
    request = {**COLLECT_REQUEST, "collectionJobId": 42}
    response = await client.post("/collections/daily", json=request)
    assert response.json()["collectionJobId"] == 42


async def test_collect_daily_content_hash_is_stable_across_calls(client):
    r1 = (await client.post("/collections/daily", json=COLLECT_REQUEST)).json()
    r2 = (await client.post("/collections/daily", json=COLLECT_REQUEST)).json()
    hashes_1 = [p["contentHash"] for p in r1["jobPostings"]]
    hashes_2 = [p["contentHash"] for p in r2["jobPostings"]]
    assert hashes_1 == hashes_2


async def test_collect_daily_uses_daily_collection_service(client):
    """Route must delegate to DailyCollectionService, not dummy_collection."""
    stub = DailyCollectResponse(
        collect_date=date(2026, 6, 30),
        stats=CollectionStats(),
    )
    with patch(
        "app.api.collections._service.collect",
        new_callable=AsyncMock,
        return_value=stub,
    ) as mock_collect:
        response = await client.post("/collections/daily", json=COLLECT_REQUEST)

    assert response.status_code == 200
    mock_collect.assert_called_once()


async def test_collect_daily_no_real_network_calls_in_fixture_mode(client, monkeypatch):
    """Fixture mode uses only FixtureAdapter — source='fixture', no network calls."""
    from app.adapters.fixture import FixtureAdapter

    monkeypatch.setattr(
        "app.services.daily_collection._build_adapters",
        lambda _req: [FixtureAdapter()],
    )
    response = await client.post("/collections/daily", json=COLLECT_REQUEST)
    postings = response.json()["jobPostings"]
    assert all(p["source"] == "fixture" for p in postings)
