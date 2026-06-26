"""LangGraph tool stub: fetch job postings from external boards.

Replace with real API calls to Wanted, JobKorea, LinkedIn, etc.
"""

from langchain_core.tools import tool


@tool
def job_posting_fetcher(
    roles: list[str],
    companies: list[str],
    skills: list[str],
    locations: list[str],
    experience_levels: list[str],
    employment_types: list[str],
) -> list[dict]:
    """Fetch job postings matching the user's preference profile.

    Returns a list of raw posting dicts, each with keys:
    title, company, source, url, description, published_at, deadline.
    """
    raise NotImplementedError("Real job-board API integration not yet implemented")
