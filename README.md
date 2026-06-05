# Briefy

A personalized AI daily briefing service that generates custom email reports based on user interests and preferences.

## 🎯 Features

- **Personalized Briefings**: Users select topics and keywords of interest
- **AI-Generated Content**: LangGraph workflows with OpenAI integration
- **Daily Email Reports**: Automated briefings delivered to inbox
- **Multi-Source Aggregation**: News, market data, weather, and more
- **Responsive Web App**: Real-time briefing viewing and preference management

## 🏗️ Architecture

Briefy is built as a three-tier microservices architecture:

```
Frontend (Next.js)
    ↓ REST API
Backend (Spring Boot)
    ↓ REST API
Agent (FastAPI + LangGraph)
    ↓
OpenAI LLM
```

**Services:**

| Service | Stack | Purpose |
|---------|-------|---------|
| **Frontend** | Next.js 15, TypeScript, Tailwind, shadcn/ui | User interface & session management |
| **Backend** | Spring Boot 3.4, Java 21, MySQL, Redis | API, user management, caching |
| **Agent** | Python 3.11, FastAPI, LangGraph | AI workflow orchestration, content generation |

**Data Storage:**

- MySQL: User data, preferences, briefing history
- Redis: Session management, caching, rate limiting

## 🚀 Quick Start

### Prerequisites

- Docker & Docker Compose
- Node.js 20+, Java 21+, Python 3.11+
- Make

### Development

```bash
# 1. Clone and setup
git clone <repo>
cd briefy
cp .env.example .env

# 2. Install dependencies
make setup

# 3. Start services
make dev

# 4. In another terminal, start frontend
cd apps/frontend
npm run dev
```

**Services will be available at:**

- Frontend: http://localhost:3000
- Backend: http://localhost:8080
- Agent: http://localhost:8000
- MySQL: localhost:3306
- Redis: localhost:6379

### Running Individual Services

```bash
# Frontend
make frontend

# Backend (requires MySQL + Redis running)
make backend

# Agent
make agent

# Database only
make db
```

## 📚 Documentation

- [Architecture](docs/architecture.md) — System design & data flow
- [API Reference](docs/api.md) — Backend endpoints
- [Agent Workflows](docs/agent-workflow.md) — LangGraph & LLM workflows
- [Deployment Guide](docs/deployment.md) — Production deployment to AWS

## 🛠️ Development Commands

```bash
make help          # Show all available commands
make dev           # Start all services (Docker Compose)
make dev-build     # Rebuild and start services
make test          # Run all test suites
make lint          # Lint all services
make down          # Stop all services
make logs-<svc>    # Follow logs for a service
```

## 📝 Project Structure

```
briefy/
├── apps/
│   ├── frontend/       # Next.js web app
│   ├── backend/        # Spring Boot API
│   └── agent/          # Python FastAPI + LangGraph
│
├── infra/
│   ├── nginx/          # Nginx reverse proxy config
│   ├── docker/         # Docker-related configs
│   └── aws/            # AWS infrastructure as code
│
├── docs/
│   ├── architecture.md
│   ├── api.md
│   ├── agent-workflow.md
│   └── deployment.md
│
├── scripts/
│   ├── dev-up.sh       # Start development environment
│   ├── dev-down.sh     # Stop development environment
│   └── deploy.sh       # Production deployment
│
├── docker-compose.yml
├── docker-compose.dev.yml
├── Makefile
├── .env.example
└── CLAUDE.md           # AI assistant guidelines
```

## 🧪 Testing

```bash
# Run all tests
make test

# Frontend tests
cd apps/frontend && npm test

# Backend tests
cd apps/backend && ./gradlew test

# Agent tests
cd apps/agent && poetry run pytest
```

## 🔒 Environment Variables

Copy `.env.example` to `.env` and configure:

```bash
cp .env.example .env
# Edit .env with your values
```

**Required variables:**

- `MYSQL_USER`, `MYSQL_PASSWORD` — Database credentials
- `REDIS_HOST`, `REDIS_PORT` — Redis connection
- `OPENAI_API_KEY` — OpenAI API key
- `JWT_SECRET` — JWT signing secret

## 📦 Deployment

### Frontend

Frontend is deployed automatically to Vercel on push to `main`.

### Backend & Agent

Deploy to AWS EC2 using:

```bash
./scripts/deploy.sh all prod
```

See [Deployment Guide](docs/deployment.md) for detailed instructions.

## 👥 Team & Support

- **Issues**: Report bugs via GitHub Issues
- **Documentation**: See `/docs` directory
- **Local Development Help**: See [CLAUDE.md](CLAUDE.md)

## 📄 License

[Add your license here]

## 🤝 Contributing

1. Create a feature branch (`git checkout -b feature/your-feature`)
2. Commit changes (`git commit -am 'Add feature'`)
3. Push to branch (`git push origin feature/your-feature`)
4. Open a Pull Request

Please follow the development guidelines in [CLAUDE.md](CLAUDE.md).
