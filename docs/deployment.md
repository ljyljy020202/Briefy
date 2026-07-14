# Deployment Guide

## 배포 구조

```
User → Vercel (Next.js frontend)
          ↓ HTTPS + credentials:include
       Nginx (EC2)
          ↓ 127.0.0.1:8080
       backend (Spring Boot, Docker)
          ↓ http://agent:8000 (Docker 내부 네트워크)
       agent (FastAPI, Docker)
          ↓
       mysql / redis (Docker 내부 네트워크)
```

| 서비스 | 플랫폼 | 외부 노출 |
|--------|--------|----------|
| Frontend | Vercel | HTTPS (Vercel 도메인) |
| Backend | EC2 Docker | Nginx를 통해서만 |
| Agent | EC2 Docker | 외부 비공개 (backend 내부 호출만) |
| MySQL | EC2 Docker | 외부 비공개 |
| Redis | EC2 Docker | 외부 비공개 |

---

## 1. 로컬 개발 검증

### 사전 준비

```bash
# 레포 클론 후 1회만 실행
cp .env.example .env
# .env 에 GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET, OPENAI_API_KEY 등 실제 값 입력

make setup   # 의존성 전체 설치
```

### 전체 서비스 기동 (Docker Compose)

```bash
make dev           # MySQL + Redis + backend + agent 기동 (포그라운드)
# 또는
make dev-build     # 이미지 재빌드 후 기동
```

백그라운드로 실행하려면:

```bash
docker compose up -d
docker compose logs -f       # 전체 로그 스트리밍
docker compose logs -f backend  # 특정 서비스 로그
```

### 서비스 상태 확인

```bash
docker compose ps

# backend health check
curl http://localhost:8080/api/health
# 기대 응답: {"status":"ok"}

# agent health check
curl http://localhost:8000/health
# 기대 응답: {"status":"ok"}

# MySQL 접속 확인
docker compose exec mysql mysql -u briefy -p briefy -e "SHOW TABLES;"

# Redis 접속 확인
docker compose exec redis redis-cli ping
# 기대 응답: PONG
```

### Frontend 개발 서버

```bash
cd apps/frontend
cp .env.example .env.local
# NEXT_PUBLIC_API_BASE_URL=http://localhost:8080 (기본값)
npm run dev
```

브라우저에서 http://localhost:3000 접속.

### 서비스 종료

```bash
make down          # 컨테이너 종료 및 제거 (볼륨 유지)
# ⚠️  절대 'docker compose down -v' 실행 금지 — mysql-data 볼륨이 삭제됩니다.
```

---

## 2. 로컬 테스트 / 빌드 검증

```bash
# 전체 테스트
make test

# 서비스별 개별 테스트
cd apps/backend  && ./gradlew test
cd apps/frontend && npm test -- --watchAll=false
cd apps/agent    && poetry run pytest

# 린트
make lint

# Backend 빌드 검증 (JAR 생성)
cd apps/backend && ./gradlew bootJar

# Frontend 빌드 검증
cd apps/frontend && npm run build
```

> **주의:** `SCHEDULER_ENABLED=false` (기본값), `EMAIL_MODE=log` (기본값) 상태에서 테스트하세요.
> 실제 이메일 발송이나 스케줄러 자동 실행이 일어나서는 안 됩니다.

---

## 3. EC2 배포 전 준비물

### 필요한 것

- EC2 인스턴스 (Ubuntu 22.04 권장, t3.medium 이상)
- EC2 보안 그룹: 인바운드 80(HTTP), 443(HTTPS), 22(SSH)만 허용
- Docker, Docker Compose v2 설치 완료
- (선택) 도메인 + DNS A레코드 설정
- Google Cloud Console OAuth redirect URI 등록 (예: `https://api.example.com/api/oauth2/callback/google`)
- OpenAI API Key

### EC2 초기 설정

```bash
# Docker 설치 (Ubuntu)
sudo apt update && sudo apt install -y docker.io docker-compose-plugin
sudo usermod -aG docker $USER
# 재로그인 후 docker ps 로 권한 확인

# Nginx 설치
sudo apt install -y nginx curl
```

---

## 4. EC2 배포 절차

### 4-1. 레포 클론

```bash
git clone https://github.com/<your-org>/briefy.git
cd briefy
git checkout main
```

### 4-2. `.env.prod` 작성

```bash
cp .env.prod.example .env.prod
# 실제 값으로 수정
#   MYSQL_ROOT_PASSWORD, MYSQL_PASSWORD, JWT_SECRET (openssl rand -hex 32)
#   GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET
#   GOOGLE_REDIRECT_URI=https://api.example.com/api/oauth2/callback/google
#   FRONTEND_BASE_URL=https://app.example.com (Vercel 배포 도메인)
#   OPENAI_API_KEY
#   EMAIL_MODE=log  (초기 배포 시 — SES 준비 전)
#   SCHEDULER_ENABLED=false  (초기 배포 시)
nano .env.prod
```

### 4-3. `docker-compose.prod.yml` 준비

```bash
cp docker-compose.prod.yml.example docker-compose.prod.yml
# 필요 시 수정 (기본 설정으로 충분한 경우 그대로 사용)
```

### 4-4. 이미지 빌드 및 기동

```bash
docker compose -f docker-compose.prod.yml up -d --build

# 기동 상태 확인
docker compose -f docker-compose.prod.yml ps

# 로그 확인
docker compose -f docker-compose.prod.yml logs -f backend
docker compose -f docker-compose.prod.yml logs -f agent
```

### 4-5. 서비스 확인

```bash
# backend health (Nginx 설정 전, 직접 확인)
curl http://127.0.0.1:8080/api/health
# 기대 응답: {"status":"ok"}

# agent health (내부 네트워크 전용 — docker exec으로 확인)
docker compose -f docker-compose.prod.yml exec agent curl http://localhost:8000/health
```

### 4-6. Nginx 설정

```bash
sudo cp infra/nginx/briefy.conf.example /etc/nginx/sites-available/briefy
sudo nano /etc/nginx/sites-available/briefy
# api.example.com 을 실제 도메인으로 변경

sudo ln -s /etc/nginx/sites-available/briefy /etc/nginx/sites-enabled/
sudo nginx -t
sudo systemctl reload nginx
```

### 4-7. HTTPS 적용 (도메인 연결 후)

```bash
sudo apt install -y certbot python3-certbot-nginx
sudo certbot --nginx -d api.example.com
# Certbot이 Nginx 설정을 자동으로 수정합니다.
```

---

## 5. Vercel 환경변수 설정

Vercel 프로젝트 → Settings → Environment Variables 에서:

| 변수명 | 값 | 환경 |
|--------|-----|------|
| `NEXT_PUBLIC_API_BASE_URL` | `https://api.example.com` | Production |
| `NEXT_PUBLIC_API_BASE_URL` | `http://localhost:8080` | Development |

> **vercel.json `ignoreCommand`:** `apps/frontend/` 변경이 없는 commit에서는
> Vercel 재빌드를 자동으로 skip합니다. (backend/agent만 변경된 경우 빌드 생략)

---

## 6. 운영 중 주의사항

### ⚠️ 절대 실행 금지

```bash
# mysql-data 볼륨 삭제 → 모든 DB 데이터 영구 소실
docker compose -f docker-compose.prod.yml down -v
```

### 안전한 재시작

```bash
# 컨테이너만 재시작 (데이터 유지)
docker compose -f docker-compose.prod.yml restart

# 이미지 재빌드 후 무중단 교체
docker compose -f docker-compose.prod.yml up -d --build --no-deps backend
docker compose -f docker-compose.prod.yml up -d --build --no-deps agent
```

### 로그 확인

```bash
docker compose -f docker-compose.prod.yml logs -f --tail=100 backend
docker compose -f docker-compose.prod.yml logs -f --tail=100 agent
```

---

## 7. main merge 전 체크리스트

dev → main merge 전에 아래 항목을 모두 확인하세요.

- [ ] `make dev` 로 로컬 전체 기동 확인
- [ ] `curl http://localhost:8080/api/health` → `{"status":"ok"}`
- [ ] `curl http://localhost:8000/health` → `{"status":"ok"}`
- [ ] `cd apps/backend && ./gradlew test` 통과
- [ ] `cd apps/backend && ./gradlew spotlessCheck` 통과
- [ ] `cd apps/frontend && npm run build` 통과
- [ ] `cd apps/frontend && npm run lint` 통과
- [ ] `cd apps/agent && poetry run pytest` 통과
- [ ] `cd apps/agent && poetry run ruff check .` 통과
- [ ] `.env.prod.example` 에 새로운 환경변수가 반영되어 있는지 확인
- [ ] API 계약 변경 시 `docs/api.md` 업데이트

---

## 8. CI/CD

### 구조

| Job | Runner | 역할 |
|-----|--------|------|
| `test` | `ubuntu-latest` (GitHub-hosted) | 단위 테스트 |
| `build-and-push` | `ubuntu-latest` (GitHub-hosted) | Docker 이미지 빌드 → GHCR push |
| `deploy` | `self-hosted, briefy-prod` (EC2) | GHCR에서 pull 후 컨테이너 교체 |

`deploy` job은 EC2 self-hosted runner가 직접 실행합니다. SSH 터널 없이 EC2 내부에서 `docker compose pull / up`만 수행하므로 보안 그룹에서 22번 포트를 외부에 열어둘 필요가 없습니다.

### EC2 self-hosted runner 등록

```bash
# EC2에서 한 번만 실행
mkdir -p /home/ubuntu/actions-runner && cd /home/ubuntu/actions-runner
# GitHub 레포 → Settings → Actions → Runners → New self-hosted runner
# 안내에 따라 tarball 다운로드 및 configure 실행
# label 지정: briefy-prod
./config.sh --url https://github.com/<org>/<repo> --token <REGISTRATION_TOKEN> --labels briefy-prod
sudo ./svc.sh install && sudo ./svc.sh start
```

### EC2 보안 그룹

- SSH(22): `0.0.0.0/0`으로 열지 말 것. **본인 고정 IP/32로만** 허용.
- HTTP(80), HTTPS(443): `0.0.0.0/0` 허용 (Nginx 경유).
- 그 외 포트(8080, 8000 등): 인바운드 차단 유지.

### 환경변수만 변경할 때

GitHub Actions를 트리거하지 않고 EC2에서 직접 처리합니다.

```bash
# EC2에서 실행
nano /home/ubuntu/apps/Briefy/.env.prod

# 변경된 환경변수를 적용하려면 force-recreate
docker compose --env-file .env.prod -f docker-compose.prod.yml up -d --force-recreate --no-build --no-deps backend
docker compose --env-file .env.prod -f docker-compose.prod.yml up -d --force-recreate --no-build --no-deps agent
```

### EC2에서 빌드 금지

EC2는 리소스가 제한적이므로 `docker compose build`를 실행하면 서버가 과부하됩니다. **EC2에서는 pull/up만 사용하고 build는 절대 실행하지 마세요.**

```bash
# ✅ 올바른 방법 (GHCR에서 pull)
docker compose --env-file .env.prod -f docker-compose.prod.yml pull backend
docker compose --env-file .env.prod -f docker-compose.prod.yml up -d --force-recreate --no-build --no-deps backend

# ❌ 절대 금지 (EC2에서 빌드)
docker compose -f docker-compose.prod.yml up -d --build
```
