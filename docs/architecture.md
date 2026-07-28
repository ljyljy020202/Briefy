# Briefy Architecture

## System Overview

Briefy is a personalized AI daily briefing service composed of three independent microservices. The **current 1st MVP** focuses on job briefing for developer and general job seekers; later phases add interested company briefing (1.5 MVP) and industry/market briefing (2nd MVP).

```
┌─────────────────────────────────────────────────────────────────┐
│  User                                                             │
└─────────────────┬───────────────────────────────────────────────┘
                  │
                  ▼
┌─────────────────────────────────────────────────────────────────┐
│  Frontend (Next.js / Vercel)                                    │
│  - Google OAuth login (redirects to backend, receives JWT)      │
│  - Briefing display & personalization UI                        │
└─────────────────┬───────────────────────────────────────────────┘
                  │ REST API
                  ▼
┌─────────────────────────────────────────────────────────────────┐
│  Backend (Spring Boot / AWS EC2)                                │
│  - User & subscription management                               │
│  - Briefing orchestration & caching                             │
│  ├─→ MySQL: user data, preferences, briefing history           │
│  └─→ Redis: cache, rate limiting                               │
└─────────────────┬───────────────────────────────────────────────┘
                  │ REST API
                  ▼
┌─────────────────────────────────────────────────────────────────┐
│  Agent (FastAPI + LangGraph / AWS EC2)                          │
│  - DailyCollectWorkflow: collect & store job postings           │
│  - UserBriefingWorkflow: filter, rank & generate per user       │
│  - LangGraph for multi-step AI orchestration                    │
└─────────────────┬───────────────────────────────────────────────┘
                  │
                  ▼
              OpenAI API
```

## Service Responsibilities

### Frontend
- Next.js 15 (App Router) with TypeScript
- Server & client components
- shadcn/ui component library
- Tailwind CSS styling
- REST client for backend communication

### Backend
- Spring Boot 3.4 with Java 21
- REST API for frontend
- JPA entities & repository pattern
- WebClient for Agent calls (briefing generation)
- Business logic orchestration

### Agent
- FastAPI + LangGraph for workflow orchestration
- Tool definitions for data retrieval & processing
- Generative AI integration (OpenAI)
- Async request handling

## Data Flow for Daily Collection

Triggered once per day at **06:00 KST** by the Spring Boot scheduler (`DailyCollectionScheduler`), before per-user briefing jobs run at **08:00 KST**. The two workflows share the same `job_postings` candidate pool — collection populates it, briefing generation reads from it.

1. Backend daily scheduler calls Agent: `POST /collections/daily` for today's date
2. Agent runs `DailyCollectWorkflow` via LangGraph:
   - Aggregate seed keywords from all active `user_briefing_preferences` across all users
   - Fetch job postings from external sources (job boards, company career pages) using seed keywords
   - Deduplicate by URL and content hash; skip already-stored postings
   - Save new postings to `job_postings` table
3. Agent returns `{ savedCounts, durationMs }` to Backend
4. Backend logs the collection result; user briefing generation for the day reads from the stored pool

## Candidate Pool Policy

Before briefing generation, the Backend applies a structured candidate selection policy to the `job_postings` table:

### CandidateType Classification

| Type | Condition |
|---|---|
| `NEW` | `publishedAt` or `collectedDate` ≤ 3 days from today |
| `URGENT` | Not NEW, and deadline within 7 days |
| `EVERGREEN` | Active, un-expired, not NEW or URGENT |

### Pre-scoring (Spring)

Spring computes `preScore` for each candidate:
- **Preference matching:** role (+30), target company (+25), skills (+5 each, max +25), experience (+15), industry (+12), location (+10), employment type (+10), company size (+8), recency (+5)
- **Urgency bonus:** deadline ≤ 1 day (+25), deadline ≤ 3 days (+15)
- **Exposure penalty:** shown yesterday/today (−40), 2–3 days ago (−25), 4–6 days ago (−10)

### Top 30 Quota Selection (Spring)

Spring selects at most 30 candidates from the pool with per-type quotas:
- NEW: up to 12 (`QUOTA_NEW`)
- URGENT: up to 10 (`QUOTA_URGENT`)
- EVERGREEN: up to 8 (`QUOTA_EVERGREEN`)
- Per-company cap: 2 (non-targeted), 3 (targeted)

Spring sets `preScoreComputed=true` and `candidateType` on every DTO sent to the Agent.

### Backend and Agent Responsibility Boundary

| Concern | Owner |
|---|---|
| DB access (read and write) | Spring only — Agent is stateless |
| Pre-scoring with personalization | Spring (`BriefingService`) |
| Urgency bonus / exposure penalty | Spring |
| CandidateType classification | Spring |
| Top-30 quota selection | Spring |
| Final ranking (use preScore when `preScoreComputed=true`) | Agent |
| Filter guards (role mismatch, experience mismatch) | Agent |
| Top-7 quota selection (NEW≤3, URGENT≤2) | Agent |
| LLM enrichment and synthesis | Agent |

---

## Data Flow for Briefing Generation

Triggered either by a scheduler (daily at 08:00 KST) or a user manually via `POST /api/briefings/generate`.
Requires that the daily candidate pool has already been collected for the target date (see above).

1. Backend receives briefing request (scheduled or manual)
2. Backend loads the user's active `user_briefing_preferences` from MySQL
3. Backend loads today's active, non-expired `job_postings` (7-day exposure window)
4. Backend pre-scores candidates and classifies by `CandidateType`; selects top 30 with quota
5. Backend creates a `briefing_jobs` record (status: `PENDING → PROCESSING`)
6. Backend calls Agent: `POST /briefings/generate` with `preference` + `candidatePool` (including `preScore`, `preScoreComputed=true`, `candidateType` per posting)
7. Agent runs `UserBriefingWorkflow` via LangGraph (1st MVP — job briefing):
   - Filter: remove past-deadline, missing title/URL, clear role mismatch, entry-level mismatch
   - Rank: use `preScore` directly when `preScoreComputed=true` (agent fallback only for direct calls)
   - Select: top 7 with quota (NEW≤3, URGENT≤2, rest by score)
   - Enrich: per-posting LLM summary + matching reason (deterministic fallback when LLM unavailable)
   - Synthesize: full Markdown briefing via LLM (deterministic fallback)
8. Agent returns `{ title, summary, content, articles, tokenUsage }` to Backend
9. Backend saves `briefing_reports` + `briefing_articles` to MySQL, marks job `COMPLETED`
10. (Scheduler path) If `EMAIL_AUTO_SEND_ENABLED=true` and user has `briefing_email_enabled = true`, backend sends email and records result in `delivery_logs`
11. Frontend fetches and renders the Markdown briefing report

## Deployment Architecture

- **Frontend**: Vercel (auto-deploy on push to main)
- **Backend & Agent**: Docker containers on AWS EC2
- **Database**: AWS RDS MySQL
- **Cache**: AWS ElastiCache Redis
- **Orchestration**: Docker Compose on EC2
