"""Unit tests for the classification system/user prompt.

Verify the system prompt encodes the field rules — especially the
large-enterprise / bank / financial new-grad open-recruitment exception that
must NOT be labelled NON_IT. No LLM or network calls are made.
"""

from __future__ import annotations

from app.prompts.classification import build_user_prompt, get_system_prompt
from app.schemas.classification import ClassifyInputQuality, ClassifyPostingInput


def _posting(pid: int, title: str, company: str) -> ClassifyPostingInput:
    return ClassifyPostingInput(
        job_posting_id=pid,
        analysis_input_hash="a" * 64,
        title=title,
        company=company,
        description=None,
        parsed_roles=[],
        parsed_experience_level=None,
        parsed_employment_type=None,
        source_refs=[],
        input_quality=ClassifyInputQuality(
            has_description=False,
            has_roles=False,
            has_experience_level=False,
            description_truncated=False,
            description_length=0,
        ),
    )


# ── System prompt: core structure ────────────────────────────────────────────


def test_system_prompt_separates_company_industry_from_role():
    prompt = get_system_prompt()
    assert "회사 산업" in prompt
    assert "담당 직무" in prompt


def test_system_prompt_documents_all_job_domains():
    prompt = get_system_prompt()
    for value in ("IT", "NON_IT", "MIXED", "UNKNOWN"):
        assert value in prompt


def test_system_prompt_requires_json_only_output():
    prompt = get_system_prompt()
    assert "JSON" in prompt


# ── Enterprise / financial new-grad open-recruitment exception ────────────────


def test_system_prompt_has_enterprise_financial_exception():
    prompt = get_system_prompt()
    # The exception section must mention banks/financial and open recruitment.
    assert "금융" in prompt or "은행" in prompt
    assert "공개채용" in prompt


def test_system_prompt_maps_unspecified_open_recruitment_to_mixed():
    prompt = get_system_prompt()
    # Must instruct MIXED + OPEN_RECRUITMENT + GENERAL_IT track, not NON_IT.
    assert "MIXED" in prompt
    assert "OPEN_RECRUITMENT" in prompt
    assert "GENERAL_IT" in prompt


def test_system_prompt_keeps_explicit_non_it_role_as_non_it():
    prompt = get_system_prompt()
    # Boundary: explicit non-IT roles (e.g. bank branch teller) stay NON_IT.
    assert "영업점" in prompt or "창구" in prompt


# ── Field-level guidance ─────────────────────────────────────────────────────


def test_system_prompt_distinguishes_general_it_from_other_it():
    prompt = get_system_prompt()
    assert "GENERAL_IT" in prompt
    assert "OTHER_IT" in prompt


def test_system_prompt_separates_required_from_preferred_experience():
    prompt = get_system_prompt()
    assert "REQUIRED" in prompt
    assert "PREFERRED" in prompt
    assert "우대" in prompt


def test_system_prompt_forbids_new_grad_forced_false():
    prompt = get_system_prompt()
    assert "acceptsNewGrad" in prompt
    assert "null" in prompt


def test_system_prompt_forbids_mixing_tracks():
    prompt = get_system_prompt()
    # Same posting's role and experience must be read from the same track.
    assert "같은 트랙" in prompt


# ── User prompt ──────────────────────────────────────────────────────────────


def test_user_prompt_includes_posting_fields():
    prompt = build_user_prompt(
        [_posting(1, "한화에어로스페이스 2026 신입사원 공개채용", "한화에어로스페이스")]
    )
    assert "한화에어로스페이스" in prompt
    assert "1" in prompt


def test_user_prompt_is_valid_json_with_postings_key():
    import json

    prompt = build_user_prompt([_posting(7, "KB국민은행 신입 공채", "KB국민은행")])
    data = json.loads(prompt)
    assert "postings" in data
    assert data["postings"][0]["jobPostingId"] == 7
