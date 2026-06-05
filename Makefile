.PHONY: help dev dev-build down db frontend backend agent test lint build logs setup

help:
	@grep -E '^[a-zA-Z_%-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "\033[36m%-20s\033[0m %s\n", $$1, $$2}'

# ── Infrastructure ────────────────────────────────────────────────────────────

dev: ## Start MySQL + Redis + backend + agent via Docker Compose
	docker compose up

dev-build: ## Rebuild images then start all services
	docker compose up --build

down: ## Stop and remove containers
	docker compose down

db: ## Start only MySQL and Redis (for native service development)
	docker compose up mysql redis

logs: ## Follow logs for all services
	docker compose logs -f

logs-%: ## Follow logs for a specific service, e.g. make logs-backend
	docker compose logs -f $*

# ── Per-service dev servers ───────────────────────────────────────────────────

frontend: ## Start Next.js dev server (port 3000)
	cd apps/frontend && npm run dev

backend: ## Start Spring Boot with local profile (requires DB running)
	cd apps/backend && ./gradlew bootRun --args='--spring.profiles.active=local'

agent: ## Start FastAPI agent with hot reload (port 8000)
	cd apps/agent && poetry run uvicorn app.main:app --reload --port 8000

# ── Quality ───────────────────────────────────────────────────────────────────

test: ## Run all test suites
	@echo "==> frontend" && cd apps/frontend && npm test -- --watchAll=false
	@echo "==> backend"  && cd apps/backend  && ./gradlew test
	@echo "==> agent"    && cd apps/agent    && poetry run pytest

lint: ## Lint all services
	@echo "==> frontend" && cd apps/frontend && npm run lint
	@echo "==> backend"  && cd apps/backend  && ./gradlew spotlessCheck
	@echo "==> agent"    && cd apps/agent    && poetry run ruff check .

build: ## Build all Docker images
	docker compose build

# ── First-time setup ──────────────────────────────────────────────────────────

setup: ## Install all dependencies (run once after cloning)
	@echo "==> frontend" && cd apps/frontend && npm install
	@echo "==> backend"  && cd apps/backend  && ./gradlew dependencies
	@echo "==> agent"    && cd apps/agent    && poetry install
	@echo "Done. Copy .env.example to .env and edit values before running 'make dev'."
