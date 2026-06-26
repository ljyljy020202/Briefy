"""LangGraph tool stub: identify postings with imminent deadlines.

Accepts a list of raw posting dicts and returns those whose
deadline falls within the given number of days.
"""

from langchain_core.tools import tool


@tool
def deadline_checker(postings: list[dict], within_days: int = 3) -> list[dict]:
    """Return postings whose deadline is within `within_days` days from today.

    Expects each posting dict to have a `deadline` key (ISO date string or None).
    """
    raise NotImplementedError("Deadline checker not yet implemented")
