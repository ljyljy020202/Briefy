#!/bin/bash

# dev-down.sh - Stop local development environment
# Usage: ./scripts/dev-down.sh

set -e

echo "🛑 Stopping Briefy development environment..."

# Stop and remove containers
docker compose down

echo "✅ All services stopped and cleaned up."
echo ""
echo "💡 To start again: ./scripts/dev-up.sh or make dev"
