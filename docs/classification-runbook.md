# 공고 분류 파이프라인 운영 런북

> 대상: 배포·운영 담당자.  
> 이 런북은 분류 파이프라인의 설정, 배포 순서, 모드 전환, 장애 대응 절차를 다룬다.

---

## 1. 환경 변수

| 변수 | 기본값 | 설명 |
|------|--------|------|
| `CLASSIFICATION_MODE` | `OFF` | 분류 파이프라인 동작 모드. `OFF \| SHADOW \| ENFORCE` |
| `CLASSIFIER_VERSION` | `1.0.0` | 분류기 버전 식별자. 동일 hash+version 조합이면 재분류 생략 |
| `CLASSIFICATION_WORKER_FIXED_DELAY_MS` | `60000` | 워커 실행 간격 (ms) |
| `CLASSIFICATION_WORKER_MAX_ITEMS_PER_RUN` | `100` | 워커 1회 실행당 최대 처리 공고 수 |
| `CLASSIFICATION_WORKER_BUDGET_SECONDS` | `600` | 워커 1회 실행 최대 허용 시간 (초) |
| `CLASSIFICATION_WORKER_BATCH_SIZE` | `5` | Agent 호출당 배치 크기 |
| `CLASSIFICATION_WORKER_MAX_CONCURRENT_REQUESTS` | `2` | Agent 동시 호출 수 |
| `CLASSIFICATION_WORKER_AGENT_TIMEOUT_SECONDS` | `90` | Agent 단일 호출 타임아웃 (초) |
| `CLASSIFICATION_WORKER_LEASE_SECONDS` | `700` | PROCESSING 상태 리스 만료 시간 (초) |
| `CLASSIFICATION_WORKER_MAX_ATTEMPTS` | `5` | 분류 최대 재시도 횟수 |
| `CLASSIFICATION_WORKER_BACKOFF_BASE_SECONDS` | `60` | 재시도 백오프 기반 시간 (초). 지수 증가 |

### 모드별 동작 요약

| 모드 | 분류 결과 반영 | 필터·스코어 영향 | Shadow 로그 |
|------|----------------|-----------------|-------------|
| `OFF` | 안 함 | 없음 (기존 키워드 기반만) | 없음 |
| `SHADOW` | 안 함 | 없음 (기존 키워드 기반 결과 사용) | 차이 발생 시 `SHADOW_FILTER_DIFF` / `SHADOW_SCORE_DIFF` 로그 기록 |
| `ENFORCE` | 함 | 분류 기반 필터·스코어 적용 | 없음 |

---

## 2. 배포 순서

> **DDL 무중단은 보장하지 않는다.** 배포 전 점검창(maintenance window) 또는 새벽 트래픽 최저 시간대를 선택할 것.

### 최초 배포 (신규 테이블 포함)

1. **DB 마이그레이션 먼저 실행**  
   `job_posting_analyses` 테이블은 Flyway V23 마이그레이션으로 생성된다.  
   배포 전 스테이징 환경에서 마이그레이션 성공 확인 후 운영 적용.

2. **`CLASSIFICATION_MODE=OFF`로 백엔드 배포**  
   새 코드 배포 후 기존 기능이 그대로 동작하는지 확인.

3. **`CLASSIFICATION_MODE=SHADOW`로 전환**  
   애플리케이션 재시작 없이 환경변수를 변경하거나, 재배포.  
   로그에서 `SHADOW_FILTER_DIFF`·`SHADOW_SCORE_DIFF`를 모니터링하여  
   분류 결과가 기존 키워드 기반 결과와 얼마나 다른지 파악.

4. **Shadow 충분히 검증 후 `CLASSIFICATION_MODE=ENFORCE`로 전환**  
   SHADOW 기간 동안 이상 결과가 없으면 ENFORCE로 전환.

### 업데이트 배포 (분류 로직 변경, 버전 불변)

일반 롤링 배포. 분류 버전 변경 없으면 기존 분류 결과를 재사용한다.

### 분류기 버전 업그레이드 (`CLASSIFIER_VERSION` 변경)

1. `CLASSIFIER_VERSION`을 새 버전(예: `1.1.0`)으로 설정하여 배포.
2. 기존 SUCCEEDED 분석 중 hash+version 조합이 다른 공고는 PENDING으로 재분류 대상이 됨.
3. 워커가 점진적으로 재분류 수행. **전체 공고를 한 번에 재분류하지 않는다.**

---

## 3. 백필(Backfill) 명령

> 운영 DB에 적용된 Flyway 버전을 먼저 확인하라.  
> `flyway_schema_history` 테이블에서 실제 적용 버전을 확인하는 것이 기준이다.  
> 코드 파일 목록만으로 운영 DB의 마이그레이션 상태를 단정하지 않는다.

```sql
-- 운영 DB 마이그레이션 상태 확인
SELECT version, description, success, installed_on 
FROM flyway_schema_history 
ORDER BY installed_rank DESC 
LIMIT 10;
```

### 분석 미완료 공고 수 확인

```sql
SELECT classification_status, COUNT(*) 
FROM job_posting_analyses 
GROUP BY classification_status;

-- PENDING이 많으면 워커가 처리 중이거나 재시작 필요
SELECT COUNT(*) FROM job_posting_analyses WHERE classification_status = 'PROCESSING';
```

### 리스 만료된 PROCESSING 강제 초기화 (긴급 복구용)

```sql
-- 리스 만료 시간(lease_expires_at) 지난 PROCESSING 행을 PENDING으로 복구
-- CLASSIFICATION_WORKER_LEASE_SECONDS 기본값 700초
UPDATE job_posting_analyses 
SET classification_status = 'PENDING', 
    processing_started_at = NULL, 
    lease_expires_at = NULL
WHERE classification_status = 'PROCESSING' 
  AND lease_expires_at < NOW();
```

### Admin API를 통한 백필 트리거 (opt-in)

```bash
# 스테이징에서만 사용. 운영에서는 워커 자동 처리에 맡길 것.
# 최대 100건씩 점진적으로 처리됨.
curl -X POST "http://localhost:8080/admin/analysis/backfill" \
  -H "X-Admin-Key: ${ADMIN_API_KEY}" \
  -d '{"batchSize": 50, "dryRun": true}'

# dryRun=true로 먼저 확인 후 false로 실행
```

---

## 4. 롤백 절차

> **롤백은 우선 기능 플래그(모드 전환)로 수행한다. 새 테이블을 drop하지 않는다.**

### 빠른 롤백 (기능 플래그)

```bash
# 환경변수 변경 후 재배포 또는 재시작
CLASSIFICATION_MODE=OFF
```

OFF 모드로 전환하면 분류 파이프라인 코드가 분기되어 기존 키워드 기반 결과만 사용한다.  
`job_posting_analyses` 테이블은 유지되며 데이터 손실 없음.

### 코드 롤백이 필요한 경우

이전 Docker 이미지로 재배포. DB는 건드리지 않는다.  
이전 코드는 `job_posting_analyses` 테이블이 있어도 참조하지 않으므로 안전하다.

---

## 5. 주요 로그 확인

### Shadow 모드 차이 로그

```
SHADOW_FILTER_DIFF: postingId=12345, keyword=PASS, classification=ANALYSIS_ROLE_MISMATCH
SHADOW_SCORE_DIFF: postingId=12345, keywordScore=65, classificationScore=30
```

- `SHADOW_FILTER_DIFF` 비율이 높으면 ENFORCE 전환 시 노출 공고 수가 크게 변할 수 있음.
- `SHADOW_SCORE_DIFF`로 스코어 차이 분포를 파악하여 ENFORCE 전환 영향을 사전 예측.

### 워커 상태 로그

```
ClassificationWorker: 실행 시작, 처리 대상 N건
ClassificationWorker: 배치 처리 완료 N건, 소요 Xms
ClassificationWorker: 예산 초과로 조기 종료 (processed=N)
ClassificationWorker: 재시도 예정 (postingId=…, attempt=N, nextDelay=Xs)
```

### 분류 실패 알람 기준

- `classification_status = 'FAILED'` 건이 24시간 내 전체의 10%를 초과하면 조사 필요.
- Agent 타임아웃 반복 시 Agent 서버 상태 점검.

---

## 6. ENFORCE 모드 전환 체크리스트

- [ ] `flyway_schema_history`에서 V23 마이그레이션 성공 확인
- [ ] `job_posting_analyses` 테이블에 데이터가 있고 SUCCEEDED 비율이 충분한지 확인 (70%+ 권장)
- [ ] SHADOW 모드 24시간 이상 운영 후 `SHADOW_FILTER_DIFF` 로그 분석 완료
- [ ] 이상 케이스(NON_IT 공고가 PASS로 찍힌 경우 등) 없음 확인
- [ ] 스테이징에서 `CLASSIFICATION_MODE=ENFORCE` 브리핑 생성 성공 확인
- [ ] `CLASSIFICATION_MODE=OFF`로 즉시 롤백 가능한 배포 파이프라인 준비 완료
