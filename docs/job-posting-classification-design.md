# Job Posting Classification — 설계 문서

> 버전: v1.0  
> 상태: 구현 중 (1단계)  
> 최종 수정: 2026-09-01

---

## 1. 개요

### 문제

현재 브리핑 추천 파이프라인은 공고 제목의 키워드 매칭과 경력 문자열 파싱만으로 직무·경력 적합성을 판단한다.
그 결과 다음 사례들이 신입 백엔드 개발자 브리핑에 포함되었다.

- "AML Manager" — NON_DEV 키워드 없음 → AMBIGUOUS → 통과
- "Sales Manager" — "sales" 키워드 없음 → AMBIGUOUS → 통과
- "경력 2년 이상 AI Research Engineer" — JUNIOR 분류 → 신입 PASS_PARTIAL

### 해결 방향

Daily Collection 수집 완료 후 신규·변경 공고만 LLM 분류기로 분석하여
`job_posting_analyses` 테이블에 구조화된 메타데이터를 저장한다.
Spring Hard Filter와 Scorer는 이 분석 결과를 우선 활용하고,
분류 데이터가 없으면 기존 키워드 기반 로직으로 폴백한다.

### 책임 경계 (유지)

| 역할 | 담당 |
|------|------|
| 수집·파싱·저장 | Spring Backend (CandidatePoolService) |
| LLM 분류 수행 | Agent (ClassificationService) |
| Hard Filter / Top 7 선정 | Spring Backend (RecommendationFilter, RecommendationSelector) |
| DB 직접 접근 | Agent 불가. 요청 본문에서 입력, 결과는 응답으로만 반환 |
| Top 7 재필터링·재랭킹 | Briefing Agent 불가 |

---

## 2. 분류 축 (Classification Axes)

### 2-1. `jobDomain` — IT 도메인 여부

| 값 | 의미 |
|----|------|
| `IT` | IT/개발/엔지니어링 직군 |
| `NON_IT` | 비IT 직군 (영업, 마케팅, HR, 재무 등) |
| `MIXED` | IT와 비IT 직군이 모두 모집되는 공채 |
| `UNKNOWN` | 제목·설명만으로 판별 불가 |

### 2-2. `postingScope` — 모집 범위

| 값 | 의미 |
|----|------|
| `ROLE_SPECIFIC` | 단일 직무 공고 |
| `MULTI_ROLE` | 여러 직무를 트랙별로 모집 |
| `OPEN_RECRUITMENT` | 공개채용 (트랙 구분 없거나 전 직군) |
| `UNKNOWN` | 판별 불가 |

### 2-3. `roleGroups` — 역할 그룹 태그 배열

기존 `RoleGroup` 정책 열거형과 대응하되, 분류 전용 추가 태그를 포함한다.

| 태그 | 의미 |
|------|------|
| `BACKEND` | 서버/백엔드 개발 |
| `FRONTEND` | 프론트엔드 개발 |
| `FULLSTACK` | 풀스택 개발 |
| `DATA` | 데이터 엔지니어/사이언티스트/분석가 |
| `AI_ML` | AI/ML 엔지니어 |
| `MOBILE` | iOS/Android 모바일 개발 |
| `DEVOPS_INFRA` | DevOps/SRE/인프라 |
| `GENERAL_IT` | 직군이 불명확한 IT 직무 (예: "IT 직군", "개발자 모집") |
| `OTHER_IT` | 개발 외 IT 직무 (QA, 보안, 기술지원, 스크럼 마스터 등) |
| `NON_DEV` | 비개발 직군 (마케팅, 영업, HR, 재무, 법무 등) |

> **주의**: `GENERAL_IT`는 개발 전체 직무의 wildcard가 아니다.
> "IT 직군 포함" 공채에서 구체적인 역할(BACKEND 등)을 알 수 없을 때에만 사용한다.
> 구체적인 역할이 파악되면 해당 태그를 사용한다.

### 2-4. `recruitmentType` — 채용 유형

`employmentType`(정규직/계약직 등 고용 형태)과 혼합하지 않는다.

| 값 | 의미 |
|----|------|
| `EXPERIENCED_HIRE` | 경력직 수시채용 |
| `NEW_GRAD_HIRE` | 신입 공개채용 (신입 한정) |
| `OPEN_HIRE` | 신입/경력 모두 지원 가능 |
| `INTERNSHIP` | 인턴십 |
| `UNKNOWN` | 판별 불가 |

### 2-5. 경력 관련 필드

| 필드 | 타입 | 의미 |
|------|------|------|
| `acceptsNewGrad` | `Boolean` (nullable) | 신입 지원 가능 여부. null = 판별 불가. false 강제 변환 금지. |
| `minRequiredYears` | `Integer` (nullable) | 필수 경력 최소 연수. 0 = 경력 무관. |
| `maxRequiredYears` | `Integer` (nullable) | 필수 경력 최대 연수. null = 상한 없음. |
| `experienceRequirementType` | enum | 경력 조건 성격 |
| `preferredExperience` | `String` (nullable) | 우대 경력 원문 텍스트 (필수와 구분) |

**`experienceRequirementType` 값:**

| 값 | 의미 |
|----|------|
| `REQUIRED` | 필수 경력 ("3년 이상 필수") |
| `PREFERRED` | 우대 경력 ("1년 이상 우대") |
| `NONE` | 경력 무관 |
| `UNKNOWN` | 판별 불가 |

> **경력 정보 부재 규칙**: 경력 정보가 없으면 `acceptsNewGrad=null`, `minRequiredYears=null`로 둔다.
> 0년, 신입 가능으로 임의 변환하지 않는다.
> 우대 경력은 필수 경력 필드(minRequiredYears 등)에 저장하지 않는다.

---

## 3. 다직무(Multi-track) 공고

### 3-1. `tracks` 배열

공개채용·다직무 공고는 `tracks` 배열에 모집 분야별 정보를 저장한다.
단일 역할 공고는 `tracks=[]`로 두고 공고 수준 필드를 사용한다.

각 track 오브젝트:

```json
{
  "trackLabel": "서버/백엔드",
  "jobDomain": "IT",
  "roleGroups": ["BACKEND"],
  "acceptsNewGrad": true,
  "minRequiredYears": null,
  "maxRequiredYears": null,
  "experienceRequirementType": "NONE",
  "recruitmentType": "OPEN_HIRE",
  "employmentType": "정규직",
  "evidence": "공고 본문 발췌 또는 분류 근거",
  "unknown": false
}
```

### 3-2. tracks 의미론

- 동일 track에서 직무와 경력 조건을 함께 만족해야 한다.
- 공고 전체의 `roles`, `acceptsNewGrad`를 합집합으로 평가하지 않는다.
- 공통 자격 조건을 track에 상속할 때는 "공통 조건"이라는 근거(evidence)가 있어야 한다.
- 모집 분야 연결이 불명확하면 `"unknown": true`로 표시한다.
- "신입공채", "전 직군", "IT" 등의 표현은 `trackLabel`에 쓰되,
  실제 역할 배열(`roleGroups`)에 뒤섞지 않는다.

---

## 4. 분류 메타데이터

| 필드 | 의미 |
|------|------|
| `analysisInputHash` | title+company+description+parsedRoles+parsedExperienceLevel+parsedEmploymentType의 SHA-256 (hex 64자). 입력이 같으면 동일한 분류기·버전에서 재분류하지 않는다. |
| `classifierVersion` | 프롬프트·규칙의 논리 버전. SemVer 권장. |
| `modelName` | LLM 모델 식별자. |
| `classificationMethod` | `LLM` / `RULE_BASED` / `MANUAL` |
| `classificationStatus` | 상태 (아래 상태 다이어그램 참고) |
| `evidence` | 분류 근거 원문 (진단용) |
| `uncertaintyReasons` | 불확실성 이유 목록 |
| `inputCompleteness` | 0.0~1.0. 입력 품질 점수 (진단용) |
| `descriptionTruncated` | description이 길어서 잘렸으면 true |
| `confidence` | **진단용 전용.** LLM의 자기보고 confidence. 추천 허용·제외 기준으로 사용하지 않는다. |
| `classifiedAt` | 분류 완료 시각 |

### 상태 다이어그램

```
PENDING → PROCESSING → SUCCEEDED
                     → FALLBACK   (규칙 기반 폴백 완료)
                     → FAILED     (재시도 가능)
PENDING → CONFLICT               (hash 불일치 등 재해결 필요)
FAILED  → PROCESSING             (재시도)
```

### 운영 필드

| 필드 | 의미 |
|------|------|
| `attemptCount` | 분류 시도 횟수 |
| `nextRetryAt` | 다음 재시도 가능 시각 (지수 백오프) |
| `claimToken` | 분산 처리 충돌 방지용 UUID |
| `leaseUntil` | 클레임 만료 시각 |
| `lastErrorCode` | 마지막 오류 코드 (간결, 민감 정보 제외) |

---

## 5. DB 스키마: `job_posting_analyses`

Flyway 마이그레이션 번호: **V23**

`job_postings`에 대한 optional 1:1 분석 테이블.
기존 공고(V23 이전 수집분)에는 행이 없으며, 이는 정상이다.
분류 행이 없으면 Spring은 기존 키워드 기반 로직으로 폴백한다.

FK 삭제 정책: 기존 `fk_jps_job_posting`과 동일하게 `ON DELETE RESTRICT` (명시하지 않으면 MySQL 기본값).
`job_postings` 삭제 전 분석 행을 먼저 삭제해야 한다. 불필요한 `CASCADE`를 도입하지 않는다.

`DEFAULT 'PENDING'`은 DB 삽입 시 기본값이다. 기존에 분석 행이 없는 공고에는 적용되지 않는다.

---

## 6. Java–Python 계약

### 요청: `POST /collections/classify`

Spring → Agent

```json
{
  "requestId": "uuid-v4",
  "classifierVersion": "1.0.0",
  "postings": [
    {
      "jobPostingId": 123,
      "analysisInputHash": "sha256hex64chars",
      "title": "백엔드 개발자",
      "company": "네이버",
      "description": "...",
      "parsedRoles": ["백엔드"],
      "parsedExperienceLevel": "3년 이상",
      "parsedEmploymentType": "정규직",
      "sourceRefs": [
        { "source": "saramin", "sourceUrl": "https://...", "sourceExternalId": "123" }
      ],
      "inputQuality": {
        "hasDescription": true,
        "hasRoles": true,
        "hasExperienceLevel": true,
        "descriptionTruncated": false,
        "descriptionLength": 850
      }
    }
  ]
}
```

**요청 계약:**
- 동일 요청 내 `jobPostingId` 중복 금지
- `analysisInputHash`는 Spring이 계산해서 전송

### 응답: `POST /collections/classify`

Agent → Spring

```json
{
  "requestId": "uuid-v4",
  "classifierVersion": "1.0.0",
  "results": [
    {
      "jobPostingId": 123,
      "analysisInputHash": "sha256hex64chars",
      "jobDomain": "IT",
      "postingScope": "ROLE_SPECIFIC",
      "roleGroups": ["BACKEND"],
      "recruitmentType": "EXPERIENCED_HIRE",
      "tracks": [],
      "acceptsNewGrad": false,
      "minRequiredYears": 3,
      "maxRequiredYears": null,
      "experienceRequirementType": "REQUIRED",
      "preferredExperience": null,
      "method": "LLM",
      "status": "SUCCEEDED",
      "evidence": "경력 3년 이상 필수 명시",
      "uncertaintyReasons": [],
      "confidence": 0.92,
      "inputCompleteness": 0.9,
      "descriptionTruncated": false
    }
  ],
  "tokenUsage": {
    "promptTokens": 1200,
    "completionTokens": 300,
    "totalTokens": 1500
  },
  "warnings": []
}
```

**응답 계약:**
1. `response.requestId == request.requestId`
2. `response.classifierVersion == request.classifierVersion`
3. `results`의 `jobPostingId` 집합 == 요청 `jobPostingId` 집합 (누락·추가·중복 불허)
4. 각 result의 `analysisInputHash == request`의 동일 ID 해시 (불일치 → CONFLICT)
5. `status=FAILED`인 result의 분류 필드는 null 허용
6. `postingScope=MULTI_ROLE` 또는 `OPEN_RECRUITMENT`이면 `tracks` 비어 있지 않아야 함

**요청에 포함하지 않는 것:**
- 사용자 선호도 / 회사 가산점 / 추천 점수 / rank

---

## 7. analysisInputHash 계산 방법

Spring이 classify 요청 시 계산한다. Agent는 받은 값을 응답에 그대로 에코한다.

```
입력 = title + "\n" + company + "\n" + (description ?? "") + "\n"
     + sorted(parsedRoles).join(",") + "\n"
     + (parsedExperienceLevel ?? "") + "\n"
     + (parsedEmploymentType ?? "")
hash = SHA-256(UTF-8(입력)) → hex lowercase 64자
```

같은 공고의 `contentHash`가 변경되거나 `classifierVersion`이 변경되면 재분류 대상이 된다.
`analysisInputHash == 기존 hash` AND `classifierVersion == 기존 version` AND `status == SUCCEEDED|FALLBACK`이면 재분류하지 않는다.

---

## 8. 구현 단계

| 단계 | 내용 | 상태 |
|------|------|------|
| 1 | 설계 문서, DB 엔티티, Java↔Python 계약 DTO | ✅ 진행 중 |
| 2 | Spring 작업자: 신규·변경 공고 식별, classify 호출, 결과 저장 | ⬜ 예정 |
| 3 | RecommendationFilter/Scorer에서 분석 결과 활용 | ⬜ 예정 |
| 4 | Agent ClassificationService 구현 | ⬜ 예정 |
| 5 | Agent POST /collections/classify 엔드포인트 | ⬜ 예정 |

---

## 9. 결정된 제약사항

- `GENERAL_IT`는 구체적 역할을 모를 때만 사용한다 (wildcard 아님).
- `recruitmentType`은 고용형태(정규직/계약직)와 혼합하지 않는다.
- `acceptsNewGrad=null`은 판별 불가를 의미한다. `false`로 강제 변환하지 않는다.
- `confidence`는 LLM 자기보고 값이며 진단용이다. 추천 허용·제외 판단에 사용하지 않는다.
- 비IT 공고도 수집·저장한다. 추천 제외는 분석 결과 기반 Hard Filter에서 처리한다.
- 분류 실패·지연으로 수집 저장을 롤백하지 않는다.
- Agent는 DB에 직접 접근하지 않는다.
- 기업 인지도 점수, 임베딩, 사용자 피드백, 새 산업 분류는 이 설계의 범위 밖이다.
