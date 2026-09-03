# 분류 파이프라인 평가 픽스처

> **목적**: 분류 파이프라인의 올바른 동작을 검증하기 위한 **레이블이 달린 합성 케이스** 모음.
> 실제 운영 공고 데이터나 실제 LLM 분류 결과가 **아니다**.
>
> mock/단위 테스트는 계약·정책 검증이며, 실제 LLM 분류 품질 평가는 별개의 opt-in 절차가 필요하다.

---

## 케이스 형식

```
케이스명       : 고유 식별자
공고 시나리오  : 공고 제목·직군·경력 요건 등 분류 입력 요약
기대 job_domain: IT | NON_IT | MIXED | UNKNOWN
기대 scope     : ROLE_SPECIFIC | MULTI_ROLE | OPEN_RECRUITMENT | UNKNOWN
tracks (요약)  : 트랙별 role_groups + exp 요건 (MULTI_ROLE/OPEN_RECRUITMENT 만)
사용자 선호    : roles / experienceLevels
기대 eligibility: eligible=T/F, roleMatch=…, expMatch=…
판정 근거      : 결과가 나오는 이유 설명
```

---

## CASE-01: 순수 백엔드 공고 — 신입 제외

```
케이스명       : CASE-01
공고 시나리오  : "백엔드 개발자 채용 (3년 이상 필수)"
                 job_domain=IT, scope=ROLE_SPECIFIC
                 role_groups=[BACKEND]
                 experience_requirement_type=REQUIRED, min_required_years=3
                 accepts_new_grad=false
사용자 선호    : roles=["backend"], experienceLevels=["ENTRY"]
기대 eligibility: eligible=false, roleMatch=DIRECT_MATCH, expMatch=EXCLUDED
판정 근거      : role은 BACKEND → DIRECT_MATCH. 신입(ENTRY) 사용자지만
                 accepts_new_grad=false + min_required_years=3 → EXCLUDED.
                 BriefingService의 ENFORCE 모드에서 EXPERIENCE_EXCLUDED로 필터 제외.
```

---

## CASE-02: 백엔드 공고 — 경력 일치

```
케이스명       : CASE-02
공고 시나리오  : "백엔드 서버 개발자 채용 (3~5년)"
                 job_domain=IT, scope=ROLE_SPECIFIC
                 role_groups=[BACKEND]
                 experience_requirement_type=REQUIRED, min_required_years=3
사용자 선호    : roles=["backend"], experienceLevels=["JUNIOR", "MID"]
기대 eligibility: eligible=true, roleMatch=DIRECT_MATCH, expMatch=FULL
판정 근거      : 경력직(JUNIOR/MID) → isNewGrad=false.
                 REQUIRED 경력 → FULL (경력직은 REQUIRED를 FULL로 처리).
                 RelevanceScorer에서 roleScore +20(SCORE_ROLE_MATCH), experienceScore +15.
```

---

## CASE-03: FULLSTACK 공고 — BACKEND 사용자와 일치

```
케이스명       : CASE-03
공고 시나리오  : "풀스택 개발자 채용"
                 job_domain=IT, scope=ROLE_SPECIFIC
                 role_groups=[FULLSTACK]
사용자 선호    : roles=["backend"], experienceLevels=["MID"]
기대 eligibility: eligible=true, roleMatch=DIRECT_MATCH, expMatch=FULL
판정 근거      : BACKEND 사용자의 acceptableTags에 FULLSTACK이 포함된다
                 (RoleGroup.BACKEND.compatiblePostingGroups() → {BACKEND, FULLSTACK}).
                 role_groups=[FULLSTACK] → DIRECT_MATCH.
```

---

## CASE-04: NON_IT 공고 — 무조건 제외

```
케이스명       : CASE-04
공고 시나리오  : "영업 담당자 채용"
                 job_domain=NON_IT, scope=ROLE_SPECIFIC
사용자 선호    : roles=["backend"], experienceLevels=["MID"]
기대 eligibility: eligible=false, roleMatch=MISMATCH, expMatch=UNKNOWN
판정 근거      : ClassificationAnalyzer.derive()가 job_domain=NON_IT에서
                 즉시 nonIt() 반환. ENFORCE 모드에서 ANALYSIS_NON_IT 이유로 필터 제외.
```

---

## CASE-05: GENERAL_IT 단일 직군 — BACKEND 사용자에게 BROAD_IT_MATCH (제외 안 함)

```
케이스명       : CASE-05
공고 시나리오  : "IT 개발자 채용"
                 job_domain=IT, scope=ROLE_SPECIFIC
                 role_groups=[GENERAL_IT]
사용자 선호    : roles=["backend"], experienceLevels=["MID"]
기대 eligibility: eligible=true, roleMatch=BROAD_IT_MATCH, expMatch=(경력 요건 따라)
판정 근거      : 직무 기반 제외는 role_groups가 전부 NON_DEV일 때만 발생한다.
                 GENERAL_IT은 비개발이 아니므로 BACKEND의 acceptableTags와 교집합이 없어도 제외하지 않는다.
                 직접 일치는 아니므로 BROAD_IT_MATCH로 통과하고, RelevanceScorer에서 roleScore +15.
                 (DIRECT_MATCH가 아니므로 backend 전용 +20 가산점은 받지 않는다.)
```

---

## CASE-06: 공개채용(OPEN_RECRUITMENT) — IT 트랙으로 BROAD_IT_MATCH

```
케이스명       : CASE-06
공고 시나리오  : "2026 상반기 공개채용"
                 job_domain=IT, scope=OPEN_RECRUITMENT
                 tracks:
                   - trackLabel="IT개발", roleGroups=[BACKEND, FULLSTACK],
                     recruitmentType=NEW_GRAD_HIRE, experience_requirement_type=NONE,
                     evidence="백엔드/풀스택 개발 직무 포함", unknown=false
                   - trackLabel="영업", roleGroups=[NON_DEV],
                     evidence="영업 직무", unknown=false
사용자 선호    : roles=["data"], experienceLevels=["ENTRY"]  ← IT이지만 DATA 선호
기대 eligibility: eligible=true, roleMatch=BROAD_IT_MATCH, expMatch=FULL
판정 근거      : scope=OPEN_RECRUITMENT. DATA 사용자 acceptableTags={DATA, AI_ML(compatible)}.
                 "IT개발" 트랙의 role_groups=[BACKEND, FULLSTACK] → DATA와 DIRECT_MATCH 없음.
                 hasAnyRoleMatchingTrack=false → BROAD_IT_MATCH 시도.
                 "IT개발" 트랙이 IT_TAGS에 속하고 exp NONE → FULL.
                 → BROAD_IT_MATCH+FULL. RelevanceScorer +15 (role) +15 (exp)
                 + 대기업 공채 가산점: scope=OPEN_RECRUITMENT +10, NEW_GRAD_HIRE+신입 사용자 +5 → 합산 상한 12.
```

> **대기업 공채 가산점 (openRecruitmentScore)**: 분류 경로 스코어링에서만 적용되는 editorial 가산점.
> - `postingScope=OPEN_RECRUITMENT` → +10 (`SCORE_OPEN_RECRUITMENT`, 사용자 무관)
> - `recruitmentType=NEW_GRAD_HIRE` **AND 사용자가 신입** → +5 (`SCORE_NEW_GRAD_HIRE`, 게이팅)
> - 두 가산점 합산 상한 12 (`SCORE_OPEN_RECRUITMENT_MAX`)
> - 경력직 사용자에게는 NEW_GRAD_HIRE 가산점이 붙지 않는다.

---

## CASE-07: MULTI_ROLE — DIRECT_MATCH 우선, BROAD_IT_MATCH 불필요

```
케이스명       : CASE-07
공고 시나리오  : "개발/비개발 통합 채용"
                 job_domain=IT, scope=MULTI_ROLE
                 tracks:
                   - trackLabel="백엔드", roleGroups=[BACKEND],
                     experience_requirement_type=REQUIRED, min_required_years=2, unknown=false
                   - trackLabel="마케팅", roleGroups=[NON_DEV], unknown=false
사용자 선호    : roles=["backend"], experienceLevels=["JUNIOR"]
기대 eligibility: eligible=true, roleMatch=DIRECT_MATCH, expMatch=FULL, matchedTrack="백엔드"
판정 근거      : 경력직(JUNIOR) → isNewGrad=false.
                 "백엔드" 트랙이 acceptableTags 에 BACKEND 포함 → DIRECT_MATCH.
                 REQUIRED + min 2년 → FULL (경력직).
                 BROAD_IT_MATCH 시도 없이 종료.
```

---

## CASE-08: 트랙 간 경력 혼합 금지 — 모두 EXCLUDED

```
케이스명       : CASE-08
공고 시나리오  : "백엔드 개발자 채용 (경력 5년 이상)"
                 job_domain=IT, scope=MULTI_ROLE
                 tracks:
                   - trackLabel="시니어백엔드", roleGroups=[BACKEND],
                     experience_requirement_type=REQUIRED, min_required_years=5,
                     accepts_new_grad=false, unknown=false
                   - trackLabel="IT기획", roleGroups=[GENERAL_IT],
                     experience_requirement_type=NONE, unknown=false
사용자 선호    : roles=["backend"], experienceLevels=["ENTRY"]  ← 신입
기대 eligibility: eligible=false, roleMatch=DIRECT_MATCH, expMatch=EXCLUDED
판정 근거      : 신입(ENTRY) → isNewGrad=true.
                 "시니어백엔드" 트랙이 BACKEND → role 일치. 그러나 min 5년, accepts_new_grad=false → EXCLUDED.
                 hasAnyRoleMatchingTrack=true이므로 BROAD_IT_MATCH 시도 없이 종료.
                 "IT기획" 트랙의 경력 데이터를 역할 불일치 트랙에서 가져오는 것은 금지됨.
                 → DIRECT_MATCH+EXCLUDED. ENFORCE 모드에서 EXPERIENCE_EXCLUDED 필터.
```

---

## CASE-09: 분석 미완료 (PENDING) — ENFORCE 모드에서 DEFERRED

```
케이스명       : CASE-09
공고 시나리오  : 분석 행 있음, classification_status=PENDING
사용자 선호    : roles=["backend"], experienceLevels=["MID"]
기대 eligibility: deferred("분류 미완료: PENDING")
판정 근거      : ClassificationAnalyzer가 SUCCEEDED/FALLBACK이 아닌 상태에서 deferred 반환.
                 ENFORCE 모드에서 RecommendationFilter.evaluateWithClassification()이
                 ANALYSIS_DEFERRED 이유로 필터 제외.
                 SHADOW 모드에서는 키워드 기반 결과를 그대로 사용하고 차이만 로그 기록.
```

---

## CASE-10: MODE=OFF — 분류 무시, 키워드 기반으로만 판정

```
케이스명       : CASE-10
공고 시나리오  : NON_IT 공고, classification_status=SUCCEEDED
사용자 선호    : roles=["backend"], experienceLevels=["MID"]
CLASSIFICATION_MODE=OFF
기대 eligibility: deferred("MODE=OFF")
판정 근거      : ClassificationAnalyzer가 OFF 모드에서 즉시 deferred 반환.
                 RecommendationFilter.evaluateWithClassification()은 OFF 분기에서
                 classify 결과 무시하고 evaluate()로 위임.
                 기존 키워드 기반 필터/스코러만 적용됨. 분류 pipeline 변경 전과 동일.
```

---

## 사용 방법

### 단위 테스트에서 픽스처 활용

```java
// ClassificationAnalyzerTest.java 내 analysis() 헬퍼로 합성 분석 결과 생성
JobPostingAnalysis analysis = analysis(
    JobDomain.IT, PostingScope.ROLE_SPECIFIC, ClassificationStatus.SUCCEEDED,
    List.of(RoleGroupTag.BACKEND),
    RecruitmentType.EXPERIENCED_HIRE, ExperienceRequirementType.REQUIRED, 3, null
);
Map<String, Object> preference = Map.of(
    "roles", List.of("backend"),
    "experienceLevels", List.of("ENTRY")
);
AnalysisEligibility result = ClassificationAnalyzer.derive(
    analysis, preference, ClassificationMode.ENFORCE
);
assertThat(result.eligible()).isFalse();
assertThat(result.roleMatch()).isEqualTo(RoleMatchType.DIRECT_MATCH);
assertThat(result.experienceMatch()).isEqualTo(ExperienceMatchType.EXCLUDED);
```

### 실제 LLM 품질 평가 (opt-in, 이번 작업에서는 실행하지 않음)

실제 LLM이 위 케이스와 일치하는 분류 결과를 생성하는지 확인하려면:

```bash
# 로컬 agent 서버가 실행 중이어야 함. 운영 환경에서는 실행 금지.
# 각 케이스의 합성 공고 텍스트를 /collections/classify에 직접 POST하여
# 응답의 job_domain, scope, tracks가 픽스처 기댓값과 일치하는지 수동 검증.
# 비용 발생 (OpenAI 토큰 사용). 테스트 스위트에 포함되지 않음.
poetry run python -c "
from app.schemas.classification import ClassifyRequest, ClassifyPostingInput, ClassifyInputQuality
import httpx, json

req = ClassifyRequest(
    request_id='eval-CASE-01',
    classifier_version='1.0.0',
    postings=[ClassifyPostingInput(
        job_posting_id=1,
        analysis_input_hash='aaaa' * 16,
        title='백엔드 개발자 채용 (3년 이상 필수)',
        company='테스트 주식회사',
        description='자격요건: Java Spring Boot 경력 3년 이상 필수. 신입 불가.',
        parsed_roles=['backend'],
        parsed_experience_level='3년 이상',
        parsed_employment_type='정규직',
        source_refs=[],
        input_quality=ClassifyInputQuality(
            has_description=True, has_roles=True,
            has_experience_level=True, description_truncated=False,
            description_length=50
        )
    )]
)
r = httpx.post('http://localhost:8000/collections/classify', json=req.model_dump(by_alias=True))
print(json.dumps(r.json(), indent=2, ensure_ascii=False))
"
```

---

*이 파일의 케이스는 합성 데이터이며 mock 테스트의 계약·정책 검증 목적으로 작성됐다.*
*실제 운영 공고 분류 품질 측정치가 아니다.*
