# Agent Workflow Guide

## Overview

The Agent service orchestrates LangGraph workflows to generate personalized briefings. It receives requests from the Backend, processes data, and returns AI-generated content.

**Design principle:** The Agent separates data collection from briefing generation into two independent workflows. This avoids fetching the internet from scratch for every user and every briefing request.

| Workflow | Trigger | Responsibility |
|---|---|---|
| `DailyCollectWorkflow` | Backend daily scheduler (`POST /collections/daily`) | Collect common candidate pools from job boards and news sources; deduplicate; store in DB |
| `UserBriefingWorkflow` | Backend per-user briefing job (`POST /briefings/generate`) | Load pool from DB; filter and rank by user preferences; summarize with LLM; write Markdown report |

**Current 1st MVP focus:** Job briefing for developer and general job seekers. `DailyCollectWorkflow` collects `job_postings`; `UserBriefingWorkflow` filters them by user preferences, ranks them, generates matching reasons, and formats a Markdown briefing.

**Later phases:** Company news briefing (1.5 MVP) and industry/market briefing (2nd MVP) will be implemented as additional collection and briefing graph pairs in `app/graph/`. Do not mix them into the job briefing graphs.

---

## DailyCollectWorkflow

Runs once per day, triggered by the Spring Boot scheduler. Aggregates seed keywords from all active user preferences, fetches candidates from external sources, deduplicates, and saves to the shared candidate pool tables (`job_postings`, `company_issues`, `industry_issues`).

### State Definition

```python
from typing import TypedDict

class DailyCollectState(TypedDict):
    collect_date: str                      # YYYY-MM-DD date being collected for
    categories: list[str]                  # e.g. ["JOB_POSTING"]
    seed_keywords: dict                    # aggregated from all active user preferences
                                           # {"roles": [...], "companies": [...], "skills": [...]}
    raw_job_postings: list[dict]           # fetched from job board APIs / scrapers
    raw_company_issues: list[dict]         # fetched from news / RSS sources (1.5 MVP)
    raw_industry_issues: list[dict]        # fetched from news / RSS sources (2nd MVP)
    deduplicated_postings: list[dict]      # after URL + content hash deduplication
    deduplicated_company_issues: list[dict]
    deduplicated_industry_issues: list[dict]
    saved_counts: dict                     # {"jobPostings": N, "companyIssues": M, ...}
    duration_ms: int                       # wall-clock time for the run
```

### Graph Structure

```
load_seed_keywords_node
      ↓
      ├─ collect_job_postings_node       ← fetch from job boards using seed roles/companies/skills
      ├─ collect_company_issues_node     ← fetch company news from RSS/news APIs  [1.5 MVP]
      └─ collect_industry_issues_node    ← fetch industry trends from RSS/news APIs [2nd MVP]
      ↓
deduplicate_node                        ← remove duplicate URLs; skip by content_hash
      ↓
save_to_db_node                         ← upsert job_postings / company_issues / industry_issues
      ↓
return_summary_node                     ← return {savedCounts, durationMs}
```

The three collection nodes can run in parallel. In the 1st MVP, only `collect_job_postings_node` is active; the other two are no-ops.

### Nodes

#### `load_seed_keywords_node`
Queries all active `user_briefing_preferences` rows for each requested category and aggregates unique values across all users into a seed keyword set.

```python
# Example seed_keywords output for JOB_POSTING:
{
    "roles": ["백엔드 개발자", "풀스택 개발자", "프론트엔드 개발자", ...],
    "companies": ["네이버", "카카오", "라인", "토스", ...],
    "skills": ["Spring Boot", "Java", "React", "Python", ...]
}
```

This is a deterministic DB read — do not call LLM here.

#### `collect_job_postings_node`
Fetches job postings from configured external sources using the aggregated seed keywords.

```python
@tool
def job_posting_fetcher(
    roles: list[str],
    companies: list[str],
    skills: list[str],
) -> list[dict]:
    """Fetch job postings from job boards matching the seed keyword pool."""
```

Sources include Wanted, 사람인, LinkedIn, and company career pages. Each raw posting includes: `title`, `company`, `url`, `location`, `deadline`, `description`, `published_at`.

#### `collect_company_issues_node` (1.5 MVP)
Fetches company news and hiring signals from RSS feeds, news APIs, and company investor relations pages.

#### `collect_industry_issues_node` (2nd MVP)
Fetches industry / market trend articles from news APIs. Content must be information-only; never include buy/sell signals.

#### `deduplicate_node`
Deterministic deduplication — do not call LLM here.

- **URL deduplication:** skip postings whose `url` already exists in `job_postings`.
- **Content hash:** compute SHA-256 of `(company + title + deadline)` to detect reposted identical listings under different URLs.
- **Title similarity:** optionally flag near-duplicate titles from the same company for human review (not blocking in MVP).

#### `save_to_db_node`
Upserts deduplicated items into the candidate pool tables. Skips rows that already exist by URL. Records `collected_date = collect_date`.

### Tools — DailyCollectWorkflow

| Tool | Phase | Purpose |
|---|---|---|
| `job_posting_fetcher` | 1st MVP | Fetch job postings from external job boards |
| `deadline_checker` | 1st MVP | Flag postings whose deadline falls within N days |
| `company_news_fetcher` | 1.5 MVP | Fetch news and hiring signals for target companies |
| `industry_news_fetcher` | 2nd MVP | Fetch industry/market news (information-only) |

---

## UserBriefingWorkflow

Runs per user per day, triggered by the backend briefing job (`POST /briefings/generate`). Loads the pre-collected candidate pool from DB, filters and ranks candidates using the user's preferences, generates summaries and matching reasons with LLM, and assembles a Markdown briefing.

Does **not** call external sources. All data comes from the candidate pool tables populated by `DailyCollectWorkflow`.

### State Definition

```python
from typing import TypedDict

class UserBriefingState(TypedDict):
    user_id: int
    briefing_date: str              # YYYY-MM-DD
    category: str                   # e.g. "JOB_POSTING"
    preference: dict                # user's preference_json from user_briefing_preferences
    tone: str                       # "easy" | "professional"
    candidate_pool: list[dict]      # loaded from job_postings for briefing_date
    filtered_candidates: list[dict] # after preference-based filtering
    ranked_candidates: list[dict]   # sorted by match score (descending)
    top_candidates: list[dict]      # top N selected for the report
    summaries: list[dict]           # LLM-generated per-item summaries + whyItMatters
    briefing_content: str           # final Markdown briefing
    quality_ok: bool                # quality gate: non-empty, within token budget
```

### Graph Structure

```
load_preferences_node           ← read user's preference_json from DB
      ↓
load_candidate_pool_node        ← query job_postings WHERE collected_date = briefing_date
      ↓
filter_by_preferences_node      ← deterministic: match by role / company / skill / location
      ↓
rank_by_score_node              ← deterministic: score match strength per candidate
      ↓
select_top_candidates_node      ← deterministic: top N by score; prioritise deadline-near
      ↓
summarize_candidates_node       ← LLM: summarise each item; generate whyItMatters
      ↓
format_briefing_node            ← LLM: assemble Markdown (new postings · deadline-near · actions)
      ↓
quality_check_node              ← verify non-empty sections; check token budget
      ↓
return_report_node              ← return {title, summary, content, articles, tokenUsage}
```

### Nodes

#### `load_preferences_node`
Reads the user's `preference_json` for the requested category. Passed in from the backend request — this node validates and parses it.

#### `load_candidate_pool_node`
Queries `job_postings` (for `JOB_POSTING` category) WHERE `collected_date = briefing_date`. Returns all postings collected for the day.

This is a deterministic DB read — do not call LLM here.

#### `filter_by_preferences_node`
Applies the user's preference conditions to the candidate pool. Deterministic — do not call LLM here.

Filtering logic for `JOB_POSTING`:
- **Role match:** posting's `roles` field overlaps with user `preference.roles` (case-insensitive, partial match allowed)
- **Company match:** posting's `company` is in user `preference.companies`
- **Skill match:** posting's `skills` field contains any of user `preference.skills`
- **Location match:** posting's `location` overlaps with user `preference.locations`
- **Experience match:** posting's `experience_level` matches user `preference.experienceLevels`
- **Employment match:** posting's `employment_type` matches user `preference.employmentTypes`

A posting passes the filter if it matches at least one condition in any dimension. Stricter multi-dimension matching can be applied in the ranking step.

#### `rank_by_score_node`
Assigns a numeric match score to each filtered candidate. Deterministic — do not call LLM here.

Scoring for `JOB_POSTING`:
- +3 points: company is in user's `companies` list
- +2 points: role is in user's `roles` list
- +2 points: each matching skill (capped at +6)
- +1 point: location matches
- +1 point: experience level matches
- +2 points: deadline within 3 days (urgency bonus)

#### `select_top_candidates_node`
Picks the top N candidates by score. Also separates into two sections: `new_postings` and `deadline_near_postings`. Deterministic.

Default: top 10 total, with up to 3 deadline-near postings promoted to a separate section.

#### `summarize_candidates_node`
Calls LLM for each selected candidate to produce:
- A concise one-paragraph `summary` of the posting
- A `whyItMatters` explanation tailored to the user's preference

This is the primary LLM call for per-item content.

#### `format_briefing_node`
Calls LLM to assemble the final Markdown report with:
- Section: 신규 공고 (new postings with summaries and match reasons)
- Section: 마감 임박 공고 (deadline-near postings)
- Section: 오늘의 추천 액션 (recommended next steps)

#### `quality_check_node`
Verifies the output non-programmatically:
- At least one posting in the report
- `content` is non-empty and parseable Markdown
- `tokenUsage.outputTokens` is within configured budget

If the check fails, the node can retry `format_briefing_node` once or return an empty report with an error note.

### Tools — UserBriefingWorkflow

| Tool | Phase | Purpose |
|---|---|---|
| `preference_matcher` | 1st MVP | Deterministic score calculation against user preferences |
| `deadline_checker` | 1st MVP | Flag postings whose deadline is within N days |

> Do not add web-fetching tools to `UserBriefingWorkflow`. All external data must be in the candidate pool by the time this workflow runs.

---

## Request/Response Flow

The Agent server is called **only by the Spring Boot backend** (`AgentClient`).  
The frontend never calls the Agent directly.  
Agent endpoints do **not** use the `/api` prefix.

### Daily Collection Request (Backend Scheduler → Agent)

```http
POST /collections/daily
Content-Type: application/json

{
  "collectDate": "2026-06-28",
  "categories": ["JOB_POSTING"]
}
```

### Daily Collection Response

```json
{
  "collectDate": "2026-06-28",
  "savedCounts": {
    "jobPostings": 120,
    "companyIssues": 0,
    "industryIssues": 0
  },
  "durationMs": 45230
}
```

---

### Briefing Generation Request (Backend `AgentClient` → Agent)

```http
POST /briefings/generate
Content-Type: application/json

{
  "userId": 1,
  "category": "JOB_POSTING",
  "preference": {
    "roles": ["백엔드 개발자", "풀스택 개발자"],
    "companies": ["네이버", "카카오", "라인"],
    "skills": ["Spring Boot", "Java", "Kotlin"],
    "locations": ["서울", "판교"],
    "experienceLevels": ["신입", "3년 이상"],
    "employmentTypes": ["정규직"]
  },
  "briefingDate": "2026-06-28",
  "tone": "easy"
}
```

| Field | Notes |
|---|---|
| `userId` | For logging/tracing only; Agent does not persist it |
| `category` | Which briefing type to generate (matches `briefing_categories.code`) |
| `preference` | The user's full `preference_json` from `user_briefing_preferences` |
| `briefingDate` | ISO-8601 date the briefing covers (`YYYY-MM-DD`) |
| `tone` | Forwarded from the frontend or scheduler |

### Briefing Generation Response

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
      "publishedAt": "2026-06-28T00:00:00"
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

---

## Development

### Running Locally

```bash
cd apps/agent
poetry install
poetry run uvicorn app.main:app --reload --port 8000
```

### Testing Workflows

```bash
# Test collection workflow
poetry run pytest tests/test_daily_collect_graph.py -v

# Test briefing generation workflow
poetry run pytest tests/test_user_briefing_graph.py -v

# All tests
poetry run pytest
```

### Debugging

Enable debug logging:
```bash
export LOG_LEVEL=DEBUG
poetry run uvicorn app.main:app --reload --log-level debug
```

---

## Adding New Workflows

1. Create a new `StateGraph` in `app/graph/new_workflow_graph.py`
2. Define the state schema as a `TypedDict`
3. Add node functions (deterministic logic) and tool functions (`@tool` for LLM-callable operations) in `app/tools/`
4. Register the new router in `app/api/` and mount in `app/main.py`
5. Add tests in `tests/`

**Phase guidance for planned graphs:**

| Graph file | Phase | Notes |
|---|---|---|
| `daily_collect_graph.py` | 1st MVP | Active; collects `job_postings` |
| `user_briefing_graph.py` | 1st MVP | Active; generates job briefings |
| `company_briefing_graph.py` | 1.5 MVP | Company news, hiring changes, earnings summaries |
| `industry_briefing_graph.py` | 2nd MVP | IT/AI, semiconductor, platform, finance, content; information-only |
