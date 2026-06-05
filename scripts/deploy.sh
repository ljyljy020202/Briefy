#!/bin/bash

# deploy.sh - Deploy to AWS EC2
# Usage: ./scripts/deploy.sh [backend|agent|all] [dev|prod]

set -e

COMPONENT=${1:-all}
ENVIRONMENT=${2:-prod}

if [ "$COMPONENT" != "backend" ] && [ "$COMPONENT" != "agent" ] && [ "$COMPONENT" != "all" ]; then
    echo "Usage: ./scripts/deploy.sh [backend|agent|all] [dev|prod]"
    exit 1
fi

if [ "$ENVIRONMENT" != "dev" ] && [ "$ENVIRONMENT" != "prod" ]; then
    echo "Usage: ./scripts/deploy.sh [backend|agent|all] [dev|prod]"
    exit 1
fi

echo "🚀 Deploying $COMPONENT to $ENVIRONMENT..."

# Load configuration
if [ -f "infra/aws/.env.$ENVIRONMENT" ]; then
    source "infra/aws/.env.$ENVIRONMENT"
else
    echo "❌ Configuration file not found: infra/aws/.env.$ENVIRONMENT"
    exit 1
fi

# Build images if needed
if [ "$COMPONENT" = "backend" ] || [ "$COMPONENT" = "all" ]; then
    echo "📦 Building backend image..."
    cd apps/backend
    ./gradlew bootJar -q
    docker build -t "${DOCKER_REGISTRY}/briefy-backend:${VERSION:-latest}" .
    docker push "${DOCKER_REGISTRY}/briefy-backend:${VERSION:-latest}"
    cd - > /dev/null
fi

if [ "$COMPONENT" = "agent" ] || [ "$COMPONENT" = "all" ]; then
    echo "📦 Building agent image..."
    cd apps/agent
    docker build -t "${DOCKER_REGISTRY}/briefy-agent:${VERSION:-latest}" .
    docker push "${DOCKER_REGISTRY}/briefy-agent:${VERSION:-latest}"
    cd - > /dev/null
fi

# Deploy to EC2
echo "📤 Connecting to EC2 instance..."
if [ -z "$EC2_HOST" ] || [ -z "$EC2_KEY" ]; then
    echo "❌ EC2_HOST and EC2_KEY must be set in infra/aws/.env.$ENVIRONMENT"
    exit 1
fi

ssh -i "$EC2_KEY" "ec2-user@$EC2_HOST" << EOF
set -e
echo "Pulling latest images..."
docker pull ${DOCKER_REGISTRY}/briefy-backend:${VERSION:-latest}
docker pull ${DOCKER_REGISTRY}/briefy-agent:${VERSION:-latest}

echo "Stopping old services..."
docker compose -f docker-compose.yml -f docker-compose.prod.yml down || true

echo "Starting new services..."
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d

echo "Checking service health..."
sleep 5
curl -f http://localhost:8080/health || echo "⚠️  Backend health check failed"
curl -f http://localhost:8000/health || echo "⚠️  Agent health check failed"

echo "✅ Deployment complete!"
EOF

echo "✅ $COMPONENT deployed to $ENVIRONMENT successfully!"
