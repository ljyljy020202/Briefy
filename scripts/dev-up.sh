#!/bin/bash

# dev-up.sh - Start local development environment
# Usage: ./scripts/dev-up.sh

set -e

echo "🚀 Starting Briefy development environment..."

# Check if .env exists
if [ ! -f .env ]; then
    echo "❌ .env file not found. Copying from .env.example..."
    cp .env.example .env
    echo "⚠️  Edit .env with your values before continuing."
    exit 1
fi

# Start Docker Compose services
echo "📦 Starting Docker Compose services (MySQL, Redis, backend, agent)..."
docker compose up -d

# Wait for services to be healthy
echo "⏳ Waiting for services to be healthy..."
sleep 5

# Check MySQL
echo "✅ Checking MySQL..."
until docker compose exec -T mysql mysqladmin ping -h localhost -u root -p"${MYSQL_ROOT_PASSWORD}" > /dev/null 2>&1; do
    echo "⏳ MySQL is not ready, waiting..."
    sleep 2
done
echo "✅ MySQL is ready"

# Check Redis
echo "✅ Checking Redis..."
until docker compose exec -T redis redis-cli ping > /dev/null 2>&1; do
    echo "⏳ Redis is not ready, waiting..."
    sleep 2
done
echo "✅ Redis is ready"

echo ""
echo "✅ All services are running!"
echo ""
echo "📍 Service URLs:"
echo "   Backend:  http://localhost:8080"
echo "   Agent:    http://localhost:8000"
echo "   MySQL:    localhost:3306"
echo "   Redis:    localhost:6379"
echo ""
echo "💡 Next steps:"
echo "   1. Start frontend: cd apps/frontend && npm run dev"
echo "   2. Start backend: cd apps/backend && ./gradlew bootRun --args='--spring.profiles.active=local'"
echo "   3. Start agent:   cd apps/agent && poetry run uvicorn app.main:app --reload"
echo ""
echo "📝 Or use: make frontend, make backend, make agent"
echo "🔍 View logs:   docker compose logs -f"
