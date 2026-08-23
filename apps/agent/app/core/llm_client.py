"""Async OpenAI wrapper for briefing generation.

Provides two call modes:
- call_text: free-form text response
- call_json: structured JSON response (uses OpenAI JSON mode)

Both raise LLMUnavailableError when no API key is configured, and
LLMClientError on API failure or unparseable output.
"""

import json
import logging
from dataclasses import dataclass, field
from typing import Any

from app.core.config import settings

logger = logging.getLogger(__name__)


class LLMUnavailableError(Exception):
    """Raised when OPENAI_API_KEY is not configured."""


class LLMClientError(Exception):
    """Raised when an LLM API call fails or returns unparseable output."""


@dataclass
class LLMTokenUsage:
    input_tokens: int = 0
    output_tokens: int = 0
    total_tokens: int = 0


@dataclass
class LLMResult:
    content: str
    token_usage: LLMTokenUsage = field(default_factory=LLMTokenUsage)


class LLMClient:
    """Async OpenAI client wrapper.

    Pass ``_openai_client`` in tests to inject a mock instead of creating a
    real AsyncOpenAI instance.
    """

    def __init__(self, _openai_client: Any = None) -> None:
        if _openai_client is not None:
            self._client: Any = _openai_client
            return

        if not settings.openai_api_key:
            self._client = None
            return

        try:
            from openai import AsyncOpenAI

            self._client = AsyncOpenAI(
                api_key=settings.openai_api_key,
                timeout=float(settings.llm_timeout_seconds),
            )
        except ImportError:
            logger.warning("openai package not importable; LLM disabled.")
            self._client = None

    @property
    def enabled(self) -> bool:
        return self._client is not None

    def _require_client(self) -> Any:
        if self._client is None:
            raise LLMUnavailableError(
                "LLM is unavailable: OPENAI_API_KEY is not configured."
            )
        return self._client

    async def _call(
        self,
        system_prompt: str,
        user_prompt: str,
        *,
        json_mode: bool = False,
    ) -> LLMResult:
        client = self._require_client()
        kwargs: dict[str, Any] = {
            "model": settings.llm_model,
            "temperature": settings.llm_temperature,
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt},
            ],
        }
        if json_mode:
            kwargs["response_format"] = {"type": "json_object"}

        try:
            response = await client.chat.completions.create(**kwargs)
        except Exception as exc:
            raise LLMClientError(f"LLM API call failed: {exc}") from exc

        usage = getattr(response, "usage", None)
        token_usage = LLMTokenUsage(
            input_tokens=getattr(usage, "prompt_tokens", 0) or 0,
            output_tokens=getattr(usage, "completion_tokens", 0) or 0,
            total_tokens=getattr(usage, "total_tokens", 0) or 0,
        )
        if usage:
            logger.info(
                "LLM token usage — model=%s input=%d output=%d total=%d",
                settings.llm_model,
                token_usage.input_tokens,
                token_usage.output_tokens,
                token_usage.total_tokens,
            )

        choices = getattr(response, "choices", [])
        content = (choices[0].message.content if choices else "") or ""
        return LLMResult(content=content.strip(), token_usage=token_usage)

    async def call_text(
        self,
        system_prompt: str,
        user_prompt: str,
    ) -> LLMResult:
        """Call LLM and return a free-form text result."""
        return await self._call(system_prompt, user_prompt, json_mode=False)

    async def call_json(
        self,
        system_prompt: str,
        user_prompt: str,
    ) -> tuple[Any, LLMTokenUsage]:
        """Call LLM expecting a JSON object response.

        Returns ``(parsed_object, token_usage)``.
        Raises ``LLMClientError`` if the response cannot be parsed as JSON.
        """
        result = await self._call(system_prompt, user_prompt, json_mode=True)
        content = _strip_code_fences(result.content)
        try:
            parsed = json.loads(content)
        except json.JSONDecodeError as exc:
            raise LLMClientError(
                f"LLM returned non-JSON output: {exc}. "
                f"First 300 chars: {content[:300]!r}"
            ) from exc
        return parsed, result.token_usage


def _strip_code_fences(text: str) -> str:
    """Remove markdown code fences that some models add despite JSON mode."""
    text = text.strip()
    if not text.startswith("```"):
        return text
    lines = text.splitlines()
    start = 1
    end = len(lines) - 1 if lines and lines[-1].strip() == "```" else len(lines)
    return "\n".join(lines[start:end]).strip()


llm_client = LLMClient()
