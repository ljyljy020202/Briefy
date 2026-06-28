# Briefy

AI 에이전트 기반 취업 준비생을 위한 맞춤형 일일 브리핑 서비스입니다. 목표 직무·회사·스킬·지역·경력 수준·고용 형태를 설정하면, AI가 채용 공고를 수집·필터링·요약해 매일 아침 이메일로 전달합니다.

> **현재 1차 MVP**는 개발자·취업 준비생을 위한 **채용 브리핑**에 집중합니다.
> 목표 직무·회사·스킬·지역·경력 수준·고용 형태를 설정하면, AI가 신규 공고와 마감 임박 공고를 매일 정리해 전달합니다.

## MVP 로드맵

| 단계 | 내용 |
|------|------|
| **1차 MVP** | **채용 브리핑** — 신규 공고, 마감 임박 공고, 매칭 이유, 추천 액션 |
| 1.5 MVP | **관심 기업 브리핑** — 기업 뉴스, 채용 변화, 사업/서비스 이슈, 실적·투자 동향 |
| 2차 MVP | **산업/시장 브리핑** — IT/AI, 반도체, 플랫폼, 금융, 콘텐츠 등 업종별 동향 (정보 제공 목적, 투자 권유 없음) |

## 주요 기능

- **맞춤형 브리핑**: 브리핑 선호도(목표 직무·회사·스킬·지역·경력·고용 형태 등)를 직접 설정해 나만의 브리핑 구성
- **AI 콘텐츠 생성**: LangGraph 워크플로우와 OpenAI 연동으로 핵심만 요약
- **매일 아침 이메일 발송**: 지정한 시간에 받은편지함으로 자동 전달
- **채용 공고 수집 (1차 MVP)**: 목표 직무·회사·스킬·지역 기반으로 신규 공고와 마감 임박 공고 자동 수집
- **웹 앱 지원**: 브리핑 열람 및 선호도 설정을 웹에서 관리

## 아키텍처

3개의 독립 서비스로 구성된 모노레포입니다.

```
프론트엔드 (Next.js)
    ↓ REST API
백엔드 (Spring Boot)
    ↓ REST API
에이전트 (FastAPI + LangGraph)
    ↓
OpenAI LLM
```

| 서비스 | 기술 스택 | 역할 |
|--------|-----------|------|
| **Frontend** | Next.js 15, TypeScript, Tailwind, shadcn/ui | UI 및 사용자 세션 관리 |
| **Backend** | Spring Boot 3.4, Java 21, MySQL, Redis | API, 사용자 관리, 캐싱 |
| **Agent** | Python 3.11, FastAPI, LangGraph | AI 워크플로우 오케스트레이션, 콘텐츠 생성 |

**데이터 저장소:**

- MySQL: 사용자 정보, 브리핑 선호도, 채용 공고 풀, 브리핑 이력
- Redis: 세션 관리, 캐싱, 요청 속도 제한

## 빠른 시작

### 사전 요구사항

- Docker & Docker Compose
- Node.js 20+, Java 21+, Python 3.11+
- Make

### 개발 환경 실행

```bash
# 1. 클론 및 초기 설정
git clone <repo>
cd briefy
cp .env.example .env
# .env 파일에 필요한 값 입력

# 2. 의존성 설치
make setup

# 3. 인프라 + 백엔드 + 에이전트 시작
make dev

# 4. 별도 터미널에서 프론트엔드 시작
cd apps/frontend
npm run dev
```

**각 서비스 접속 주소:**

| 서비스 | 주소 |
|--------|------|
| 프론트엔드 | http://localhost:3000 |
| 백엔드 | http://localhost:8080 |
| 에이전트 | http://localhost:8000 |
| MySQL | localhost:3306 |
| Redis | localhost:6379 |

### 서비스별 개별 실행

```bash
make frontend        # 프론트엔드
make backend         # 백엔드 (MySQL + Redis 먼저 실행 필요)
make agent           # 에이전트
make db              # DB만 실행 (MySQL + Redis)
```

## 문서

- [아키텍처](docs/architecture.md) — 시스템 설계 및 데이터 흐름
- [API 레퍼런스](docs/api.md) — 백엔드 엔드포인트 목록
- [에이전트 워크플로우](docs/agent-workflow.md) — LangGraph 및 LLM 워크플로우
- [배포 가이드](docs/deployment.md) — AWS 프로덕션 배포

## 개발 명령어

```bash
make help            # 전체 명령어 목록
make dev             # 전체 서비스 시작 (Docker Compose)
make dev-build       # 이미지 재빌드 후 시작
make test            # 전체 테스트 실행
make lint            # 전체 린트 실행
make down            # 전체 서비스 종료
make logs-<서비스명>  # 특정 서비스 로그 스트리밍
```

## 프로젝트 구조

```
briefy/
├── apps/
│   ├── frontend/       # Next.js 웹 앱
│   ├── backend/        # Spring Boot API 서버
│   └── agent/          # Python FastAPI + LangGraph
│
├── docs/
│   ├── architecture.md
│   ├── api.md
│   ├── agent-workflow.md
│   └── deployment.md
│
├── scripts/
│   ├── dev-up.sh       # 개발 환경 시작
│   ├── dev-down.sh     # 개발 환경 종료
│   └── deploy.sh       # 프로덕션 배포
│
├── docker-compose.yml
├── Makefile
├── .env.example
└── CLAUDE.md           # AI 어시스턴트 가이드
```

## 테스트

```bash
# 전체 테스트
make test

# 프론트엔드
cd apps/frontend && npm test

# 백엔드
cd apps/backend && ./gradlew test

# 에이전트
cd apps/agent && poetry run pytest
```

## 환경 변수

`.env.example`을 `.env`로 복사한 뒤 값을 채워주세요.

```bash
cp .env.example .env
```

**필수 설정 항목:**

| 변수 | 설명 |
|------|------|
| `MYSQL_USER`, `MYSQL_PASSWORD` | 데이터베이스 접속 정보 |
| `REDIS_HOST`, `REDIS_PORT` | Redis 연결 정보 |
| `OPENAI_API_KEY` | OpenAI API 키 |
| `JWT_SECRET` | JWT 서명 시크릿 |
| `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` | Google OAuth 앱 정보 |
| `FRONTEND_BASE_URL` | 프론트엔드 주소 (기본값: http://localhost:3000) |

## 배포

### 프론트엔드

`main` 브랜치에 push하면 Vercel에 자동 배포됩니다.

### 백엔드 & 에이전트

AWS EC2에 아래 명령어로 배포합니다.

```bash
./scripts/deploy.sh all prod
```

자세한 내용은 [배포 가이드](docs/deployment.md)를 참고하세요.

## 기여

1. 기능 브랜치 생성 (`git checkout -b feature/기능명`)
2. 변경사항 커밋 (`git commit -m 'feat: 기능 설명'`)
3. 브랜치 푸시 (`git push origin feature/기능명`)
4. Pull Request 생성

개발 가이드라인은 [CLAUDE.md](CLAUDE.md)를 참고하세요.
