# Agent Workflow Guide

## Overview

The Agent service orchestrates LangGraph workflows to generate personalized briefings. It receives requests from the Backend, processes data asynchronously, and returns AI-generated content.

## LangGraph Architecture

The agent uses LangGraph's `StateGraph` to define multi-step AI workflows. Each graph handles a specific workflow (e.g., briefing generation).

### State Definition

```python
from typing import TypedDict

class BriefingState(TypedDict):
    user_id: int
    topics: list[dict]   # [{"name": "AI/LLM", "keywords": ["OpenAI", ...]}, ...]
    date: str            # YYYY-MM-DD
    tone: str
    raw_news: list[dict]       # fetched news items
    processed_data: list[dict] # filtered & categorized articles
    briefing_content: str      # final LLM output (Markdown)
```

### Graph Structure

```
fetch_news_node
      ↓
categorize_node
      ↓
generate_briefing_node
      ↓
format_output_node
```

## Tools

Tools are functions decorated with `@tool` that the LLM can call. They enable the agent to fetch external data or perform computations.

### Available Tools

#### news_fetcher
Fetch news articles from configured sources.

```python
@tool
def news_fetcher(categories: list[str], language: str) -> list[dict]:
    """Fetch relevant news articles."""
```

#### weather_fetcher
Fetch weather information for user's location.

```python
@tool
def weather_fetcher(location: str) -> dict:
    """Fetch weather forecast."""
```

#### market_data_fetcher
Fetch stock market & cryptocurrency data.

```python
@tool
def market_data_fetcher(symbols: list[str]) -> list[dict]:
    """Fetch market data."""
```

## Request/Response Flow

The Agent server is called **only by the Spring Boot backend** (`AgentClient`).  
The frontend never calls the Agent directly.  
Agent endpoints do **not** use the `/api` prefix.

### Request from Backend

```http
POST /briefings/generate
Content-Type: application/json

{
  "userId": 1,
  "topics": [
    {
      "name": "AI/LLM",
      "keywords": ["OpenAI", "Claude", "LangGraph"]
    },
    {
      "name": "Backend/Spring",
      "keywords": ["Spring Boot", "Redis"]
    }
  ],
  "date": "2026-06-05",
  "tone": "easy"
}
```

| Field | Notes |
|---|---|
| `userId` | For logging/tracing only; Agent does not persist it |
| `topics` | Active `user_topics` records for this user, grouped by topic name |
| `date` | ISO-8601 date the briefing covers (`YYYY-MM-DD`) |
| `tone` | Forwarded from the frontend or scheduler (e.g. `"easy"`, `"professional"`) |

### Response

```json
{
  "title": "오늘의 AI/백엔드 브리핑",
  "summary": "오늘은 OpenAI, Claude Code, Spring 관련 업데이트가 주요 이슈였습니다.",
  "content": "## 오늘의 핵심 요약\n\n...",
  "articles": [
    {
      "title": "Example Article",
      "source": "OpenAI Blog",
      "url": "https://example.com",
      "summary": "One-paragraph agent-generated summary.",
      "whyItMatters": "Relevance explanation for the user's topics.",
      "publishedAt": "2026-06-05T00:00:00"
    }
  ],
  "tokenUsage": {
    "inputTokens": 8000,
    "outputTokens": 1500
  }
}
```

| Field | Notes |
|---|---|
| `content` | Full briefing in **Markdown** |
| `articles` | May be an empty array if no articles were found |
| `tokenUsage` | Stored in `briefing_reports.token_input` / `token_output` for cost tracking |

## Development

### Running Locally

```bash
cd apps/agent
poetry install
poetry run uvicorn app.main:app --reload --port 8000
```

### Testing Workflows

```bash
poetry run pytest tests/test_graph.py -v
```

### Debugging

Enable debug logging:
```bash
export LOG_LEVEL=DEBUG
poetry run uvicorn app.main:app --reload --log-level debug
```

## Adding New Workflows

1. Create new `StateGraph` in `app/graph/new_workflow.py`
2. Define state schema and nodes
3. Add node functions in `app/tools/`
4. Register in `app/main.py`
5. Add tests in `tests/`
