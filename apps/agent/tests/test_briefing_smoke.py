"""Smoke / integration tests for POST /briefings/generate — Spring contract.

Each test exercises the full FastAPI stack (no DB, no real LLM) and verifies
that the agent response can be safely deserialized and persisted by the Spring
backend.  Field names, types, and lengths come from:

  BriefingReport.java
    title    @Column(nullable=false, length=255)
    summary  @Column(length=1000)
    content  @Column(nullable=false, columnDefinition="MEDIUMTEXT")
    tokenInput / tokenOutput  Integer (may be null if LLM is skipped)

  BriefingArticle.java
    title         @Column(nullable=false, length=500)
    source        @Column(length=255)
    url           @Column(length=1000)
    summary       @Column(columnDefinition="TEXT")
    whyItMatters  @Column(name="why_it_matters", columnDefinition="TEXT")
    publishedAt   LocalDateTime — parsed via LocalDateTime.parse(ISO string) or null

  AgentBriefingResponse.java
    title, summary, content, articles[], tokenUsage{inputTokens, outputTokens}
    articles[]: title, source, url, summary, whyItMatters, publishedAt
    (companyName is an extra agent field; Spring ignores it via Jackson)

No external network calls are made (test env has no OPENAI_API_KEY).
"""

from __future__ import annotations

import re
from unittest.mock import AsyncMock, patch

from app.services.llm_client import LLMTokenUsage

# ---------------------------------------------------------------------------
# Fixtures — realistic payloads that mirror what BriefingService sends
# ---------------------------------------------------------------------------

# 10 job postings that resemble what AgentCandidateJobPosting carries.
_POOL_10 = [
    {
        "id": 101,
        "source": "원티드",
        "sourceUrl": "https://www.wanted.co.kr/wd/00101",
        "companyName": "네이버",
        "title": "네이버 서버 백엔드 개발자 (Java/Spring)",
        "position": "백엔드 개발자",
        "employmentType": "정규직",
        "experienceLevel": "신입",
        "location": "성남시 분당구",
        "deadline": "2026-07-15",
        "skills": ["Java", "Spring Boot", "MySQL", "Redis"],
        "roles": ["백엔드 개발자"],
        "description": "네이버 서버 플랫폼팀에서 백엔드 개발자를 채용합니다.",
        "postedAt": "2026-06-25T10:00:00",
        "collectedDate": "2026-06-26",
        "contentHash": "a" * 64,
        "preScore": 90,
    },
    {
        "id": 102,
        "source": "카카오채용",
        "sourceUrl": "https://careers.kakao.com/jobs/102",
        "companyName": "카카오",
        "title": "카카오 플랫폼 백엔드 개발자",
        "position": "백엔드 개발자",
        "employmentType": "정규직",
        "experienceLevel": "3년 이상",
        "location": "판교",
        "deadline": "2026-07-20",
        "skills": ["Kotlin", "Spring Boot", "Kafka"],
        "roles": ["백엔드 개발자"],
        "description": "카카오 플랫폼팀 백엔드 개발자 채용. Kotlin/Spring Boot 기반.",
        "postedAt": "2026-06-24T09:00:00",
        "collectedDate": "2026-06-26",
        "contentHash": "b" * 64,
        "preScore": 85,
    },
    {
        "id": 103,
        "source": "LinkedIn",
        "sourceUrl": "https://www.linkedin.com/jobs/view/103",
        "companyName": "라인",
        "title": "LINE 풀스택 개발자 (Java/React)",
        "position": "풀스택 개발자",
        "employmentType": "정규직",
        "experienceLevel": "3년 이상",
        "location": "서울 강남구",
        "deadline": "2026-07-25",
        "skills": ["Java", "React", "TypeScript"],
        "roles": ["풀스택 개발자"],
        "description": "LINE Plus 풀스택 개발자 모집. Java + React 경험자 우대.",
        "postedAt": "2026-06-23T09:00:00",
        "collectedDate": "2026-06-26",
        "contentHash": "c" * 64,
        "preScore": 75,
    },
    {
        "id": 104,
        "source": "잡코리아",
        "sourceUrl": "https://www.jobkorea.co.kr/Recruit/GI_Read/00104",
        "companyName": "쿠팡",
        "title": "쿠팡 물류 플랫폼 백엔드 개발자",
        "position": "백엔드 개발자",
        "employmentType": "정규직",
        "experienceLevel": "3년 이상",
        "location": "서울 강남구",
        "deadline": "2026-08-01",
        "skills": ["Java", "Spring Boot", "AWS", "DynamoDB"],
        "roles": ["백엔드 개발자"],
        "description": "쿠팡 물류 플랫폼 백엔드 개발. AWS 기반 대용량 처리 우대.",
        "postedAt": "2026-06-22T11:00:00",
        "collectedDate": "2026-06-26",
        "contentHash": "d" * 64,
        "preScore": 70,
    },
    {
        "id": 105,
        "source": "사람인",
        "sourceUrl": "https://www.saramin.co.kr/zf_user/jobs/relay/view?job_cd=105",
        "companyName": "토스",
        "title": "토스 서버 개발자 (Kotlin/Spring)",
        "position": "백엔드 개발자",
        "employmentType": "정규직",
        "experienceLevel": "신입",
        "location": "서울 강남구",
        "deadline": "2026-07-10",
        "skills": ["Kotlin", "Spring Boot", "MySQL"],
        "roles": ["백엔드 개발자"],
        "description": "토스 서버팀 개발자 채용. Kotlin 기반 서비스 개발 및 운영.",
        "postedAt": "2026-06-26T08:00:00",
        "collectedDate": "2026-06-26",
        "contentHash": "e" * 64,
        "preScore": 65,
    },
    {
        "id": 106,
        "source": "원티드",
        "sourceUrl": "https://www.wanted.co.kr/wd/00106",
        "companyName": "당근마켓",
        "title": "당근마켓 백엔드 엔지니어",
        "position": "백엔드 개발자",
        "employmentType": "정규직",
        "experienceLevel": "3년 이상",
        "location": "서울 서초구",
        "deadline": "2026-07-30",
        "skills": ["Go", "gRPC", "PostgreSQL"],
        "roles": ["백엔드 엔지니어"],
        "description": "당근마켓 백엔드 엔지니어 채용. Go 마이크로서비스 경험 우대.",
        "postedAt": "2026-06-20T10:00:00",
        "collectedDate": "2026-06-26",
        "contentHash": "f" * 64,
        "preScore": 60,
    },
    {
        "id": 107,
        "source": "LinkedIn",
        "sourceUrl": "https://www.linkedin.com/jobs/view/107",
        "companyName": "배달의민족",
        "title": "배민 서버 개발자 (Java)",
        "position": "서버 개발자",
        "employmentType": "정규직",
        "experienceLevel": "신입",
        "location": "서울 송파구",
        "deadline": "2026-07-20",
        "skills": ["Java", "Spring Boot", "AWS"],
        "roles": ["서버 개발자"],
        "description": "배달의민족 서버팀에서 Java 서버 개발자를 채용합니다.",
        "postedAt": "2026-06-21T14:00:00",
        "collectedDate": "2026-06-26",
        "contentHash": "g" * 64,
        "preScore": 55,
    },
    {
        "id": 108,
        "source": "잡코리아",
        "sourceUrl": "https://www.jobkorea.co.kr/Recruit/GI_Read/00108",
        "companyName": "카카오페이",
        "title": "카카오페이 결제 시스템 백엔드 개발자",
        "position": "백엔드 개발자",
        "employmentType": "정규직",
        "experienceLevel": "3년 이상",
        "location": "판교",
        "deadline": "2026-08-15",
        "skills": ["Java", "Spring Boot", "MySQL", "Redis"],
        "roles": ["백엔드 개발자"],
        "description": "카카오페이 결제 시스템 개발. 금융 서비스 도메인 경험 우대.",
        "postedAt": "2026-06-19T09:00:00",
        "collectedDate": "2026-06-26",
        "contentHash": "h" * 64,
        "preScore": 50,
    },
    {
        "id": 109,
        "source": "사람인",
        "sourceUrl": "https://www.saramin.co.kr/zf_user/jobs/relay/view?job_cd=109",
        "companyName": "네이버파이낸셜",
        "title": "네이버파이낸셜 Java 백엔드 개발자",
        "position": "백엔드 개발자",
        "employmentType": "계약직",
        "experienceLevel": "신입",
        "location": "성남시 분당구",
        "deadline": "2026-07-12",
        "skills": ["Java", "Spring"],
        "roles": ["백엔드 개발자"],
        "description": "네이버파이낸셜 핀테크 서비스 Java 기반 금융 플랫폼 개발.",
        "postedAt": "2026-06-18T10:00:00",
        "collectedDate": "2026-06-26",
        "contentHash": "i" * 64,
        "preScore": 45,
    },
    {
        "id": 110,
        "source": "원티드",
        "sourceUrl": "https://www.wanted.co.kr/wd/00110",
        "companyName": "스포카",
        "title": "스포카 백엔드 개발자 (Python/Java)",
        "position": "백엔드 개발자",
        "employmentType": "정규직",
        "experienceLevel": "신입",
        "location": "서울 마포구",
        "deadline": "2026-07-31",
        "skills": ["Python", "Django", "Java"],
        "roles": ["백엔드 개발자"],
        "description": "스포카 백엔드팀 개발자 채용. Python/Java 기반 서비스 개발.",
        "postedAt": "2026-06-17T09:00:00",
        "collectedDate": "2026-06-26",
        "contentHash": "j" * 64,
        "preScore": 40,
    },
]

_REQUEST_10 = {
    "userId": 10,
    "category": "JOB_POSTING",
    "preference": {
        "roles": ["백엔드 개발자", "풀스택 개발자", "서버 개발자"],
        "companies": ["네이버", "카카오", "라인", "토스"],
        "skills": ["Java", "Spring Boot", "Kotlin", "MySQL"],
        "locations": ["서울", "판교", "성남"],
        "experienceLevels": ["신입", "3년 이상"],
        "employmentTypes": ["정규직"],
    },
    "briefingDate": "2026-06-26",
    "tone": "easy",
    "candidatePool": {
        "jobPostings": _POOL_10,
        "companyIssues": [],
        "industryIssues": [],
    },
}

_REQUEST_EMPTY_POOL = {
    "userId": 20,
    "category": "JOB_POSTING",
    "preference": {
        "roles": ["백엔드 개발자"],
        "companies": ["네이버"],
        "skills": ["Java"],
        "locations": ["서울"],
        "experienceLevels": ["신입"],
        "employmentTypes": ["정규직"],
    },
    "briefingDate": "2026-06-26",
    "tone": "easy",
    "candidatePool": {"jobPostings": [], "companyIssues": [], "industryIssues": []},
}

# Postings where every optional field is absent — only id, title, companyName, preScore.
_REQUEST_MISSING_OPTIONAL = {
    "userId": 30,
    "category": "JOB_POSTING",
    "preference": {
        "roles": ["백엔드 개발자"],
        "companies": [],
        "skills": ["Java"],
        "locations": [],
        "experienceLevels": [],
        "employmentTypes": [],
    },
    "briefingDate": "2026-06-26",
    "tone": "easy",
    "candidatePool": {
        "jobPostings": [
            {
                "id": 200,
                "companyName": "테스트회사",
                "title": "백엔드 개발자",
                "preScore": 50,
            },
            {
                "id": 201,
                "companyName": "스타트업B",
                "title": "Java 서버 개발자",
                "source": "원티드",
                "skills": [],
                "roles": [],
                "preScore": 30,
            },
        ],
        "companyIssues": [],
        "industryIssues": [],
    },
}

# Mix: one posting with a past deadline (should be filtered) + one valid.
_REQUEST_PAST_DEADLINE = {
    "userId": 40,
    "category": "JOB_POSTING",
    "preference": {
        "roles": ["백엔드 개발자"],
        "companies": [],
        "skills": ["Java"],
        "locations": ["서울"],
        "experienceLevels": [],
        "employmentTypes": [],
    },
    "briefingDate": "2026-06-26",
    "tone": "easy",
    "candidatePool": {
        "jobPostings": [
            {
                "id": 300,
                "source": "원티드",
                "sourceUrl": "https://www.wanted.co.kr/wd/00300",
                "companyName": "만료회사",
                "title": "기간만료 백엔드 개발자",
                "deadline": "2026-06-25",  # one day before briefingDate
                "skills": ["Java"],
                "roles": ["백엔드 개발자"],
                "preScore": 80,
            },
            {
                "id": 301,
                "source": "잡코리아",
                "sourceUrl": "https://www.jobkorea.co.kr/Recruit/GI_Read/00301",
                "companyName": "유효회사",
                "title": "유효한 백엔드 개발자 공고",
                "deadline": "2026-07-30",
                "skills": ["Java"],
                "roles": ["백엔드 개발자"],
                "preScore": 60,
            },
        ],
        "companyIssues": [],
        "industryIssues": [],
    },
}

# LLM mock responses for the mocked-success scenario.
_MOCK_ENRICHMENT_10 = {
    "enrichments": [
        {
            "id": str(pid),
            "summary": f"id {pid} 공고 LLM 요약입니다.",
            "matchingReason": f"id {pid} 백엔드 개발자 역할과 Java 스킬이 매칭됩니다.",
            "matchedKeywords": ["Java", "Spring Boot"],
        }
        for pid in [101, 102, 103, 104, 105, 106, 107]
    ]
}
_MOCK_SYNTHESIS_10 = {
    "markdownContent": (
        "# 오늘의 채용 브리핑\n\n"
        "## 오늘의 핵심 요약\n\n"
        "- 2026-06-26 기준, 네이버·카카오·라인 등 7건의 공고를 선별했습니다.\n"
        "- 백엔드 개발자 역할과 Java, Spring Boot 스킬이 주요 매칭 조건입니다.\n"
        "- 토스 공고 마감(2026-07-10)이 임박하니 오늘 확인해 보세요.\n\n"
        "## 🏆 추천 공고 TOP 7\n\n"
        "공고 상세\n\n"
        "## ⏰ 신규/마감 임박 공고\n\n"
        "토스 — 2026-07-10 마감\n\n"
        "## 💡 오늘의 지원 추천 액션\n\n"
        "1. 토스 공고를 오늘 바로 지원하세요.\n\n"
        "## 🔑 오늘의 키워드\n\n"
        "Java · Spring Boot · Kotlin\n\n"
        "## ✏️ 한 줄 정리\n\n"
        "네이버·카카오·라인 등 7건 확인되었습니다."
    ),
    "overallSummary": (
        "네이버·카카오·라인 등 7건의 Java/Spring Boot 백엔드 공고를 선별했습니다."
    ),
}

# ISO-8601 local datetime pattern Spring's LocalDateTime.parse() expects.
_ISO_LOCAL_DATETIME_RE = re.compile(
    r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d+)?)?$"
)

_REQUIRED_SECTION_SUBSTRINGS = [
    "오늘의 핵심 요약",
    "추천 공고 TOP",
    "신규/마감 임박 공고",
    "오늘의 지원 추천 액션",
    "오늘의 키워드",
    "한 줄 정리",
]


# ---------------------------------------------------------------------------
# Contract-check helpers
# ---------------------------------------------------------------------------


def _check_report_contract(body: dict) -> None:
    """Verify top-level fields satisfy Spring BriefingReport column constraints."""
    assert body.get("title"), "title must be non-null and non-empty"
    assert body.get("content"), "content must be non-null and non-empty"
    assert isinstance(body["title"], str)
    assert isinstance(body["content"], str)
    assert len(body["title"]) <= 255, (
        f"title exceeds Spring column length=255: {len(body['title'])} chars"
    )
    if body.get("summary"):
        assert isinstance(body["summary"], str)
        assert len(body["summary"]) <= 1000, (
            f"summary exceeds Spring column length=1000: {len(body['summary'])} chars"
        )
    token = body.get("tokenUsage", {})
    assert isinstance(token.get("inputTokens"), int), (
        "tokenUsage.inputTokens must be an integer (Spring Integer field)"
    )
    assert isinstance(token.get("outputTokens"), int), (
        "tokenUsage.outputTokens must be an integer (Spring Integer field)"
    )


def _check_article_contract(article: dict) -> None:
    """Verify each article satisfies Spring BriefingArticle column constraints."""
    # title: nullable=false, length=500
    assert article.get("title"), (
        "article.title must be non-null (Spring nullable=false)"
    )
    assert isinstance(article["title"], str)
    assert len(article["title"]) <= 500, (
        f"article.title exceeds Spring column length=500: {len(article['title'])} chars"
    )
    # source: length=255
    if article.get("source") is not None:
        assert isinstance(article["source"], str)
        assert len(article["source"]) <= 255, (
            "article.source exceeds Spring column length=255"
        )
    # url: length=1000
    if article.get("url") is not None:
        assert isinstance(article["url"], str)
        assert len(article["url"]) <= 1000, (
            "article.url exceeds Spring column length=1000"
        )
    # publishedAt: null or ISO datetime parseable by Spring's LocalDateTime.parse()
    pub = article.get("publishedAt")
    if pub is not None:
        assert isinstance(pub, str), "publishedAt must be a string or null"
        assert _ISO_LOCAL_DATETIME_RE.match(pub), (
            f"publishedAt not parseable as ISO LocalDateTime by Spring: {pub!r}"
        )
    # whyItMatters: TEXT column, maps to BriefingArticle.why_it_matters
    if article.get("whyItMatters") is not None:
        assert isinstance(article["whyItMatters"], str)


# ---------------------------------------------------------------------------
# 1. Normal case — 10 realistic postings
# ---------------------------------------------------------------------------


async def test_smoke_10_postings_returns_200(client):
    response = await client.post("/briefings/generate", json=_REQUEST_10)
    assert response.status_code == 200


async def test_smoke_10_postings_report_satisfies_spring_contract(client):
    body = (await client.post("/briefings/generate", json=_REQUEST_10)).json()
    _check_report_contract(body)


async def test_smoke_10_postings_articles_satisfy_spring_contract(client):
    articles = (await client.post("/briefings/generate", json=_REQUEST_10)).json()[
        "articles"
    ]
    assert isinstance(articles, list)
    for article in articles:
        _check_article_contract(article)


async def test_smoke_10_postings_selects_at_most_top_n(client):
    articles = (await client.post("/briefings/generate", json=_REQUEST_10)).json()[
        "articles"
    ]
    assert len(articles) <= 7, "pipeline must cap output at _TOP_N=7"
    assert len(articles) > 0


async def test_smoke_10_postings_content_has_all_required_sections(client):
    content = (await client.post("/briefings/generate", json=_REQUEST_10)).json()[
        "content"
    ]
    for section in _REQUIRED_SECTION_SUBSTRINGS:
        assert section in content, f"required section missing from report: {section!r}"


async def test_smoke_10_postings_content_starts_with_top_heading(client):
    content = (await client.post("/briefings/generate", json=_REQUEST_10)).json()[
        "content"
    ]
    assert content.startswith("# 오늘의 채용 브리핑")


async def test_smoke_10_postings_prefers_interest_companies_in_articles(client):
    articles = (await client.post("/briefings/generate", json=_REQUEST_10)).json()[
        "articles"
    ]
    # Top-ranked postings should include interest companies (네이버·카카오·라인·토스)
    companies = {a.get("companyName") for a in articles}
    interest = {"네이버", "카카오", "라인", "토스"}
    assert companies & interest, (
        f"expected at least one interest company in articles; got {companies}"
    )


# ---------------------------------------------------------------------------
# 2. Empty candidatePool
# ---------------------------------------------------------------------------


async def test_smoke_empty_pool_returns_200(client):
    response = await client.post("/briefings/generate", json=_REQUEST_EMPTY_POOL)
    assert response.status_code == 200


async def test_smoke_empty_pool_report_satisfies_spring_contract(client):
    body = (
        await client.post("/briefings/generate", json=_REQUEST_EMPTY_POOL)
    ).json()
    _check_report_contract(body)


async def test_smoke_empty_pool_articles_is_empty_list(client):
    body = (
        await client.post("/briefings/generate", json=_REQUEST_EMPTY_POOL)
    ).json()
    assert body.get("articles") == []


async def test_smoke_empty_pool_content_has_all_required_sections(client):
    content = (
        await client.post("/briefings/generate", json=_REQUEST_EMPTY_POOL)
    ).json()["content"]
    for section in _REQUIRED_SECTION_SUBSTRINGS:
        assert section in content, f"empty-pool report missing section: {section!r}"


# ---------------------------------------------------------------------------
# 3. No API key — deterministic fallback
# ---------------------------------------------------------------------------


async def test_smoke_no_api_key_returns_200_not_500(client):
    # Test env has no OPENAI_API_KEY; the pipeline must always fall back gracefully.
    response = await client.post("/briefings/generate", json=_REQUEST_10)
    assert response.status_code == 200


async def test_smoke_no_api_key_token_usage_is_zero(client, monkeypatch):
    from unittest.mock import MagicMock

    import app.graph.user_briefing_graph as _graph

    fake = MagicMock()
    fake.enabled = False
    monkeypatch.setattr(_graph, "llm_client", fake)
    token = (await client.post("/briefings/generate", json=_REQUEST_10)).json()[
        "tokenUsage"
    ]
    assert token["inputTokens"] == 0
    assert token["outputTokens"] == 0


async def test_smoke_no_api_key_report_has_deterministic_content(client):
    body = (await client.post("/briefings/generate", json=_REQUEST_10)).json()
    _check_report_contract(body)
    assert "##" in body["content"]
    assert len(body["articles"]) > 0


# ---------------------------------------------------------------------------
# 4. LLM mocked success — full LLM path, Spring field mapping
# ---------------------------------------------------------------------------


async def test_smoke_llm_success_returns_200(client):
    with patch("app.graph.user_briefing_graph.llm_client") as mock_llm:
        mock_llm.enabled = True
        mock_llm.call_json = AsyncMock(
            side_effect=[
                (_MOCK_ENRICHMENT_10, LLMTokenUsage(100, 200, 300)),
                (_MOCK_SYNTHESIS_10, LLMTokenUsage(50, 100, 150)),
            ]
        )
        response = await client.post("/briefings/generate", json=_REQUEST_10)
    assert response.status_code == 200


async def test_smoke_llm_success_report_satisfies_spring_contract(client):
    with patch("app.graph.user_briefing_graph.llm_client") as mock_llm:
        mock_llm.enabled = True
        mock_llm.call_json = AsyncMock(
            side_effect=[
                (_MOCK_ENRICHMENT_10, LLMTokenUsage(100, 200, 300)),
                (_MOCK_SYNTHESIS_10, LLMTokenUsage(50, 100, 150)),
            ]
        )
        body = (await client.post("/briefings/generate", json=_REQUEST_10)).json()
    _check_report_contract(body)


async def test_smoke_llm_success_articles_satisfy_spring_contract(client):
    with patch("app.graph.user_briefing_graph.llm_client") as mock_llm:
        mock_llm.enabled = True
        mock_llm.call_json = AsyncMock(
            side_effect=[
                (_MOCK_ENRICHMENT_10, LLMTokenUsage(100, 200, 300)),
                (_MOCK_SYNTHESIS_10, LLMTokenUsage(50, 100, 150)),
            ]
        )
        articles = (
            await client.post("/briefings/generate", json=_REQUEST_10)
        ).json()["articles"]
    for article in articles:
        _check_article_contract(article)


async def test_smoke_llm_success_summary_from_overallsummary(client):
    with patch("app.graph.user_briefing_graph.llm_client") as mock_llm:
        mock_llm.enabled = True
        mock_llm.call_json = AsyncMock(
            side_effect=[
                (_MOCK_ENRICHMENT_10, LLMTokenUsage(100, 200, 300)),
                (_MOCK_SYNTHESIS_10, LLMTokenUsage(50, 100, 150)),
            ]
        )
        body = (await client.post("/briefings/generate", json=_REQUEST_10)).json()
    assert body["summary"] == _MOCK_SYNTHESIS_10["overallSummary"]


async def test_smoke_llm_success_token_usage_accumulated(client):
    with patch("app.graph.user_briefing_graph.llm_client") as mock_llm:
        mock_llm.enabled = True
        mock_llm.call_json = AsyncMock(
            side_effect=[
                (_MOCK_ENRICHMENT_10, LLMTokenUsage(100, 200, 300)),
                (_MOCK_SYNTHESIS_10, LLMTokenUsage(50, 100, 150)),
            ]
        )
        token = (
            await client.post("/briefings/generate", json=_REQUEST_10)
        ).json()["tokenUsage"]
    assert token["inputTokens"] == 150   # 100 + 50
    assert token["outputTokens"] == 300  # 200 + 100


async def test_smoke_llm_success_content_has_required_sections(client):
    with patch("app.graph.user_briefing_graph.llm_client") as mock_llm:
        mock_llm.enabled = True
        mock_llm.call_json = AsyncMock(
            side_effect=[
                (_MOCK_ENRICHMENT_10, LLMTokenUsage(100, 200, 300)),
                (_MOCK_SYNTHESIS_10, LLMTokenUsage(50, 100, 150)),
            ]
        )
        content = (
            await client.post("/briefings/generate", json=_REQUEST_10)
        ).json()["content"]
    for section in _REQUIRED_SECTION_SUBSTRINGS:
        assert section in content, f"LLM-path report missing section: {section!r}"


# ---------------------------------------------------------------------------
# 5. Postings with missing optional fields
# ---------------------------------------------------------------------------


async def test_smoke_missing_optional_fields_returns_200(client):
    response = await client.post(
        "/briefings/generate", json=_REQUEST_MISSING_OPTIONAL
    )
    assert response.status_code == 200


async def test_smoke_missing_optional_fields_report_satisfies_spring_contract(client):
    body = (
        await client.post("/briefings/generate", json=_REQUEST_MISSING_OPTIONAL)
    ).json()
    _check_report_contract(body)


async def test_smoke_missing_optional_fields_articles_satisfy_spring_contract(client):
    articles = (
        await client.post("/briefings/generate", json=_REQUEST_MISSING_OPTIONAL)
    ).json()["articles"]
    assert isinstance(articles, list)
    for article in articles:
        _check_article_contract(article)
        # url and source may be None for postings that had no sourceUrl/source
        assert "url" in article, "url key must exist even if null"
        assert "source" in article, "source key must exist even if null"


async def test_smoke_missing_optional_fields_articles_have_non_empty_title(client):
    articles = (
        await client.post("/briefings/generate", json=_REQUEST_MISSING_OPTIONAL)
    ).json()["articles"]
    for article in articles:
        assert article.get("title"), (
            "article.title must not be empty even if posting lacks optional fields"
        )


# ---------------------------------------------------------------------------
# 6. Past-deadline postings are excluded
# ---------------------------------------------------------------------------


async def test_smoke_past_deadline_returns_200(client):
    response = await client.post("/briefings/generate", json=_REQUEST_PAST_DEADLINE)
    assert response.status_code == 200


async def test_smoke_past_deadline_posting_excluded_from_articles(client):
    articles = (
        await client.post("/briefings/generate", json=_REQUEST_PAST_DEADLINE)
    ).json()["articles"]
    titles = [a["title"] for a in articles]
    assert not any("기간만료" in t for t in titles), (
        "posting with past deadline must not appear in articles"
    )


async def test_smoke_past_deadline_valid_posting_included(client):
    articles = (
        await client.post("/briefings/generate", json=_REQUEST_PAST_DEADLINE)
    ).json()["articles"]
    assert len(articles) == 1
    assert "유효한" in articles[0]["title"]


async def test_smoke_past_deadline_report_satisfies_spring_contract(client):
    body = (
        await client.post("/briefings/generate", json=_REQUEST_PAST_DEADLINE)
    ).json()
    _check_report_contract(body)
    for article in body["articles"]:
        _check_article_contract(article)
