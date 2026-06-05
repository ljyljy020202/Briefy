# Deployment Guide

## Overview

Briefy uses a multi-cloud deployment model:
- **Frontend**: Vercel (serverless, auto-scaling)
- **Backend & Agent**: AWS EC2 (Docker containers, Docker Compose orchestration)
- **Data**: AWS RDS (MySQL) + ElastiCache (Redis)

## Local Development

### Prerequisites

- Docker & Docker Compose
- Node.js 20+, Python 3.11+, Java 21+
- Make

### Quick Start

```bash
cp .env.example .env
# Edit .env with your values

make setup      # Install all dependencies
make dev        # Start all services (MySQL, Redis, backend, agent)

# In another terminal
cd apps/frontend
npm run dev     # Start Next.js on port 3000
```

### Services

| Service | URL | Command |
|---------|-----|---------|
| Frontend | http://localhost:3000 | `npm run dev` (from `apps/frontend/`) |
| Backend | http://localhost:8080 | `make backend` (or `./gradlew bootRun --args='--spring.profiles.active=local'`) |
| Agent | http://localhost:8000 | `make agent` (or `poetry run uvicorn ...`) |
| MySQL | localhost:3306 | `docker compose up mysql` |
| Redis | localhost:6379 | `docker compose up redis` |

## Production Deployment

### Prerequisites

- AWS Account with EC2, RDS, ElastiCache access
- GitHub Actions for CI/CD
- Terraform or CloudFormation for IaC (optional)

### Step 1: Prepare AWS Infrastructure

```bash
cd infra/aws
# Create RDS MySQL instance, ElastiCache Redis, EC2 instance
# Store connection details in AWS Secrets Manager
```

### Step 2: Build Docker Images

```bash
cd apps/backend && ./gradlew bootJar
cd apps/agent && poetry export -f requirements.txt --output requirements.txt
docker build -t briefy-backend:latest .
docker build -t briefy-agent:latest .
```

Or use GitHub Actions to build automatically on push to `main`.

### Step 3: Deploy to EC2

```bash
# SSH into EC2 instance
ssh -i key.pem ec2-user@your-instance-ip

# Pull latest images
docker pull your-registry/briefy-backend:latest
docker pull your-registry/briefy-agent:latest

# Copy production docker-compose
scp docker-compose.yml docker-compose.prod.yml ec2-user@your-instance-ip:~/

# Start services
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d
```

### Step 4: Deploy Frontend

Frontend deploys automatically to Vercel when you push to `main` branch (if connected).

Alternatively:
```bash
npm run build
npm run start  # Or deploy to your hosting platform
```

### Step 5: Configure Domain & SSL

- Set up Route 53 DNS (or your DNS provider)
- Use AWS Certificate Manager for SSL certificates
- Configure load balancer if using multiple EC2 instances

## Monitoring & Logging

### Backend & Agent Logs

```bash
# On EC2
docker compose logs -f backend
docker compose logs -f agent

# Or use CloudWatch with Docker logging driver
```

### Frontend Monitoring

- Vercel provides built-in analytics & monitoring
- Set up DataDog or similar for custom metrics

## Scaling Considerations

### Horizontal Scaling

For high traffic, consider:
- Multiple EC2 instances behind load balancer
- AWS Auto Scaling Groups
- CloudFront CDN for frontend

### Database Scaling

- RDS Read Replicas for scaling reads
- ElastiCache for caching frequently accessed data
- Connection pooling in backend

## Rollback Procedure

```bash
# SSH into EC2
docker pull your-registry/briefy-backend:previous-tag
docker compose down
docker compose up -d backend  # Starts with rolled-back image
```

## Environment Variables

Production `.env` should include:
- Database credentials (from AWS Secrets Manager)
- Redis connection string
- API keys (OpenAI, etc.)
- JWT secret
- CORS origins

Store sensitive values in AWS Secrets Manager, not in `.env` file.

## Emergency Contacts

- On-call engineer: check Slack #oncall
- AWS support: Enterprise Support plan
