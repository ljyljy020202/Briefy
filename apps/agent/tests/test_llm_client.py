"""Unit tests for LLMClient.

All tests use injected mock clients — no real OpenAI network calls are made.
"""

from unittest.mock import AsyncMock, MagicMock

import pytest

from app.core.llm_client import (
    LLMClient,
    LLMClientError,
    LLMTokenUsage,
    LLMUnavailableError,
    _strip_code_fences,
)

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _make_openai_response(
    content: str = "{}",
    prompt_tokens: int = 10,
    completion_tokens: int = 5,
) -> MagicMock:
    """Build a mock object that looks like an OpenAI ChatCompletion response."""
    usage = MagicMock()
    usage.prompt_tokens = prompt_tokens
    usage.completion_tokens = completion_tokens
    usage.total_tokens = prompt_tokens + completion_tokens

    message = MagicMock()
    message.content = content

    choice = MagicMock()
    choice.message = message

    response = MagicMock()
    response.choices = [choice]
    response.usage = usage
    return response


def _make_mock_openai(
    content: str = "{}",
    prompt_tokens: int = 10,
    completion_tokens: int = 5,
) -> AsyncMock:
    """Build an AsyncMock that replaces openai.AsyncOpenAI."""
    response = _make_openai_response(content, prompt_tokens, completion_tokens)
    mock = AsyncMock()
    mock.chat.completions.create = AsyncMock(return_value=response)
    return mock


# ---------------------------------------------------------------------------
# Enabled / disabled state
# ---------------------------------------------------------------------------


def test_llm_client_disabled_when_no_api_key(monkeypatch):
    import app.core.llm_client as _mod

    monkeypatch.setattr(_mod.settings, "openai_api_key", None)
    client = LLMClient()
    assert client.enabled is False


def test_llm_client_enabled_with_injected_client():
    mock = _make_mock_openai()
    client = LLMClient(_openai_client=mock)
    assert client.enabled is True


# ---------------------------------------------------------------------------
# Guard: LLMUnavailableError
# ---------------------------------------------------------------------------


async def test_call_text_raises_unavailable_when_disabled(monkeypatch):
    import app.core.llm_client as _mod

    monkeypatch.setattr(_mod.settings, "openai_api_key", None)
    client = LLMClient()
    with pytest.raises(LLMUnavailableError):
        await client.call_text("system", "user")


async def test_call_json_raises_unavailable_when_disabled(monkeypatch):
    import app.core.llm_client as _mod

    monkeypatch.setattr(_mod.settings, "openai_api_key", None)
    client = LLMClient()
    with pytest.raises(LLMUnavailableError):
        await client.call_json("system", "user")


# ---------------------------------------------------------------------------
# call_text
# ---------------------------------------------------------------------------


async def test_call_text_returns_content():
    mock = _make_mock_openai(content="안녕하세요")
    client = LLMClient(_openai_client=mock)
    result = await client.call_text("system", "user")
    assert result.content == "안녕하세요"


async def test_call_text_populates_token_usage():
    mock = _make_mock_openai(content="hi", prompt_tokens=20, completion_tokens=8)
    client = LLMClient(_openai_client=mock)
    result = await client.call_text("system", "user")
    assert result.token_usage.input_tokens == 20
    assert result.token_usage.output_tokens == 8
    assert result.token_usage.total_tokens == 28


async def test_call_text_raises_client_error_on_api_failure():
    mock = AsyncMock()
    mock.chat.completions.create = AsyncMock(side_effect=RuntimeError("timeout"))
    client = LLMClient(_openai_client=mock)
    with pytest.raises(LLMClientError, match="LLM API call failed"):
        await client.call_text("system", "user")


# ---------------------------------------------------------------------------
# call_json
# ---------------------------------------------------------------------------


async def test_call_json_parses_valid_json_object():
    mock = _make_mock_openai(content='{"key": "value", "num": 42}')
    client = LLMClient(_openai_client=mock)
    parsed, usage = await client.call_json("system", "user")
    assert parsed == {"key": "value", "num": 42}


async def test_call_json_parses_nested_json():
    payload = (
        '{"enrichments": [{"id": "1", "summary": "요약",' ' "matchingReason": "이유"}]}'
    )
    mock = _make_mock_openai(content=payload)
    client = LLMClient(_openai_client=mock)
    parsed, _ = await client.call_json("system", "user")
    assert "enrichments" in parsed
    assert parsed["enrichments"][0]["id"] == "1"


async def test_call_json_returns_token_usage():
    mock = _make_mock_openai(
        content='{"ok": true}', prompt_tokens=15, completion_tokens=6
    )
    client = LLMClient(_openai_client=mock)
    _, usage = await client.call_json("system", "user")
    assert isinstance(usage, LLMTokenUsage)
    assert usage.input_tokens == 15
    assert usage.output_tokens == 6
    assert usage.total_tokens == 21


async def test_call_json_raises_client_error_on_invalid_json():
    mock = _make_mock_openai(content="이건 JSON이 아닙니다")
    client = LLMClient(_openai_client=mock)
    with pytest.raises(LLMClientError, match="non-JSON"):
        await client.call_json("system", "user")


async def test_call_json_raises_client_error_on_api_failure():
    mock = AsyncMock()
    mock.chat.completions.create = AsyncMock(side_effect=RuntimeError("network error"))
    client = LLMClient(_openai_client=mock)
    with pytest.raises(LLMClientError):
        await client.call_json("system", "user")


async def test_call_json_strips_markdown_fences_before_parsing():
    fenced = '```json\n{"answer": 1}\n```'
    mock = _make_mock_openai(content=fenced)
    client = LLMClient(_openai_client=mock)
    parsed, _ = await client.call_json("system", "user")
    assert parsed == {"answer": 1}


# ---------------------------------------------------------------------------
# _strip_code_fences helper
# ---------------------------------------------------------------------------


def test_strip_code_fences_removes_json_block():
    assert _strip_code_fences("```json\n{}\n```") == "{}"


def test_strip_code_fences_removes_plain_block():
    assert _strip_code_fences('```\n{"a": 1}\n```') == '{"a": 1}'


def test_strip_code_fences_leaves_plain_json_untouched():
    assert _strip_code_fences('{"key": "val"}') == '{"key": "val"}'


def test_strip_code_fences_handles_no_closing_fence():
    result = _strip_code_fences('```json\n{"x": 2}')
    assert '{"x": 2}' in result


def test_strip_code_fences_handles_empty_string():
    assert _strip_code_fences("") == ""


# ---------------------------------------------------------------------------
# Token usage defaults when usage is absent
# ---------------------------------------------------------------------------


async def test_call_text_token_usage_defaults_to_zero_when_usage_absent():
    response = MagicMock()
    message = MagicMock()
    message.content = "result"
    choice = MagicMock()
    choice.message = message
    response.choices = [choice]
    response.usage = None

    mock = AsyncMock()
    mock.chat.completions.create = AsyncMock(return_value=response)

    client = LLMClient(_openai_client=mock)
    result = await client.call_text("sys", "usr")
    assert result.token_usage.input_tokens == 0
    assert result.token_usage.output_tokens == 0
    assert result.token_usage.total_tokens == 0
