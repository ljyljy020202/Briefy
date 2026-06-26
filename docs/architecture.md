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
│  - LLM workflow orchestration                                    │
│  - Job posting collection & matching (1st MVP)                  │
│  - Briefing generation via LangGraph                            │
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

## Data Flow for Briefing Generation

Triggered either by a scheduler (daily) or a user manually via `POST /api/briefings/generate`.

1. Backend receives briefing request (scheduled or manual)
2. Backend loads the user's active topic subscriptions (`user_topics`) from MySQL
3. Backend creates a `briefing_jobs` record (status: `PENDING → PROCESSING`)
4. Backend calls Agent: `POST /briefings/generate` with topic + keyword list
5. Agent uses LangGraph to orchestrate multi-step workflow (1st MVP — job briefing):
   - Collect job postings from sources, filtered by user's role / company / skill / location preferences
   - Deduplicate postings; rank by preference match score
   - Generate matching reasons and recommended actions with LLM
   - Format final Markdown briefing (new postings · deadline-near postings · recommended actions)
6. Agent returns `{ title, summary, content, articles, tokenUsage }` to Backend
7. Backend saves `briefing_reports` + `briefing_articles` to MySQL, marks job `COMPLETED`
8. Backend sends email and records result in `delivery_logs`
9. Frontend fetches and renders the Markdown briefing report

## Deployment Architecture

- **Frontend**: Vercel (auto-deploy on push to main)
- **Backend & Agent**: Docker containers on AWS EC2
- **Database**: AWS RDS MySQL
- **Cache**: AWS ElastiCache Redis
- **Orchestration**: Docker Compose on EC2
