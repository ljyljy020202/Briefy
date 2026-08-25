"""Internal Pydantic models for parsing and validating LLM JSON output.

These are NOT part of the external API schema (app/schemas/briefing.py).
They are used only within the agent service layer.
"""

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field


class JobPostingEnrichment(BaseModel):
    """LLM-generated enrichment for a single job posting."""

    model_config = ConfigDict(populate_by_name=True)

    id: str
    summary: str
    matching_reason: str = Field(default="", alias="matchingReason")
    matched_keywords: list[str] = Field(default_factory=list, alias="matchedKeywords")


class BriefingSynthesisResult(BaseModel):
    """LLM-generated output for the full briefing report.

    ``referenced_posting_ids`` carries the posting IDs the LLM actually used,
    in the order they appear in the report. Validation checks this list against
    the Backend-provided IDs to ensure no reordering or omission occurred.
    """

    model_config = ConfigDict(populate_by_name=True)

    markdown_content: str = Field(alias="markdownContent")
    overall_summary: str = Field(alias="overallSummary")
    referenced_posting_ids: list[str] = Field(
        default_factory=list, alias="referencedPostingIds"
    )


# Explicit validation status literals shared across graph nodes and tests.
ValidationStatus = Literal["pending", "pass", "retryable", "fallback_required"]
