"""LangGraph scaffold for the job briefing workflow.

Intended pipeline (not yet wired):
  collect_postings → deduplicate → rank_by_preference
      → summarize → generate_matching_reasons → format_briefing

Replace apps/agent/app/services/dummy_briefing.py with this graph
once real job-board API access is available.
"""

from typing import TypedDict


class JobBriefingState(TypedDict):
    user_id: int
    category: str
    preference: dict
    briefing_date: str
    tone: str
    raw_postings: list[dict]
    deduplicated: list[dict]
    ranked_postings: list[dict]
    matching_reasons: list[dict]
    briefing_content: str


def collect_postings(state: JobBriefingState) -> dict:
    raise NotImplementedError


def deduplicate(state: JobBriefingState) -> dict:
    raise NotImplementedError


def rank_by_preference(state: JobBriefingState) -> dict:
    raise NotImplementedError


def summarize_postings(state: JobBriefingState) -> dict:
    raise NotImplementedError


def generate_matching_reasons(state: JobBriefingState) -> dict:
    raise NotImplementedError


def format_briefing(state: JobBriefingState) -> dict:
    raise NotImplementedError


# Future graph assembly:
# from langgraph.graph import StateGraph
# graph = StateGraph(JobBriefingState)
# graph.add_node("collect_postings", collect_postings)
# graph.add_node("deduplicate", deduplicate)
# graph.add_node("rank_by_preference", rank_by_preference)
# graph.add_node("summarize_postings", summarize_postings)
# graph.add_node("generate_matching_reasons", generate_matching_reasons)
# graph.add_node("format_briefing", format_briefing)
# graph.set_entry_point("collect_postings")
# graph.add_edge("collect_postings", "deduplicate")
# graph.add_edge("deduplicate", "rank_by_preference")
# graph.add_edge("rank_by_preference", "summarize_postings")
# graph.add_edge("summarize_postings", "generate_matching_reasons")
# graph.add_edge("generate_matching_reasons", "format_briefing")
# job_briefing_app = graph.compile()
