"""Unit tests for prompt builder modules.

Tests verify:
- System prompts contain required instructions and rules.
- User prompts include the correct preference and posting data.
- parse_response() handles valid and edge-case LLM output.

No LLM or network calls are made.
"""

import pytest

from app.prompts import briefing_synthesis, job_enrichment
from app.schemas.briefing import CandidateJobPosting, JobPostingPreference
from app.schemas.llm import BriefingSynthesisResult, JobPostingEnrichment

# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------


@pytest.fixture()
def preference() -> JobPostingPreference:
    return JobPostingPreference(
        roles=["백엔드 개발자"],
        companies=["네이버", "카카오"],
        skills=["Spring Boot", "Java"],
        locations=["서울"],
        experience_levels=["신입"],
        employment_types=["정규직"],
    )


@pytest.fixture()
def posting() -> CandidateJobPosting:
    return CandidateJobPosting(
        id=1,
        source="원티드",
        source_url="https://www.wanted.co.kr/wd/00001",
        company_name="네이버",
        title="네이버 백엔드 개발자",
        position="백엔드 개발자",
        employment_type="정규직",
        experience_level="신입",
        location="서울",
        deadline="2026-07-10",
        skills=["Spring Boot", "Java"],
        roles=["백엔드 개발자"],
        description="네이버 백엔드 개발자 채용합니다.",
        pre_score=75,
    )


@pytest.fixture()
def enriched_article() -> briefing_synthesis.EnrichedArticleInput:
    return {
        "id": "1",
        "title": "네이버 백엔드 개발자",
        "company_name": "네이버",
        "url": "https://www.wanted.co.kr/wd/00001",
        "source": "원티드",
        "summary": "네이버에서 신입 백엔드 개발자를 채용합니다.",
        "matching_reason": "관심 기업 네이버의 공고이며 Spring Boot 스킬과 매칭됩니다.",
        "matched_keywords": ["Spring Boot", "백엔드"],
        "deadline": "2026-07-10",
        "days_until_deadline": 5,
        "display_order": 1,
    }


# ===========================================================================
# job_enrichment — system prompt
# ===========================================================================


def test_enrichment_system_prompt_instructs_korean_output():
    prompt = job_enrichment.get_system_prompt()
    assert "한국어" in prompt


def test_enrichment_system_prompt_forbids_fabrication():
    prompt = job_enrichment.get_system_prompt()
    assert "만들어내지" in prompt


def test_enrichment_system_prompt_requires_json_format():
    prompt = job_enrichment.get_system_prompt()
    assert "JSON" in prompt


def test_enrichment_system_prompt_forbids_investment_advice():
    prompt = job_enrichment.get_system_prompt()
    assert "투자" in prompt or "매수" in prompt or "매도" in prompt


def test_enrichment_system_prompt_requires_matching_reason_field():
    prompt = job_enrichment.get_system_prompt()
    assert "matchingReason" in prompt


def test_enrichment_system_prompt_requires_matched_keywords_field():
    prompt = job_enrichment.get_system_prompt()
    assert "matchedKeywords" in prompt


# ===========================================================================
# job_enrichment — user prompt
# ===========================================================================


def test_enrichment_user_prompt_contains_roles(preference, posting):
    prompt = job_enrichment.build_user_prompt(preference, [posting])
    assert "백엔드 개발자" in prompt


def test_enrichment_user_prompt_contains_companies(preference, posting):
    prompt = job_enrichment.build_user_prompt(preference, [posting])
    assert "네이버" in prompt


def test_enrichment_user_prompt_contains_skills(preference, posting):
    prompt = job_enrichment.build_user_prompt(preference, [posting])
    assert "Spring Boot" in prompt


def test_enrichment_user_prompt_contains_posting_title(preference, posting):
    prompt = job_enrichment.build_user_prompt(preference, [posting])
    assert "네이버 백엔드 개발자" in prompt


def test_enrichment_user_prompt_contains_posting_company(preference, posting):
    prompt = job_enrichment.build_user_prompt(preference, [posting])
    assert "네이버" in prompt


def test_enrichment_user_prompt_contains_posting_deadline(preference, posting):
    prompt = job_enrichment.build_user_prompt(preference, [posting])
    assert "2026-07-10" in prompt


def test_enrichment_user_prompt_empty_pool_renders_without_error(preference):
    prompt = job_enrichment.build_user_prompt(preference, [])
    assert "사용자 선호도" in prompt
    assert "채용 공고 목록" in prompt


def test_enrichment_user_prompt_truncates_long_description(preference):
    long_desc = "A" * 1000
    posting = CandidateJobPosting(
        id=99,
        source="원티드",
        source_url="https://wanted.co.kr/wd/99",
        company_name="테스트",
        title="개발자",
        description=long_desc,
    )
    prompt = job_enrichment.build_user_prompt(preference, [posting])
    # description should be truncated to 500 chars
    assert long_desc not in prompt
    assert "A" * 500 in prompt or "A" * 499 in prompt


# ===========================================================================
# job_enrichment — parse_response
# ===========================================================================


def test_enrichment_parse_response_from_wrapped_object():
    raw = {
        "enrichments": [
            {
                "id": "1",
                "summary": "요약입니다.",
                "matchingReason": "매칭 이유입니다.",
                "matchedKeywords": ["Spring Boot"],
            }
        ]
    }
    result = job_enrichment.parse_response(raw)
    assert len(result) == 1
    assert isinstance(result[0], JobPostingEnrichment)
    assert result[0].id == "1"
    assert result[0].summary == "요약입니다."
    assert result[0].matching_reason == "매칭 이유입니다."
    assert result[0].matched_keywords == ["Spring Boot"]


def test_enrichment_parse_response_from_bare_list():
    raw = [
        {
            "id": "2",
            "summary": "리스트 형식 요약",
            "matchingReason": "리스트 형식 이유",
            "matchedKeywords": [],
        }
    ]
    result = job_enrichment.parse_response(raw)
    assert len(result) == 1
    assert result[0].id == "2"


def test_enrichment_parse_response_empty_enrichments():
    result = job_enrichment.parse_response({"enrichments": []})
    assert result == []


def test_enrichment_parse_response_missing_optional_fields():
    raw = {"enrichments": [{"id": "3", "summary": "요약"}]}
    result = job_enrichment.parse_response(raw)
    assert result[0].matching_reason == ""
    assert result[0].matched_keywords == []


def test_enrichment_parse_response_unknown_wrapper_key():
    raw = {
        "data": [
            {
                "id": "4",
                "summary": "요약",
                "matchingReason": "이유",
                "matchedKeywords": [],
            }
        ]
    }
    result = job_enrichment.parse_response(raw)
    assert len(result) == 1


def test_enrichment_parse_response_invalid_input_returns_empty():
    result = job_enrichment.parse_response(None)  # type: ignore[arg-type]
    assert result == []


# ===========================================================================
# briefing_synthesis — system prompt
# ===========================================================================


def test_synthesis_system_prompt_instructs_korean_output():
    prompt = briefing_synthesis.get_system_prompt()
    assert "한국어" in prompt


def test_synthesis_system_prompt_requires_markdown_content_field():
    prompt = briefing_synthesis.get_system_prompt()
    assert "markdownContent" in prompt


def test_synthesis_system_prompt_requires_overall_summary_field():
    prompt = briefing_synthesis.get_system_prompt()
    assert "overallSummary" in prompt


def test_synthesis_system_prompt_contains_all_required_sections():
    prompt = briefing_synthesis.get_system_prompt()
    required = [
        "오늘의 핵심 요약",
        "추천 공고 TOP",
        "신규/마감 임박 공고",
        "오늘의 지원 추천 액션",
        "오늘의 키워드",
        "한 줄 정리",
    ]
    for section in required:
        assert section in prompt, f"missing required section: {section}"


def test_synthesis_system_prompt_requires_source_links():
    prompt = briefing_synthesis.get_system_prompt()
    assert "공고 보기" in prompt or "url" in prompt.lower()


def test_synthesis_system_prompt_forbids_investment_advice():
    prompt = briefing_synthesis.get_system_prompt()
    assert "투자" in prompt or "매수" in prompt


def test_synthesis_system_prompt_requires_json_format():
    prompt = briefing_synthesis.get_system_prompt()
    assert "JSON" in prompt


# ===========================================================================
# briefing_synthesis — user prompt
# ===========================================================================


def test_synthesis_user_prompt_contains_date(preference, enriched_article):
    prompt = briefing_synthesis.build_user_prompt(
        "2026-07-02", preference, [enriched_article]
    )
    assert "2026-07-02" in prompt


def test_synthesis_user_prompt_contains_roles(preference, enriched_article):
    prompt = briefing_synthesis.build_user_prompt(
        "2026-07-02", preference, [enriched_article]
    )
    assert "백엔드 개발자" in prompt


def test_synthesis_user_prompt_contains_companies(preference, enriched_article):
    prompt = briefing_synthesis.build_user_prompt(
        "2026-07-02", preference, [enriched_article]
    )
    assert "네이버" in prompt


def test_synthesis_user_prompt_contains_article_title(preference, enriched_article):
    prompt = briefing_synthesis.build_user_prompt(
        "2026-07-02", preference, [enriched_article]
    )
    assert "네이버 백엔드 개발자" in prompt


def test_synthesis_user_prompt_contains_article_url(preference, enriched_article):
    prompt = briefing_synthesis.build_user_prompt(
        "2026-07-02", preference, [enriched_article]
    )
    assert "wanted.co.kr" in prompt


def test_synthesis_user_prompt_contains_matching_reason(preference, enriched_article):
    prompt = briefing_synthesis.build_user_prompt(
        "2026-07-02", preference, [enriched_article]
    )
    assert "Spring Boot" in prompt


def test_synthesis_user_prompt_empty_articles_renders_without_error(preference):
    prompt = briefing_synthesis.build_user_prompt("2026-07-02", preference, [])
    assert "브리핑 날짜" in prompt
    assert "사용자 선호도" in prompt


# ===========================================================================
# briefing_synthesis — parse_response
# ===========================================================================


def test_synthesis_parse_response_returns_result():
    raw = {
        "markdownContent": "## 오늘의 핵심 요약\n테스트 내용",
        "overallSummary": "오늘 5개 공고를 선별했습니다.",
    }
    result = briefing_synthesis.parse_response(raw)
    assert isinstance(result, BriefingSynthesisResult)
    assert "오늘의 핵심 요약" in result.markdown_content
    assert result.overall_summary == "오늘 5개 공고를 선별했습니다."


def test_synthesis_parse_response_raises_on_missing_fields():
    with pytest.raises(Exception):
        briefing_synthesis.parse_response({"markdownContent": "content only"})


def test_synthesis_parse_response_raises_on_empty_dict():
    with pytest.raises(Exception):
        briefing_synthesis.parse_response({})


# ===========================================================================
# briefing_synthesis — prompt quality: structure and anti-hallucination
# ===========================================================================


def test_synthesis_system_prompt_requires_top_level_heading():
    prompt = briefing_synthesis.get_system_prompt()
    assert "# 오늘의 채용 브리핑" in prompt


def test_synthesis_system_prompt_requires_three_summary_bullets():
    prompt = briefing_synthesis.get_system_prompt()
    assert "3개" in prompt or "3 개" in prompt


def test_synthesis_system_prompt_forbids_salary_hallucination():
    prompt = briefing_synthesis.get_system_prompt()
    assert "급여" in prompt or "연봉" in prompt


def test_synthesis_system_prompt_forbids_acceptance_probability():
    prompt = briefing_synthesis.get_system_prompt()
    assert "합격 가능성" in prompt or "합격 보장" in prompt


def test_synthesis_system_prompt_forbids_vague_matching_phrases():
    prompt = briefing_synthesis.get_system_prompt()
    prohibited = ["좋은 기회", "적합한 공고", "기대해볼 만한", "추천드립니다"]
    listed_in_prompt = [p for p in prohibited if p in prompt]
    assert listed_in_prompt, (
        "synthesis prompt should explicitly list prohibited vague phrases"
    )


def test_synthesis_system_prompt_requires_concrete_matching_reason():
    prompt = briefing_synthesis.get_system_prompt()
    # Must instruct to reference actual preference fields (role, skill, company, etc.)
    assert "역할" in prompt and "스킬" in prompt


def test_synthesis_system_prompt_deadline_omit_rule():
    prompt = briefing_synthesis.get_system_prompt()
    # Deadline must be omitted if not in the posting
    assert "마감일" in prompt and "생략" in prompt


# ===========================================================================
# job_enrichment — prompt quality: anti-hallucination and concrete reasons
# ===========================================================================


def test_enrichment_system_prompt_forbids_salary_hallucination():
    prompt = job_enrichment.get_system_prompt()
    assert "급여" in prompt or "연봉" in prompt


def test_enrichment_system_prompt_forbids_acceptance_probability():
    prompt = job_enrichment.get_system_prompt()
    assert "합격 가능성" in prompt or "합격 보장" in prompt


def test_enrichment_system_prompt_forbids_vague_matching_phrases():
    prompt = job_enrichment.get_system_prompt()
    prohibited = ["좋은 기회입니다", "적합한 공고입니다", "기대해볼 만한"]
    listed_in_prompt = [p for p in prohibited if p in prompt]
    assert listed_in_prompt, (
        "enrichment prompt should explicitly list prohibited vague phrases"
    )


def test_enrichment_system_prompt_requires_concrete_matching_examples():
    prompt = job_enrichment.get_system_prompt()
    # The prompt should show a concrete example (Spring Boot, 백엔드 개발자, etc.)
    assert "Spring Boot" in prompt or "백엔드 개발자" in prompt


# ===========================================================================
# JobPostingPreference — companySizes and industries (Bug 3 fix)
# ===========================================================================


def test_preference_schema_accepts_company_sizes_camel_case():
    pref = JobPostingPreference.model_validate({"companySizes": ["대기업", "스타트업"]})
    assert pref.company_sizes == ["대기업", "스타트업"]


def test_preference_schema_accepts_industries_camel_case():
    pref = JobPostingPreference.model_validate({"industries": ["IT/소프트웨어"]})
    assert pref.industries == ["IT/소프트웨어"]


def test_preference_schema_defaults_company_sizes_to_empty():
    pref = JobPostingPreference()
    assert pref.company_sizes == []


def test_preference_schema_defaults_industries_to_empty():
    pref = JobPostingPreference()
    assert pref.industries == []


def test_preference_schema_old_six_field_request_still_works():
    pref = JobPostingPreference(
        roles=["백엔드 개발자"],
        companies=["네이버"],
        skills=["Spring Boot"],
        locations=["서울"],
        experience_levels=["신입"],
        employment_types=["정규직"],
    )
    assert pref.company_sizes == []
    assert pref.industries == []
    assert pref.roles == ["백엔드 개발자"]


def test_format_preference_includes_company_sizes():
    pref = JobPostingPreference(company_sizes=["대기업", "중견기업"])
    result = briefing_synthesis._format_preference(pref)
    assert "대기업" in result
    assert "중견기업" in result


def test_format_preference_includes_industries():
    pref = JobPostingPreference(industries=["IT/소프트웨어"])
    result = briefing_synthesis._format_preference(pref)
    assert "IT/소프트웨어" in result


def test_format_preference_shows_unset_for_empty_company_sizes():
    pref = JobPostingPreference()
    result = briefing_synthesis._format_preference(pref)
    assert "기업 규모" in result
    assert "미지정" in result


def test_format_preference_shows_unset_for_empty_industries():
    pref = JobPostingPreference()
    result = briefing_synthesis._format_preference(pref)
    assert "관심 산업" in result
    assert "미지정" in result
