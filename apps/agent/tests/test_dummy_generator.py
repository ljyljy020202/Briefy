from app.schemas.briefing import BriefingGenerateRequest, BriefingGenerateResponse, TopicInput
from app.services.dummy_briefing import generate


def _make_request(**overrides) -> BriefingGenerateRequest:
    defaults: dict = {
        "user_id": 1,
        "topics": [
            TopicInput(name="Target Role", keywords=["백엔드 개발자"]),
            TopicInput(name="Target Companies", keywords=["네이버", "카카오", "라인"]),
            TopicInput(name="Skills / Competencies", keywords=["Spring Boot", "Java"]),
            TopicInput(name="Location", keywords=["서울"]),
            TopicInput(name="Experience Level", keywords=["신입"]),
            TopicInput(name="Employment Type", keywords=["정규직"]),
        ],
        "date": "2026-06-26",
        "tone": "easy",
    }
    defaults.update(overrides)
    return BriefingGenerateRequest(**defaults)


def test_generate_returns_valid_response_model():
    result = generate(_make_request())
    assert isinstance(result, BriefingGenerateResponse)
    assert result.title
    assert result.summary
    assert result.content
    assert len(result.articles) > 0


def test_generate_title_contains_primary_role():
    result = generate(_make_request())
    assert "백엔드 개발자" in result.title


def test_generate_articles_reference_target_company():
    result = generate(_make_request())
    companies_mentioned = [
        a for a in result.articles if "네이버" in a.title or "카카오" in a.title or "라인" in a.title
    ]
    assert len(companies_mentioned) > 0


def test_generate_handles_empty_topics():
    result = generate(_make_request(topics=[]))
    assert isinstance(result, BriefingGenerateResponse)
    assert len(result.articles) > 0


def test_generate_always_has_token_usage():
    result = generate(_make_request())
    assert result.token_usage is not None
    assert result.token_usage.input_tokens == 0
    assert result.token_usage.output_tokens == 0


def test_generate_content_has_all_sections():
    result = generate(_make_request())
    assert "신규 공고" in result.content
    assert "마감 임박 공고" in result.content
    assert "추천 액션" in result.content


def test_generate_articles_have_published_at():
    result = generate(_make_request())
    for article in result.articles:
        assert article.published_at is not None
        assert "2026-06-26" in article.published_at


def test_generate_articles_count_equals_total():
    result = generate(_make_request())
    assert len(result.articles) == 5
