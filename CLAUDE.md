# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Briefy is a personalized AI daily briefing service. Three independent services share this monorepo:

| Service | Stack | Port |
|---------|-------|------|
| `apps/frontend/` | Next.js 15, TypeScript, Tailwind, shadcn/ui | 3000 |
| `apps/backend/` | Spring Boot 3.4, Java 21, JPA, Redis | 8080 |
| `apps/agent/` | Python 3.11, FastAPI, LangGraph | 8000 |

## Current MVP Focus

The current **1st MVP** is a job-seeker daily briefing for developer and general job seekers.

| Phase | Scope |
|-------|-------|
| **1st MVP** | **Job Briefing** — user sets target role, companies, skills/competencies, location, experience level, employment type; briefing delivers new postings, deadline-near postings, matching reasons, and recommended actions |
| 1.5 MVP | **Interested Company Briefing** — company news, hiring changes, business/service issues, earnings/investment summaries from a job-seeker perspective |
| 2nd MVP | **Industry / Market Briefing** — IT/AI, semiconductor, platform, finance, content, and other industries; information-only, no buy/sell recommendations |

### Coding rules for MVP phases

- **Do not treat old tech-news categories as active MVP data.** The seed topics `AI/LLM`, `Backend/Spring`, `Cloud/AWS`, `Startup/Developer Trend`, `Stock/Economy`, `Company/Industry` are from the original product concept and must be replaced. All new seed data, onboarding UI, agent workflows, and mock data must reflect job-seeker preferences (role, company, skill, location, experience level, employment type).
- **Prioritise job briefing for all 1st MVP work.** When in doubt about scope, default to what serves a developer job seeker discovering relevant postings today.
- **Treat interested company briefing as 1.5 MVP scope** and industry/market briefing as 2nd MVP scope. Do not implement or scaffold these unless explicitly requested.
- **Investment-related content must be information-only.** Never generate or suggest buy/sell recommendations anywhere in the codebase (prompts, copy, mock data, or docs).
- **Landing page copy must stay broad.** The product identity is a personalized AI daily briefing service; mention job briefing only as the current MVP use case, not as the permanent product identity.

## Commands

### Root (Makefile)

```bash
make setup       # Install all dependencies once after cloning
make dev         # Start MySQL + Redis + backend + agent via Docker Compose
make db          # Start only MySQL + Redis (for running services natively)
make test        # Run all test suites
make lint        # Lint all services
make logs-<svc>  # Follow logs for a service, e.g. make logs-backend
```

### Frontend

```bash
cd apps/frontend
npm run dev        # Dev server with Turbopack
npm run build      # Production build
npm run type-check # TypeScript check without emit
npm run lint       # ESLint
npm test           # Jest

# Single test file
npm test -- --testPathPattern=ComponentName
```

### Backend (Gradle wrapper — run `gradle wrapper` to generate `gradlew` if missing)

```bash
cd apps/backend
./gradlew bootRun --args='--spring.profiles.active=local'
./gradlew test
./gradlew test --tests "com.briefy.SomeServiceTest"
./gradlew spotlessCheck
./gradlew spotlessApply
./gradlew bootJar
```

### Agent

```bash
cd apps/agent
poetry install                                            # First-time setup
poetry run uvicorn app.main:app --reload --port 8000
poetry run pytest
poetry run pytest tests/test_graph.py -v
poetry run ruff check .
poetry run ruff check . --fix
```

## Architecture

### Data flow

```
User → frontend (Next.js / Vercel)
         ↓ REST
       backend (Spring Boot / EC2) ──→ MySQL
         ↓                         ↘→ Redis (cache/rate limiting)
         ↓ REST
       agent (FastAPI / EC2)
         ↓ LangGraph
       LLM (OpenAI)
```

The frontend calls the backend for all user, auth, and data operations. The backend calls the agent for AI briefing generation — the frontend never calls the agent directly. The agent orchestrates LLM calls via LangGraph workflows.

### Backend package layout (`apps/backend/src/main/java/com/briefy/`)

- `controller/` — `@RestController` classes, one per domain (thin, delegate to service)
- `service/` — business logic
- `repository/` — `JpaRepository` interfaces
- `domain/` — JPA entities and DTOs (keep entities and DTOs separate)
- `config/` — Security, Redis, CORS, WebClient configuration

Spring profile `local` (activated via `--spring.profiles.active=local`) uses `ddl-auto: update` and verbose SQL logging. Production uses `validate`.

### Agent module layout (`apps/agent/app/`)

- `api/` — FastAPI routers, one file per domain
- `graph/` — LangGraph `StateGraph` definitions; one graph per major workflow (`daily_collect_graph.py` for collection, `user_briefing_graph.py` for per-user generation)
- `tools/` — LangGraph tool functions decorated with `@tool`
- `core/config.py` — `pydantic-settings` `Settings` class; all env vars declared here

### Frontend conventions

- App Router only (`src/app/`); no Pages Router.
- Server Components by default; add `"use client"` only when needed.
- shadcn/ui components live in `src/components/ui/` (generated by `npx shadcn@latest add <component>`).
- `src/lib/utils.ts` exports the `cn()` helper (clsx + tailwind-merge).
- API calls to backend go through a typed client in `src/lib/api.ts`.

## Rules

### Backend

- Use layered architecture: `controller/` → `service/` → `repository/`, with `domain/` for entities and DTOs.
- Never expose JPA Entity directly in API responses; use DTOs.
- All API responses should follow a consistent format (e.g., `ApiResponse<T>`).
- Business logic belongs exclusively in the `service/` layer.
- Create meaningful custom exceptions for domain-specific errors.
- Write unit tests for all service logic.
- Use Spring profiles (`local`, `dev`, `prod`) to manage environment-specific configs.

### Frontend

- Use TypeScript for all code; no `any` types.
- Prefer shadcn/ui components over custom implementations when available.
- Keep components small and focused; break down large components.
- Separate all API calls into `lib/api.ts`; never hardcode backend URLs.
- Use environment variables (`.env.local`) for API endpoints; reference via `NEXT_PUBLIC_*` prefix for browser access.
- Server Components by default; use `"use client"` sparingly.

### Agent

- Keep LangGraph nodes small and single-responsibility.
- Split workflows into two phases: `DailyCollectWorkflow` (collect → deduplicate → store candidate pool) and `UserBriefingWorkflow` (load pool → filter → rank → summarize → format).
- Do not call external sources during `UserBriefingWorkflow`; read from the pre-collected candidate pool in DB instead.
- Do not call LLM for deterministic operations such as URL deduplication, keyword matching, or preference-based score ranking; use simple code instead.
- Log token usage and processing time when possible.
- Test graph workflows independently before integration.

### General

- Never delete files without explicit user confirmation.
- Never edit `.env` files directly; suggest changes to `.env.example` instead.
- Never commit secrets, API keys, or credentials; use environment variables.
- Do not commit build artifacts (`node_modules/`, `build/`, `.gradle/`, `__pycache__/`).

## Branch Strategy

### Branch Roles

| Branch | Role |
|--------|------|
| `main` | Stable, always deployable. Never push feature work here directly. |
| `dev` | Integration branch. All feature branches merge here first. |
| `feature/*` | Feature-specific working branches, cut from `dev`. |

### Branch Naming

Use a service prefix so the branch name immediately shows what is being changed:

```
feature/<service>-<short-description>
```

| Prefix | Area |
|--------|------|
| `feature/frontend-` | `apps/frontend/` |
| `feature/backend-` | `apps/backend/` |
| `feature/agent-` | `apps/agent/` |
| `feature/infra-` | Docker Compose, Makefile, scripts |
| `feature/docs-` | `docs/` |
| `feature/ci-` | `.github/workflows/` |

**Examples:**

```
feature/frontend-onboarding-page
feature/frontend-dashboard
feature/backend-auth
feature/backend-preference
feature/backend-briefing-job
feature/agent-daily-collect
feature/agent-user-briefing
feature/infra-docker-compose
feature/docs-database-erd
feature/ci-backend-workflow
```

### PR Rules

- Always create a PR from `feature/*` → `dev`; never push feature work directly to `main`.
- Merge `dev` → `main` only when the integrated version is tested and deployable.
- Prefer small, single-feature PRs. Avoid mixing frontend, backend, and agent changes in one PR unless the feature genuinely requires cross-service integration — if it does, explain why in the PR description.
- Before opening a PR, run the relevant checks for changed services:
  - **backend**: `./gradlew test` and `./gradlew spotlessCheck`
  - **frontend**: `npm run lint` and `npm run build`
  - **agent**: `poetry run pytest` and `poetry run ruff check .`
- Update `docs/` when API contracts, database schema, environment variables, or workflows change.

### Typical Flow

1. Cut a feature branch from `dev`:
   ```bash
   git switch dev && git pull
   git switch -c feature/backend-auth
   ```
2. Commit with the Korean-subject convention (see [Commit Convention](#commit-convention)):
   ```
   feat(backend): Google OAuth 콜백 핸들러 추가
   ```
3. Push and open a PR from `feature/backend-auth` → `dev`.
4. After review and CI passes, merge to `dev`.
5. Merge `dev` → `main` only after integration testing confirms the build is deployable.

## Commit Convention

All commits in this repository follow **Conventional Commits**.

### Format

```
<type>(<scope>): <subject>
```

- **type**: what kind of change (see table below)
- **scope**: which service or layer is affected (optional but recommended)
- **subject**: 한국어로 작성, no trailing period, ≤ 72 chars total (type + scope + subject combined)

### Types

| Type | When to use |
|---|---|
| `feat` | New feature or endpoint |
| `fix` | Bug fix |
| `docs` | Documentation only |
| `chore` | Build, config, tooling, dependency changes |
| `ci` | GitHub Actions / CI pipeline changes |
| `refactor` | Code restructuring without feature or bug change |
| `test` | Adding or updating tests |
| `style` | Formatting / linting only (no logic change) |

### Scopes

Use the service name or layer affected:

| Scope | Meaning |
|---|---|
| `backend` | `apps/backend/` |
| `frontend` | `apps/frontend/` |
| `agent` | `apps/agent/` |
| `infra` | Docker Compose, Makefile, scripts |
| `ci` | `.github/workflows/` |
| `docs` | `docs/` |

### Examples

```
feat(backend): Google OAuth 콜백 핸들러 추가
fix(agent): 빈 기사 목록 처리 로직 추가
docs: database.md에 ERD 추가
chore(backend): spring-security-oauth2-jose 의존성 추가
ci: apps 경로 기준으로 워크플로우 수정
refactor(frontend): API 클라이언트를 lib/api.ts로 분리
test(backend): BriefingService 단위 테스트 추가
```

### Rules

- The type and scope must be written in English; the subject after the colon should be written in Korean.
- Do not reference issue numbers or PR numbers unless they exist.
- Never commit `.env`, secrets, API keys, or build artifacts.
- Each commit should represent one logical change — do not mix unrelated changes.

## Environment variables

Copy `.env.example` → `.env` (gitignored) at repo root. Docker Compose picks it up automatically. See `.env.example` for the full reference.

## First-time setup

```bash
cp .env.example .env
# edit .env with real values

# Backend: generate Gradle wrapper (needed once)
cd apps/backend && gradle wrapper && cd ..

# Install all service dependencies
make setup

# Start infrastructure + services
make dev
```
