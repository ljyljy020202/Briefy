# Agent Workflow Guide

## Overview

The Agent service (FastAPI + LangGraph) receives requests from the Spring Boot backend, processes candidate data, and returns formatted briefing content. It exposes two endpoints:

| Endpoint | Caller | Purpose |
|---|---|---|
| `POST /collections/daily` | Spring Boot scheduler / admin | Accept seed keywords, return raw job postings stub |
| `POST /briefings/generate` | Spring Boot `AgentClient` | Receive pre-scored candidate pool, filter/rank/format Markdown briefing |

**Critical design constraint — Spring owns all DB access:**

The Agent is **stateless**. It does not connect to MySQL or any other database.

- Seed keywords (aggregated from `user_briefing_preferences`) are assembled by Spring and sent in the collection request.
- Raw job postings returned by the Agent are upserted into `job_postings` by Spring (`CandidatePoolService`).
- The candidate pool for briefing generation is loaded from DB by Spring, pre-scored, and sent in the `candidatePool` field of the briefing request.
- The Agent reads only what is in the request body.

**Current 1st MVP implementation status:**

| Feature | Status |
|---|---|
| Real job-board scraping | Not implemented — Agent returns deterministic stub data |
| LLM summarization / formatting | Not implemented — briefing generation is fully deterministic; `tokenUsage` is always `{inputTokens: 0, outputTokens: 0}` |
| Job briefing filter + rank + Markdown | Implemented (deterministic, no LLM) |
| Scheduler (06:00 KST collection, 08:00 KST briefing) | Implemented, disabled by default (`briefy.scheduler.enabled: false`) |
| Company news briefing (1.5 MVP) | Not implemented; `companyIssues` is always `[]` |
| Industry / market briefing (2nd MVP) | Not implemented; `industryIssues` is always `[]` |

---

## DailyCollectWorkflow

Triggered by the Spring Boot scheduler (`DailyCollectionScheduler`) or the admin endpoint `POST /api/admin/collections/daily`. Spring aggregates seed keywords from all active `user_briefing_preferences`, sends them to the Agent, and upserts the returned job postings into the `job_postings` candidate pool.

### Responsibilities — who does what

| Step | Owner |
|---|---|
| Aggregate seed keywords from active user preferences | Spring (`DailyCollectionService`) |
| Create `collection_jobs` row and track lifecycle | Spring (`CollectionJobService`) |
| Call `POST /collections/daily` | Spring (`AgentClient`) |
| Generate / scrape raw job postings | Agent (`DailyCollectWorkflow`) |
| Deduplicate by content hash and URL | Agent (in response) + Spring (upsert ignores existing URLs) |
| Upsert `job_postings` rows | Spring (`CandidatePoolService`) |
| Mark `collection_jobs` COMPLETED / FAILED | Spring |

### Agent request (Spring → Agent)

```json
{
  "collectionJobId": 42,
  "collectDate": "2026-07-01",
  "categories": ["JOB_POSTING"],
  "seedKeywords": {
    "roles": ["백엔드 개발자", "풀스택 개발자"],
    "companies": ["네이버", "카카오", "라인"],
    "skills": ["Spring Boot", "Java", "Kotlin"],
    "locations": ["서울", "판교"],
    "experienceLevels": ["신입", "3년 이상"],
    "employmentTypes": ["정규직"],
    "industries": [],
    "keywords": []
  },
  "options": {
    "lookbackDays": 3,
    "deadlineWithinDays": 14,
    "maxItemsPerSource": 50
  }
}
```

| Field | Notes |
|---|---|
| `collectionJobId` | Echo'd back in the response so Spring can correlate |
| `seedKeywords` | Aggregated by Spring from all active `user_briefing_preferences` for each category |
| `options.lookbackDays` | Collect postings published within the last N days |
| `options.deadlineWithinDays` | Flag postings with deadline within N days |
| `options.maxItemsPerSource` | Max items to return per external source |

### Agent response (Agent → Spring)

```json
{
  "collectionJobId": 42,
  "collectDate": "2026-07-01",
  "jobPostings": [
    {
      "source": "원티드",
      "sourceUrl": "https://www.wanted.co.kr/wd/00123",
      "companyName": "네이버",
      "title": "네이버 백엔드 개발자",
      "position": "백엔드 개발자",
      "employmentType": "정규직",
      "experienceLevel": "신입",
      "location": "서울",
      "deadline": "2026-07-15",
      "skills": ["Spring Boot", "Java"],
      "roles": ["백엔드 개발자"],
      "description": "채용 공고 설명",
      "postedAt": "2026-07-01T09:00:00",
      "contentHash": "a3f2...sha256hex...64chars"
    }
  ],
  "companyIssues": [],
  "industryIssues": [],
  "stats": {
    "collectedCount": 5,
    "deduplicatedCount": 0,
    "jobPostingCount": 5,
    "companyIssueCount": 0,
    "industryIssueCount": 0
  },
  "warnings": []
}
```

After receiving this response, Spring upserts all items in `jobPostings` into the `job_postings` table via `CandidatePoolService.upsertJobPostings`. Rows with a URL that already exists are skipped.

### Graph structure (current stub)

The current agent implementation for `POST /collections/daily` is a deterministic stub in `app/services/dummy_collection.py`. It generates stable fake postings from the seed keywords without making external HTTP requests. There is no real graph / LangGraph pipeline yet.

When real scraping is implemented, the graph will be:

```
(planned)
load_seed_keywords_node       ← validate request, parse seed keywords
      ↓
collect_job_postings_node     ← fetch from job boards (Wanted, 사람인, LinkedIn, etc.)
collect_company_issues_node   ← [1.5 MVP] fetch company news
collect_industry_issues_node  ← [2nd MVP] fetch industry trends
      ↓
deduplicate_node              ← SHA-256 hash deduplication
      ↓
return_postings_node          ← return raw jobPostings list to Spring
```

Spring (not the Agent) owns the save step.

### Tools — DailyCollectWorkflow (planned)

| Tool | Phase | Purpose |
|---|---|---|
| `job_posting_fetcher` | 1st MVP | Fetch job postings from external job boards |
| `company_news_fetcher` | 1.5 MVP | Fetch news and hiring signals for target companies |
| `industry_news_fetcher` | 2nd MVP | Fetch industry/market news (information-only) |

---

## UserBriefingWorkflow

Triggered per user by Spring (`BriefingService.generateBriefing` or `generateScheduledBriefing`). Spring loads the user's preferences and today's `job_postings` from DB, pre-scores candidates, and sends the top 30 as a `candidatePool` in the request. The Agent filters, re-ranks, selects the top 10, and assembles a Markdown briefing — all deterministically.

**The Agent does not call external sources and does not access the database.**

### Responsibilities — who does what

| Step | Owner |
|---|---|
| Load user preferences from `user_briefing_preferences` | Spring |
| Load today's `job_postings` candidate pool from DB | Spring |
| Pre-score candidates against user preferences | Spring (`BriefingService.scorePosting`) |
| Send top 30 pre-scored candidates to Agent | Spring (`AgentClient`) |
| Filter (past-deadline, missing title/URL) | Agent (`filter_job_postings_node`) |
| Re-rank by `preScore + agentScore` | Agent (`rank_job_postings_node`) |
| Select top 10 | Agent (`select_top_items_node`) |
| Write Markdown report and article list | Agent (`write_markdown_report_node`) |
| Save `briefing_reports` and `briefing_articles` rows | Spring |

### Agent request (Spring → Agent)

```json
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
  "briefingDate": "2026-07-01",
  "tone": "easy",
  "candidatePool": {
    "jobPostings": [
      {
        "id": 1,
        "source": "원티드",
        "sourceUrl": "https://www.wanted.co.kr/wd/00001",
        "companyName": "네이버",
        "title": "네이버 백엔드 개발자",
        "position": "백엔드 개발자",
        "employmentType": "정규직",
        "experienceLevel": "신입",
        "location": "서울",
        "deadline": "2026-07-15",
        "skills": ["Spring Boot", "Java"],
        "roles": ["백엔드 개발자"],
        "description": "채용 공고 설명",
        "postedAt": "2026-07-01T09:00:00",
        "collectedDate": "2026-07-01",
        "contentHash": "a3f2...sha256hex...64chars",
        "preScore": 75
      }
    ],
    "companyIssues": [],
    "industryIssues": []
  }
}
```

| Field | Notes |
|---|---|
| `candidatePool.jobPostings` | Top 30 pre-scored candidates from `job_postings` table, selected and scored by Spring |
| `candidatePool.jobPostings[].preScore` | Score assigned by Spring's `BriefingService.scorePosting()` based on user preferences |
| `candidatePool.companyIssues` | Always `[]` in 1st MVP |
| `candidatePool.industryIssues` | Always `[]` in 1st MVP |
| `userId` | Forwarded for logging/tracing only; Agent does not persist it |
| `tone` | Forwarded from the frontend or scheduler; not used in current deterministic implementation |

### Pre-scoring logic (Spring side)

Spring scores each `job_postings` row before sending:

| Match | Score |
|---|---|
| Role or title matches `preference.roles` | +30 |
| Company matches `preference.companies` | +15 |
| Each matching skill (max 5 skills) | +5 each (max +25) |
| Location matches `preference.locations` | +10 |
| Experience level matches | +10 |
| Employment type matches | +5 |
| Deadline within 7 days | +10 |
| Collected within last 3 days | +5 |

Spring sorts by `preScore` descending, takes the top 30, and sends them as `candidatePool.jobPostings`.

### Agent re-scoring logic

Inside the Agent, each candidate is scored again (`agentScore`) using the same dimensions but different weights. The `totalScore = preScore + agentScore` is used for final ranking:

| Match | Agent score |
|---|---|
| Role match (title or position) | +30 |
| Company match | +15 |
| Each matching skill (max 5 skills) | +5 each (max +25) |
| Location match | +10 |
| Experience level match | +10 |
| Employment type match | +5 |
| Deadline within 7 days | +10 |

### Graph structure (implemented)

```python
load_request_node
      ↓
filter_job_postings_node      ← remove past-deadline or missing title/sourceUrl
      ↓
rank_job_postings_node        ← totalScore = preScore + agentScore; sort desc
      ↓
select_top_items_node         ← top 10
      ↓
write_markdown_report_node    ← build title, summary, Markdown content, articles list
      ↓
quality_check_node            ← no-op; hook for future validation
```

All nodes are deterministic — no LLM calls. `tokenUsage` is always `{inputTokens: 0, outputTokens: 0}` in the current implementation.

### Agent response (Agent → Spring)

```json
{
  "title": "오늘의 채용 브리핑 — 백엔드 개발자 (2026-07-01)",
  "summary": "2026-07-01 기준, 네이버·카카오에서 추천 공고 3건을 선별했습니다.",
  "content": "## 오늘의 핵심 요약\n\n...\n\n## 🏆 추천 공고 TOP 3\n\n...\n\n## ⏰ 마감 임박 공고\n\n...\n\n## 💡 오늘의 지원 추천 액션\n\n...",
  "articles": [
    {
      "title": "네이버 백엔드 개발자",
      "source": "원티드",
      "url": "https://www.wanted.co.kr/wd/00001",
      "summary": "네이버 — 백엔드 개발자 채용",
      "whyItMatters": "관심 기업(네이버) · 백엔드 개발자 포지션 매칭 · 스킬 매칭: Spring Boot, Java",
      "publishedAt": "2026-07-01T09:00:00",
      "companyName": "네이버"
    }
  ],
  "tokenUsage": {
    "inputTokens": 0,
    "outputTokens": 0
  }
}
```

Spring saves `title`, `summary`, `content`, `tokenUsage` to `briefing_reports`, and each `articles` item to `briefing_articles`.

---

## Request/Response Flow Summary

```
[Daily Collection]
Spring Scheduler / POST /api/admin/collections/daily
    │
    ├─ Spring: aggregate seed keywords from user_briefing_preferences
    ├─ Spring: create collection_jobs row (PENDING → PROCESSING)
    ├─ Spring → Agent: POST /collections/daily (with seedKeywords)
    ├─ Agent: return raw jobPostings (deterministic stub in 1st MVP)
    ├─ Spring: upsert job_postings via CandidatePoolService
    └─ Spring: mark collection_jobs COMPLETED (or FAILED)

[Briefing Generation — per user]
User POST /api/briefings/generate  OR  Spring BriefingScheduler
    │
    ├─ Spring: load user preferences from user_briefing_preferences
    ├─ Spring: load job_postings for today's date
    ├─ Spring: pre-score candidates; take top 30
    ├─ Spring: create briefing_jobs row (PENDING → PROCESSING)
    ├─ Spring → Agent: POST /briefings/generate (with candidatePool)
    ├─ Agent: filter → re-rank → select top 10 → write Markdown
    ├─ Spring: save briefing_reports + briefing_articles
    └─ Spring: mark briefing_jobs COMPLETED (or FAILED)
```

The Agent server is called **only by the Spring Boot backend** (`AgentClient`). The frontend never calls the Agent directly. Agent endpoints do not use the `/api` prefix.

---

## Development

### Running Locally

```bash
cd apps/agent
poetry install
poetry run uvicorn app.main:app --reload --port 8000
```

### Testing

```bash
# Collection endpoint tests
poetry run pytest tests/test_collections.py -v

# Briefing generation tests (basic)
poetry run pytest tests/test_briefings.py -v

# Briefing generation tests (candidatePool-based workflow)
poetry run pytest tests/test_user_briefing.py -v

# All tests
poetry run pytest

# Lint
poetry run ruff check .
```

### Local Integration Test (end-to-end)

Requires both backend (`./gradlew bootRun --args='--spring.profiles.active=local'`) and agent (`poetry run uvicorn app.main:app --reload --port 8000`) running.

```bash
# 1. Trigger daily collection (admin endpoint)
curl -s -X POST http://localhost:8080/api/admin/collections/daily \
  -H "Content-Type: application/json" \
  -d '{"collectDate": "2026-07-01", "categories": ["JOB_POSTING"]}' | jq .

# 2. Generate briefing (requires auth cookie; use browser session or Postman)
curl -s -X POST http://localhost:8080/api/briefings/generate \
  -H "Content-Type: application/json" \
  -H "Cookie: briefy_access_token=<your-jwt>" \
  -d '{"tone": "easy"}' | jq .

# 3. List briefing reports
curl -s http://localhost:8080/api/briefings \
  -H "Cookie: briefy_access_token=<your-jwt>" | jq .

# 4. Get briefing detail
curl -s http://localhost:8080/api/briefings/<reportId> \
  -H "Cookie: briefy_access_token=<your-jwt>" | jq .
```

### Debugging

```bash
export LOG_LEVEL=DEBUG
poetry run uvicorn app.main:app --reload --log-level debug
```

---

## Future: Adding Real Scraping and LLM

When the stub is replaced with real scraping and LLM summarization:

1. **DailyCollectWorkflow** — implement `collect_job_postings_node` using `@tool`-decorated scrapers for Wanted, 사람인, LinkedIn, etc.
2. **UserBriefingWorkflow** — add `summarize_candidates_node` (LLM call per posting for `whyItMatters`) and `format_briefing_node` (LLM for final Markdown assembly). Update `tokenUsage` fields with real counts.
3. **Do not add DB access to the Agent** — keep Spring as the single DB owner. Pass data in request bodies.
4. **Do not mix future phases into current graphs** — create `company_briefing_graph.py` (1.5 MVP) and `industry_briefing_graph.py` (2nd MVP) as new files.

**Phase guidance:**

| Graph file | Phase | Notes |
|---|---|---|
| `dummy_collection.py` (via `/collections/daily`) | 1st MVP (current) | Active; deterministic stub |
| `user_briefing_graph.py` (via `/briefings/generate`) | 1st MVP (current) | Active; deterministic, no LLM |
| Real `daily_collect_graph.py` | 1st MVP (future) | Replace stub with real scrapers |
| LLM-based `user_briefing_graph.py` | 1st MVP (future) | Add LLM summarization nodes |
| `company_briefing_graph.py` | 1.5 MVP | Company news, hiring changes, earnings summaries |
| `industry_briefing_graph.py` | 2nd MVP | IT/AI, semiconductor, platform, finance; information-only |
