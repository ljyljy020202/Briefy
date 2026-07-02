"""Internal Pydantic models for parsing and validating LLM JSON output.

These are NOT part of the external API schema (app/schemas/briefing.py).
They are used only within the agent service layer.
"""

from pydantic import BaseModel, ConfigDict, Field


class JobPostingEnrichment(BaseModel):
    """LLM-generated enrichment for a single job posting."""

    model_config = ConfigDict(populate_by_name=True)

    id: str
    summary: str
    matching_reason: str = Field(default="", alias="matchingReason")
    matched_keywords: list[str] = Field(default_factory=list, alias="matchedKeywords")


class BriefingSynthesisResult(BaseModel):
    """LLM-generated output for the full briefing report."""

    model_config = ConfigDict(populate_by_name=True)

    markdown_content: str = Field(alias="markdownContent")
    overall_summary: str = Field(alias="overallSummary")
