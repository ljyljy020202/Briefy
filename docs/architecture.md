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
│  - Google / Kakao OAuth login (redirects to backend, JWT)       │
│  - Briefing display & personalization UI                        │
└─────────────────┬───────────────────────────────────────────────┘
                  │ REST API
                  ▼
┌─────────────────────────────────────────────────────────────────┐
│  Backend (Spring Boot / AWS EC2)                                │
│  - User & subscription management                               │
│  - Candidate scoring, selection, ranking (Top 7)               │
│  - Briefing orchestration & storage                             │
│  ├─→ MySQL: user data, preferences, job postings, briefing history │
│  └─→ Redis: cache, rate limiting                               │
└─────────────────┬───────────────────────────────────────────────┘
                  │ REST API (Top-7 candidate pool)
                  ▼
┌─────────────────────────────────────────────────────────────────┐
│  Agent (FastAPI + LangGraph / AWS EC2)                          │
│  - DailyCollectWorkflow: collect & store job postings           │
│  - UserBriefingWorkflow: LLM generation → validation →          │
│    rewrite → fallback                                           │
└─────────────────┬───────────────────────────────────────────────┘
                  │
                  ▼
              OpenAI API
```

## Service Responsibilities

### Frontend
- Next.js 15 (App Router) with TypeScript
- Server & client components; shadcn/ui component library
- Tailwind CSS styling
- REST client for backend communication only (never calls Agent directly)

### Backend
- Spring Boot 3.4 with Java 21
- REST API for frontend; JPA entities & repository pattern
- **Candidate selection owner:** hard filter → relevance scoring → exposure penalty → isNew/isUrgent classification → RecommendationSelector Top-7 policy
- Sends final Top-7 list (with `rank`, `scoreBreakdown`, `matchEvidence`) to Agent
- Saves Agent response to `briefing_reports` + `briefing_articles`

### Agent
- FastAPI + LangGraph for LLM workflow orchestration
- **Stateless:** reads only what is in the request body; no DB access
- Preserves Backend's rank order throughout — never re-ranks
- LLM generation → deterministic validation → optional rewrite → deterministic fallback

---

## Data Flow for Daily Collection

Triggered once per day at **06:00 KST** by the Spring Boot scheduler, before per-user briefing jobs run at **08:00 KST**.

1. Backend scheduler calls `POST /collections/daily`
2. Agent runs `DailyCollectWorkflow`:
   - Aggregate seed keywords from all active `user_briefing_preferences`
   - Fetch job postings from external sources (job boards, company career pages)
   - Deduplicate by URL; skip already-stored postings
   - Save new postings to `job_postings` table via response to Backend
3. Agent returns `{ savedCounts, durationMs }` to Backend
4. Backend logs the result; user briefing generation for the day reads from the stored pool

---

## Candidate Pool Policy (Backend)

Before briefing generation, the Backend applies a structured candidate selection pipeline entirely within Spring. **The Agent does not re-rank or re-filter.**

### 1. Hard Filter (`RecommendationFilter`)

Postings are removed if any of the following is true:

| Condition | Action |
|---|---|
| `deadline` is in the past | Exclude |
| `title` or `companyName` is blank | Exclude |
| Posting has explicit roles AND user has explicit roles AND no overlap (MISMATCH) | Exclude |
| User's only experience level is 신입 AND posting explicitly requires 3년 이상+ (EXCLUDE) | Exclude |
| Employment type is explicitly set on both sides AND they do not match | Exclude |

Ambiguous cases (one side is blank/null) always pass through.

### 2. Relevance Scoring (`RelevanceScorer`)

`relevanceScore` uses **only preference-matching signals** — no recency bonus, no urgency bonus:

| Signal | Score |
|---|---|
| Role match | +30 |
| Target company match | +25 |
| Each matching skill (max 5 skills) | +5 each (max +25) |
| Experience level match | +15 |
| Industry match (via Company Registry) | +15 |
| Location match | +10 |
| Employment type match | +10 |
| Company size match (via Company Registry) | +15 |

### 3. Exposure Penalty (separate from `relevanceScore`)

Calculated from `publishedAt` relative to `briefingDate`:

| Age | Penalty |
|---|---|
| ≤ 1 day (YESTERDAY) | 25 |
| 2–3 days (RECENT) | 15 |
| 4–6 days (STALE) | 10 |
| ≥ 7 days | 0 |

`adjustedScore = relevanceScore − exposurePenalty`

### 4. isNew and isUrgent flags (independent)

| Flag | Condition |
|---|---|
| `isNew` | `publishedAt` or `collectedDate` ≤ 3 days before `briefingDate` |
| `isUrgent` | `deadline` is within 7 days of `briefingDate` |

Both flags are computed independently — a posting can have both `isNew=true` and `isUrgent=true` simultaneously.

### 5. Top-7 Selection (`RecommendationSelector`)

Single-pass policy applied to `adjustedScore`-sorted candidates:

| Policy | Value |
|---|---|
| `MAX_RECOMMENDATIONS` | 7 |
| `MIN_NEW` | 2 (minimum new postings if available) |
| `MIN_URGENT` | 1 (minimum urgent postings if available) |
| `MAX_PER_COMPANY` | 2 (diversity cap) |

If the pool has fewer than 7 valid candidates, all remaining candidates are included (no padding). If quota targets (MIN_NEW=2, MIN_URGENT=1) cannot be met due to pool shortage, the policy fills remaining slots by `adjustedScore` descending without artificial padding.

Tie-break: stable sort by `adjustedScore` descending, then by `id` ascending.

### 6. What Backend sends to Agent

Backend assigns `rank = 1..N` (1-based, in selection order) and sends the final Top-7 list:

```
rank + scoreBreakdown + matchEvidence + isNew + isUrgent + publishedAt
```

Removed fields (no longer in the contract): `preScore`, `preScoreComputed`, `candidateType`, `position`, `contentHash`, `postedAt`.

---

## Backend–Agent Responsibility Boundary

| Concern | Owner |
|---|---|
| DB access (read and write) | Spring only — Agent is stateless |
| Hard filter (expired, role mismatch, experience, employment type) | Spring (`RecommendationFilter`) |
| Relevance scoring (preference signals only) | Spring (`RelevanceScorer`) |
| Exposure penalty | Spring |
| isNew / isUrgent classification | Spring |
| Top-7 quota selection and rank assignment | Spring (`RecommendationSelector` + `BriefingService`) |
| LLM briefing generation + validation + rewrite | Agent (`user_briefing_graph`) |
| Deterministic fallback report | Agent (`deterministic_fallback_node`) |
| Save briefing report and articles | Spring |

---

## Data Flow for Briefing Generation

Triggered either by a scheduler (daily at 08:00 KST) or a user via `POST /api/briefings/generate`.

1. Backend receives briefing request (scheduled or manual)
2. Backend loads the user's active `user_briefing_preferences` from MySQL
3. Backend loads today's active, non-expired `job_postings` from MySQL
4. Backend runs the candidate pipeline: hard filter → relevance score → exposure penalty → Top-7 selection
5. Backend creates a `briefing_jobs` record (`PENDING → PROCESSING`)
6. Backend calls Agent `POST /briefings/generate` with `preference` + `candidatePool` (max 7 postings, each with `rank`, `scoreBreakdown`, `matchEvidence`, `isNew`, `isUrgent`)
7. Agent runs `UserBriefingWorkflow` via LangGraph:
   - Sorts postings by `rank` (preserves Backend order)
   - Enriches postings via LLM (batch: summary + matching reason)
   - Synthesizes a Markdown briefing via LLM
   - Validates the report (section structure, referenced posting IDs)
   - If validation fails: rewrites once via LLM, then re-validates
   - If still failing or any LLM error: deterministic fallback (no LLM, uses `matchEvidence`)
8. Agent returns `{ title, summary, content, articles, tokenUsage }` to Backend
9. Backend saves `briefing_reports` + `briefing_articles` to MySQL, marks job `COMPLETED`
10. If `EMAIL_AUTO_SEND_ENABLED=true` and user has `briefing_email_enabled=true`, backend calls `EmailDeliveryService.autoDeliverBriefingReport()`. The `DeliveryLog` record (PENDING) must already exist at this point; auto-delivery only processes PENDING logs. Email is sent outside any transaction; status is updated via REQUIRES_NEW transactions.
11. Frontend fetches and renders the Markdown briefing report

---

## Deployment Architecture

- **Frontend**: Vercel (auto-deploy on push to `main`)
- **Backend & Agent**: Docker containers on AWS EC2
- **Database**: AWS RDS MySQL
- **Cache**: AWS ElastiCache Redis
- **Orchestration**: Docker Compose on EC2
