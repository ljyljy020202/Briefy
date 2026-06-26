# Agent Workflow Guide

## Overview

The Agent service orchestrates LangGraph workflows to generate personalized briefings. It receives requests from the Backend, processes data, and returns AI-generated content.

**Current 1st MVP focus:** Job briefing for developer and general job seekers. The workflow collects job postings matching the user's preferences, deduplicates and ranks them, generates matching reasons, and formats a Markdown briefing.

**Later phases:** Company news briefing (1.5 MVP) and industry/market briefing (2nd MVP) will be implemented as separate graphs in `app/graph/`. Do not mix them into the job briefing graph.

## LangGraph Architecture

The agent uses LangGraph's `StateGraph` to define multi-step AI workflows. Each graph handles a specific workflow (e.g., briefing generation).

### State Definition (1st MVP — Job Briefing)

```python
from typing import TypedDict

class JobBriefingState(TypedDict):
    user_id: int
    topics: list[dict]            # [{"name": "Target Role", "keywords": ["백엔드 개발자"]}, ...]
    date: str                     # YYYY-MM-DD
    tone: str
    raw_postings: list[dict]      # collected job postings from sources
    deduplicated: list[dict]      # postings after URL/title deduplication
    ranked_postings: list[dict]   # postings ranked by user preference match score
    matching_reasons: list[dict]  # LLM-generated match explanation per posting
    briefing_content: str         # final Markdown briefing
```

### Graph Structure (1st MVP — Job Briefing)

```
collect_postings_node       ← fetches from job boards based on user preferences
      ↓
deduplicate_node            ← removes duplicate URLs / very similar titles
      ↓
rank_by_preference_node     ← scores postings against role / company / skill / location
      ↓
summarize_postings_node     ← LLM summarises each selected posting
      ↓
generate_matching_reasons_node  ← LLM explains why each posting matches the user
      ↓
format_briefing_node        ← assembles Markdown output with new postings + deadline-near section
```

**Planned later graphs (not for 1st MVP):**

| Graph file | Phase |
|---|---|
| `company_briefing_graph.py` | 1.5 MVP — company news, hiring changes, earnings summaries |
| `industry_briefing_graph.py` | 2nd MVP — IT/AI, semiconductor, platform, finance, content |

## Tools

Tools are functions decorated with `@tool` that the LLM can call. They enable the agent to fetch external data or perform computations.

### 1st MVP Tools — Job Briefing

#### job_posting_fetcher
Collect job postings from configured sources (e.g. Wanted, JobKorea, LinkedIn) filtered by user preferences.

```python
@tool
def job_posting_fetcher(
    roles: list[str],
    companies: list[str],
    skills: list[str],
    locations: list[str],
    experience_levels: list[str],
    employment_types: list[str],
) -> list[dict]:
    """Fetch job postings matching the user's preference profile."""
```

#### deadline_checker
Flag postings whose application deadline falls within the next N days.

```python
@tool
def deadline_checker(postings: list[dict], days_threshold: int = 3) -> list[dict]:
    """Return postings that are closing soon."""
```

### Later Phase Tools (not for 1st MVP)

| Tool | Phase | Purpose |
|---|---|---|
| `company_news_fetcher` | 1.5 MVP | Fetch news and hiring signals for target companies |
| `industry_news_fetcher` | 2nd MVP | Fetch industry/market news (IT/AI, semiconductor, platform, finance, content) |

> **Investment content rule:** Industry/market briefing content must be information-only summaries. Never generate or suggest buy/sell recommendations in any prompt, output, or tool response.

## Request/Response Flow

The Agent server is called **only by the Spring Boot backend** (`AgentClient`).  
The frontend never calls the Agent directly.  
Agent endpoints do **not** use the `/api` prefix.

### Request from Backend (1st MVP — Job Briefing)

```http
POST /briefings/generate
Content-Type: application/json

{
  "userId": 1,
  "topics": [
    {
      "name": "Target Role",
      "keywords": ["백엔드 개발자", "풀스택 개발자"]
    },
    {
      "name": "Target Companies",
      "keywords": ["네이버", "카카오", "라인"]
    },
    {
      "name": "Skills / Competencies",
      "keywords": ["Spring Boot", "Java", "Kotlin"]
    },
    {
      "name": "Location",
      "keywords": ["서울", "판교"]
    },
    {
      "name": "Experience Level",
      "keywords": ["신입", "3년 이상"]
    }
  ],
  "date": "2026-06-05",
  "tone": "easy"
}
```

| Field | Notes |
|---|---|
| `userId` | For logging/tracing only; Agent does not persist it |
| `topics` | Active `user_topics` records for this user, grouped by preference category name |
| `date` | ISO-8601 date the briefing covers (`YYYY-MM-DD`) |
| `tone` | Forwarded from the frontend or scheduler (e.g. `"easy"`, `"professional"`) |

### Response (1st MVP — Job Briefing)

```json
{
  "title": "오늘의 채용 브리핑 — 백엔드 개발자",
  "summary": "오늘 네이버·카카오·라인에서 신규 백엔드 공고 3건, 마감 임박 공고 2건이 있습니다.",
  "content": "## 오늘의 채용 요약\n\n### 신규 공고\n...\n\n### 마감 임박 공고\n...\n\n### 추천 액션\n...",
  "articles": [
    {
      "title": "네이버 — 백엔드 개발자 (Spring Boot) 채용",
      "source": "채용 플랫폼",
      "url": "https://example.com/job/123",
      "summary": "네이버 서치 플랫폼팀에서 Spring Boot · Java 경력 3년 이상 백엔드 개발자를 모집합니다.",
      "whyItMatters": "목표 회사(네이버)이며 핵심 스킬(Spring Boot, Java)과 정확히 매칭됩니다.",
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
| `content` | Full briefing in **Markdown**; must include sections for new postings, deadline-near postings, and recommended actions |
| `articles` | Each element is one job posting. May be empty if no postings matched. |
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
