# Agent Workflow Guide

## Overview

The Agent service (FastAPI + LangGraph) receives requests from the Spring Boot backend, processes candidate data, and returns formatted briefing content. It exposes two endpoints:

| Endpoint | Caller | Purpose |
|---|---|---|
| `POST /collections/daily` | Spring Boot scheduler / admin | Accept seed keywords, return raw job postings stub |
| `POST /briefings/generate` | Spring Boot `AgentClient` | Receive pre-scored candidate pool, filter/rank/format Markdown briefing |

**Critical design constraint — Spring owns all DB access:**

The Agent is **stateless**. It does not connect to MySQL or any other database.

- Seed keywords (aggregated from `user_briefing_preferences`) are assembled by Spring and sent in the collection request.
- Raw job postings returned by the Agent are upserted into `job_postings` by Spring (`CandidatePoolService`).
- The candidate pool for briefing generation is loaded from DB by Spring, pre-scored, and sent in the `candidatePool` field of the briefing request.
- The Agent reads only what is in the request body.

**Current 1st MVP implementation status:**

| Feature | Status |
|---|---|
| Daily Collection pipeline (V2) | Implemented — 10-stage deterministic pipeline; `DailyCollectionService` orchestrates adapter → normalize → dedup → filter → budget select |
| FixtureAdapter (local dev) | Implemented — deterministic fixture postings, no network calls, enabled by default |
| JasoseolAdapter (real source) | Implemented — sitemap-based enumeration + concurrent page scraping; opt-in via config flag |
| SaraminAdapter (real source) | Implemented — official Saramin public API; opt-in via `JOB_COLLECTION_ENABLE_SARAMIN=true` and `SARAMIN_ACCESS_KEY` |
| OfficialCompanyAdapter | Implemented — dispatches per-company sources from `officialCompanySources`; supports `SITEMAP`, `RSS`, `CUSTOM` adapter types |
| LLM summarization / formatting | Implemented — 2-call strategy (enrichment + synthesis) via `gpt-4o-mini`; deterministic fallback when `OPENAI_API_KEY` is absent or LLM fails |
| Job briefing filter + rank + Markdown | Implemented — deterministic filter/rank/select + LLM-enhanced enrichment and synthesis (with deterministic fallback) |
| Scheduler (06:00 KST collection, 08:00 KST briefing) | Implemented, disabled by default (`briefy.scheduler.enabled: false`); only generates briefings for users with `briefing_email_enabled = true` |
| Company news briefing (1.5 MVP) | Not implemented; `companyIssues` is always `[]` |
| Industry / market briefing (2nd MVP) | Not implemented; `industryIssues` is always `[]` |

---

## DailyCollectWorkflow

Triggered by the Spring Boot scheduler (`DailyCollectionScheduler`) or the admin endpoint `POST /api/admin/collections/daily`. Spring aggregates seed keywords from all active `user_briefing_preferences`, sends them to the Agent, and upserts the returned job postings into the `job_postings` candidate pool.

### Responsibilities — who does what

| Step | Owner |
|---|---|
| Aggregate seed keywords from active user preferences | Spring (`DailyCollectionService`) |
| Resolve company names → `companies` rows (`companyProfiles`) | Spring (`DailyCollectionService.resolveCompanyProfiles`) |
| Resolve company sources → `company_sources` rows (`officialCompanySources`) | Spring (`DailyCollectionService.resolveOfficialSources`) |
| Create `collection_jobs` row and track lifecycle | Spring (`CollectionJobService`) |
| Call `POST /collections/daily` | Spring (`AgentClient`) |
| Generate / scrape raw job postings (Pipeline V2) | Agent (`DailyCollectionService`) |
| Deduplicate and merge across sources (10-stage pipeline) | Agent |
| Upsert `job_postings` and `job_posting_sources` rows | Spring (`CandidatePoolService`) |
| Mark `collection_jobs` COMPLETED / FAILED | Spring |

### Agent request (Spring → Agent)

```json
{
  "collectionJobId": 42,
  "collectDate": "2026-07-01",
  "categories": ["JOB_POSTING"],
  "seedKeywords": {
    "roles": ["백엔드 개발자", "풀스택 개발자"],
    "companies": ["네이버", "카카오", "라인"],
    "companySizes": ["대기업", "중견기업"],
    "industries": ["IT/인터넷", "게임"],
    "skills": ["Spring Boot", "Java", "Kotlin"],
    "locations": ["서울", "판교"],
    "experienceLevels": ["신입", "3년 이상"],
    "employmentTypes": ["정규직"],
    "keywords": []
  },
  "options": {
    "lookbackDays": 3,
    "deadlineWithinDays": 14,
    "discoveryLimitPerSource": 50,
    "detailFetchLimitPerSource": 50,
    "maxResultsPerSource": 50,
    "maxTotalResults": 200
  },
  "companyProfiles": [
    {
      "id": 1,
      "canonicalName": "네이버",
      "normalizedName": "네이버",
      "companySize": "대기업",
      "industryCodes": ["IT/인터넷"]
    }
  ],
  "officialCompanySources": [
    {
      "companyId": 1,
      "sourceType": "CAREERS_PAGE",
      "sourceUrl": "https://recruit.navercorp.com/sitemap.xml",
      "adapterType": "SITEMAP",
      "configJson": null
    }
  ]
}
```

| Field | Notes |
|---|---|
| `collectionJobId` | Echo'd back in the response so Spring can correlate |
| `seedKeywords` | Aggregated by Spring from all active `user_briefing_preferences` for each category |
| `seedKeywords.companySizes` | Aggregated from `preference_json.companySizes` |
| `seedKeywords.industries` | Aggregated from `preference_json.industries` |
| `options.lookbackDays` | Collect postings published within the last N days (default: 3) |
| `options.deadlineWithinDays` | Flag postings with deadline within N days (default: 14) |
| `options.discoveryLimitPerSource` | Max URLs to enumerate per source (default: 50) |
| `options.detailFetchLimitPerSource` | Max detail page fetches per source (default: 50) |
| `options.maxResultsPerSource` | Max postings selected per source after pipeline (default: 50) |
| `options.maxTotalResults` | Hard cap on total postings returned (default: 200) |
| `companyProfiles` | Company Registry rows resolved by Spring for the companies in `seedKeywords.companies` |
| `officialCompanySources` | Active `company_sources` rows for those companies; used by `OfficialCompanyAdapter` |

### Agent response (Agent → Spring)

```json
{
  "collectionJobId": 42,
  "collectDate": "2026-07-01",
  "jobPostings": [
    {
      "source": "jasoseol",
      "sourceUrl": "https://jasoseol.com/recruit/12345",
      "companyName": "네이버",
      "title": "네이버 백엔드 개발자",
      "position": "백엔드 개발자",
      "employmentType": "정규직",
      "experienceLevel": "신입",
      "location": "서울",
      "deadline": "2026-07-15",
      "skills": ["Spring Boot", "Java"],
      "roles": ["백엔드 개발자"],
      "description": "채용 공고 설명",
      "postedAt": "2026-07-01T09:00:00",
      "contentHash": "a3f2...sha256hex...64chars",
      "sourceRecordKey": "b1c2...sha256hex...64chars",
      "canonicalFingerprint": "c3d4...sha256hex...64chars",
      "sourceRefs": [
        {
          "source": "jasoseol",
          "sourceExternalId": null,
          "sourceUrl": "https://jasoseol.com/recruit/12345",
          "sourceRecordKey": "b1c2...sha256hex...64chars"
        }
      ]
    }
  ],
  "companyIssues": [],
  "industryIssues": [],
  "stats": {
    "discovered": 80,
    "fetched": 50,
    "parsed": 48,
    "exactDuplicates": 3,
    "crossSourceMerged": 2,
    "expiredFiltered": 5,
    "staleFiltered": 1,
    "truncated": 0,
    "final": 37,
    "collectedCount": 80,
    "deduplicatedCount": 3,
    "jobPostingCount": 37
  },
  "warnings": []
}
```

| Field | Notes |
|---|---|
| `jobPostings[].sourceRecordKey` | SHA-256 identity key for this source record; stored in `job_posting_sources.source_record_key` |
| `jobPostings[].canonicalFingerprint` | SHA-256(norm_company + norm_title + deadline); cross-source merge key |
| `jobPostings[].sourceRefs` | All source records that were merged into this canonical posting |
| `stats.discovered` | URLs enumerated across all adapters before any fetching |
| `stats.fetched` | Detail pages actually fetched (bounded by `detailFetchLimitPerSource`) |
| `stats.parsed` | Postings successfully parsed from fetched pages |
| `stats.exactDuplicates` | Items removed by same-source `source_record_key` dedup |
| `stats.crossSourceMerged` | Items merged across sources by `canonical_fingerprint` |
| `stats.expiredFiltered` | Items removed because `deadline < collect_date` |
| `stats.staleFiltered` | Items removed because `posted_at < collect_date - lookback_days` |
| `stats.truncated` | Items cut by budget selection (`maxTotalResults`) |
| `stats.final` | Final count of returned postings |
| `stats.collectedCount` | Alias for `discovered` (backward compat) |
| `stats.deduplicatedCount` | Alias for `exactDuplicates` (backward compat) |
| `stats.jobPostingCount` | Alias for `final` (backward compat) |

After receiving this response, Spring upserts all items in `jobPostings` into `job_postings` and `job_posting_sources` via `CandidatePoolService.upsertJobPostings`.

### Pipeline structure — V2 (implemented)

`POST /collections/daily` is handled by `DailyCollectionService` (`app/services/daily_collection.py`). It is a sequential 10-stage deterministic pipeline — no LangGraph, no LLM.

```
DailyCollectRequest
      │
      ├─ [1] Category gate: JOB_POSTING not in categories → return empty response
      │
      ├─ [2] Build adapter list from config flags
      │         JOB_COLLECTION_USE_FIXTURE=true         → add FixtureAdapter
      │         JOB_COLLECTION_ENABLE_JASOSEOL=true → add JasoseolAdapter
      │         JOB_COLLECTION_ENABLE_SARAMIN=true      → add SaraminAdapter
      │         officialCompanySources present          → add OfficialCompanyAdapter
      │         all false / empty → FixtureAdapter (safe fallback)
      │
      ├─ [3] Collect: fetch raw postings from each adapter; accumulate AdapterSourceStats
      │
      ├─ [4] Normalize: clean whitespace, lower-case fields, compute content_hash and
      │         canonical_fingerprint per posting
      │
      ├─ [5] Source dedup: drop exact duplicates by source_record_key within each adapter
      │
      ├─ [6] Deadline filter: remove postings where deadline < collect_date
      │
      ├─ [7] Company canonicalization: match normalized company name against
      │         companyProfiles; attach canonical_fingerprint using Company Registry data
      │
      ├─ [8] Cross-source dedup: group by canonical_fingerprint; keep one canonical posting,
      │         collect all source URLs into source_refs
      │
      ├─ [9] Score & sort: deterministic relevance score (no LLM)
      │
      └─ [10] Budget select: apply discoveryLimitPerSource / maxResultsPerSource /
                maxTotalResults; reserve _EXPLORATION_RATIO (0.2) of budget for
                lower-scored postings to avoid over-fitting to top companies
                    │
                    └─ DailyCollectResponse (jobPostings, stats, warnings)
```

Spring (not the Agent) owns the save step after receiving the response.

---

## Adapter Architecture

Adapters live in `app/adapters/`. Each adapter implements the `JobBoardAdapter` ABC:

```python
class JobBoardAdapter(ABC):
    @property
    @abstractmethod
    def source_name(self) -> str: ...

    @abstractmethod
    async def fetch(
        self,
        seed_keywords: SeedKeywords,
        options: CollectionOptions,
        collect_date: date | None = None,
    ) -> AdapterResult: ...
```

`AdapterResult` contains a list of `RawJobPosting` objects, an accumulated `warnings` list, and an `AdapterSourceStats` dataclass (`discovered`, `fetched`, `parsed`, `selected`). Adapters never raise to the caller — all per-item failures are caught and appended to `warnings`.

### FixtureAdapter

**File:** `app/adapters/fixture.py`  
**Source name:** `fixture`  
**Default:** active when `JOB_COLLECTION_USE_FIXTURE=true` (default)

- Generates 3–5 deterministic job postings from seed keywords; no network calls.
- Output is stable across calls for the same `collect_date` — identical content hashes every run.
- Safe for local development, CI, and offline testing.
- Postings use `source="fixture"` and `source_url="https://fixture.local/jobs/{id}"` so they are identifiable in the DB.

### JasoseolAdapter

**File:** `app/adapters/jasoseol.py`  
**Source name:** `jasoseol`  
**Default:** inactive; enabled when `JOB_COLLECTION_ENABLE_JASOSEOL=true`

#### Strategy

1. Fetch two sitemap XMLs from jasoseol.com:
   - `/sitemap/employment_companies.xml` (정규직/계약직 공고)
   - `/sitemap/intern_employment_companies.xml` (인턴 공고)
2. Parse `<lastmod>` dates; keep only URLs where `lastmod >= collect_date - lookback_days`.
   URLs without `<lastmod>` are included conservatively.
3. Sort by `lastmod` descending; cap at `options.discovery_limit_per_source` per sitemap.
4. Fetch individual `/recruit/{id}` pages concurrently (max 5 at a time via `asyncio.Semaphore`).
5. Parse each page HTML for posting data.

#### HTML parsing

Jasoseol.com is a Next.js app. Listing pages are client-side rendered and carry no data; individual posting pages have server-rendered HTML. CSS class names are hashed modules — the parser uses semantic selectors instead:

| Field | Extraction method |
|---|---|
| `company_name` | `img[alt*="기업 아이콘"]` → strip suffix "기업 아이콘" |
| `title` | First `<h1>` or `<h2>` element |
| `deadline` | Last Korean date match in page text (`YYYY년 MM월 DD일`) |
| `employment_type` | Keyword search for 정규직/계약직/인턴/파견직/프리랜서 |

#### Known limitations

| Field | Status |
|---|---|
| `position` | Not reliably extractable — falls back to `title` |
| `location` | Not reliably extractable — `None` |
| `experience_level` | Not reliably extractable — `None` |
| `skills` | Not reliably extractable — `[]` |
| `roles` | Not reliably extractable — `[]` |
| `description` | Not extracted — `None` |
| `posted_at` | Not available in page HTML — `None` |

Fields left as `None`/`[]` are not fabricated. Spring and the briefing workflow handle missing fields gracefully.

#### Robots.txt

jasoseol.com allows all paths under `/`. No login, no CAPTCHA bypass, no browser automation is used or needed.

#### Error handling

All per-operation failures (network timeout, HTTP error, XML parse error, HTML parse error) are caught, logged, and appended to `AdapterResult.warnings`. The adapter never raises to `DailyCollectionService`.

### SaraminAdapter

**File:** `app/adapters/saramin.py`  
**Source name:** `saramin`  
**Default:** inactive; enabled when `JOB_COLLECTION_ENABLE_SARAMIN=true` and `SARAMIN_ACCESS_KEY` is set

#### Strategy

1. Build a bounded query plan from `seed_keywords` — one query per (role, location) combination, capped at `SARAMIN_MAX_QUERIES_PER_COLLECT` (default: 10).
2. For each query, call the official Saramin public API (`GET /job-search`) with pagination, advancing `start` by the number of results returned each page.
3. Deduplicates job keys using a `frozenset` within the adapter run to avoid re-fetching.
4. No HTML scraping — all data comes from the structured API response.

#### Known limitations

| Field | Status |
|---|---|
| `description` | Not returned by the API; `None` |
| `roles` | Not returned by the API; `[]` |

#### API credentials

Requires a free-tier Saramin developer API key from `https://oapi.saramin.co.kr`. Set `SARAMIN_ACCESS_KEY` in `apps/agent/.env`.

---

### OfficialCompanyAdapter

**File:** `app/adapters/official_company.py`  
**Source name:** varies (uses `source_type` from the `CompanySource` row)  
**Default:** active when `officialCompanySources` in the collection request is non-empty

#### Strategy

For each `OfficialCompanySource` in the request, dispatches based on `adapter_type`:

| `adapter_type` | Handler | Description |
|---|---|---|
| `SITEMAP` | `_handle_sitemap` | Fetches the sitemap XML, parses `<loc>` URLs, fetches each job page and extracts fields |
| `RSS` | `_handle_rss` | Fetches the RSS feed, parses `<item>` elements for title/link/description/pubDate |
| `CUSTOM` | `_handle_custom` | Looks up a custom parser by `company_id` in `_CUSTOM_REGISTRY`; calls its `parse()` method |

- `source_url` is optional — a missing URL triggers a warning and the source is skipped rather than raising.
- All per-source failures (HTTP errors, parse errors, timeouts) are caught and appended to `AdapterResult.warnings`.

---

### Config flags

Set in `apps/agent/.env` (or environment variables):

| Variable | Default | Effect |
|---|---|---|
| `JOB_COLLECTION_USE_FIXTURE` | `true` | Include FixtureAdapter in the pipeline |
| `JOB_COLLECTION_ENABLE_JASOSEOL` | `false` | Include JasoseolAdapter |
| `JOB_COLLECTION_ENABLE_SARAMIN` | `false` | Include SaraminAdapter |
| `JOB_COLLECTION_TIMEOUT_SECONDS` | `10` | Per-request HTTP timeout for real adapters |
| `JASOSEOL_BASE_URL` | `https://jasoseol.com` | Override Jasoseol base URL for testing |
| `SARAMIN_ACCESS_KEY` | _(required)_ | Saramin public API access key |
| `SARAMIN_API_BASE_URL` | `https://oapi.saramin.co.kr` | Override Saramin API base URL for testing |
| `SARAMIN_MAX_QUERIES_PER_COLLECT` | `10` | Max (role × location) query combinations per run |

All real-source flags can be `true` simultaneously — postings from all active adapters are merged and deduplicated.  
If all real-source flags are `false`, `FixtureAdapter` is used as a safe fallback so the pipeline never returns nothing unexpectedly.

### Future adapters (planned)

| Adapter | Phase | Purpose |
|---|---|---|
| `WantedAdapter` | 1st MVP (future) | Wanted.co.kr public API or RSS |
| `company_news_fetcher` | 1.5 MVP | Company news and hiring changes |
| `industry_news_fetcher` | 2nd MVP | Industry/market news (information-only) |

---

## UserBriefingWorkflow

Triggered per user by Spring (`BriefingService.generateBriefing` or `generateScheduledBriefing`). Spring loads the user's preferences and today's `job_postings` from DB, pre-scores candidates, and sends the top 30 as a `candidatePool` in the request. The Agent filters, re-ranks, selects the top 7, enriches them via LLM, and synthesizes a Markdown briefing. Both LLM nodes fall back to deterministic equivalents when `OPENAI_API_KEY` is absent or any LLM call fails — the pipeline never returns HTTP 500.

**The Agent does not call external sources and does not access the database.**

### Responsibilities — who does what

| Step | Owner |
|---|---|
| Load user preferences from `user_briefing_preferences` | Spring |
| Load today's `job_postings` candidate pool from DB | Spring |
| Pre-score candidates against user preferences | Spring (`BriefingService.scorePosting`) |
| Send top 30 pre-scored candidates to Agent | Spring (`AgentClient`) |
| Filter (past-deadline, missing title/URL) | Agent (`filter_job_postings_node`) |
| Re-rank by `preScore + agentScore` | Agent (`rank_job_postings_node`) |
| Select top 7 | Agent (`select_top_items_node`) |
| Enrich postings — LLM batch summary + matching reason (or deterministic fallback) | Agent (`enrich_selected_node`) |
| Synthesize Markdown report — LLM full report + summary line (or deterministic fallback) | Agent (`synthesize_report_node`) |
| Quality check (log-only guardrail) | Agent (`quality_check_node`) |
| Save `briefing_reports` and `briefing_articles` rows | Spring |

### Agent request (Spring → Agent)

```json
{
  "userId": 1,
  "category": "JOB_POSTING",
  "preference": {
    "roles": ["백엔드 개발자", "풀스택 개발자"],
    "companies": ["네이버", "카카오", "라인"],
    "skills": ["Spring Boot", "Java", "Kotlin"],
    "locations": ["서울", "판교"],
    "experienceLevels": ["신입", "3년 이상"],
    "employmentTypes": ["정규직"]
  },
  "briefingDate": "2026-07-01",
  "tone": "easy",
  "candidatePool": {
    "jobPostings": [
      {
        "id": 1,
        "source": "원티드",
        "sourceUrl": "https://www.wanted.co.kr/wd/00001",
        "companyName": "네이버",
        "title": "네이버 백엔드 개발자",
        "position": "백엔드 개발자",
        "employmentType": "정규직",
        "experienceLevel": "신입",
        "location": "서울",
        "deadline": "2026-07-15",
        "skills": ["Spring Boot", "Java"],
        "roles": ["백엔드 개발자"],
        "description": "채용 공고 설명",
        "postedAt": "2026-07-01T09:00:00",
        "collectedDate": "2026-07-01",
        "contentHash": "a3f2...sha256hex...64chars",
        "preScore": 75
      }
    ],
    "companyIssues": [],
    "industryIssues": []
  }
}
```

| Field | Notes |
|---|---|
| `candidatePool.jobPostings` | Top 30 pre-scored candidates from `job_postings` table, selected and scored by Spring |
| `candidatePool.jobPostings[].preScore` | Score assigned by Spring's `BriefingService.scorePosting()` based on user preferences |
| `candidatePool.companyIssues` | Always `[]` in 1st MVP |
| `candidatePool.industryIssues` | Always `[]` in 1st MVP |
| `userId` | Forwarded for logging/tracing only; Agent does not persist it |
| `tone` | Forwarded from the frontend or scheduler; not yet used in LLM prompts |

### Pre-scoring logic (Spring side)

Spring scores each `job_postings` row before sending:

| Match | Score |
|---|---|
| Role or title matches `preference.roles` | +30 |
| Company matches `preference.companies` | +25 |
| Each matching skill (max 5 skills) | +5 each (max +25) |
| Experience level matches | +15 |
| Industry matches (via Company Registry) | +12 |
| Location matches `preference.locations` | +10 |
| Employment type matches | +10 |
| Company size matches (via Company Registry) | +8 |
| Deadline within 7 days | +10 |
| Collected within last 3 days | +5 |

Spring sorts by `preScore` descending, takes the top 30, and sends them as `candidatePool.jobPostings`.

### Agent re-scoring logic

Inside the Agent, each candidate is scored again (`agentScore`) using the same dimensions but different weights. The `totalScore = preScore + agentScore` is used for final ranking:

| Match | Agent score |
|---|---|
| Role match (title or position) | +30 |
| Company match | +15 |
| Each matching skill (max 5 skills) | +5 each (max +25) |
| Location match | +10 |
| Experience level match | +10 |
| Employment type match | +5 |
| Deadline within 7 days | +10 |
| Collected within last 3 days | +5 |

### Graph structure (implemented)

```python
filter_job_postings_node      ← remove past-deadline or missing title/company_name/sourceUrl
      ↓
rank_job_postings_node        ← totalScore = preScore + agentScore; sort desc
      ↓
select_top_items_node         ← top 7  (_TOP_N = 7)
      ↓
enrich_selected_node          ← LLM Call 1: batch enrichment (summary, matchingReason, matchedKeywords)
                                  on failure / no key → enrichments = {}
      ↓
synthesize_report_node        ← merge enrichment + deterministic fallback per posting
                                  if empty selected → _empty_state_report()
                                  LLM Call 2: Markdown report + overallSummary
                                  on failure → _build_deterministic_report()
      ↓
quality_check_node            ← log-only guardrail; never modifies state or raises
```

`enrich_selected_node` and `synthesize_report_node` are async. All other nodes are synchronous and deterministic. `tokenUsage` accumulates Call 1 + Call 2 token counts; it is `{inputTokens: 0, outputTokens: 0}` when LLM is skipped or both calls fail.

### Agent response (Agent → Spring)

```json
{
  "title": "오늘의 채용 브리핑 — 백엔드 개발자 (2026-07-01)",
  "summary": "네이버·카카오·라인 등 3건의 Spring Boot 백엔드 공고를 선별했습니다.",
  "content": "# 오늘의 채용 브리핑\n\n## 오늘의 핵심 요약\n\n...\n\n## 🏆 추천 공고 TOP 3\n\n...\n\n## ⏰ 신규/마감 임박 공고\n\n...\n\n## 💡 오늘의 지원 추천 액션\n\n...\n\n## 🔑 오늘의 키워드\n\n...\n\n## ✏️ 한 줄 정리\n\n...",
  "articles": [
    {
      "title": "네이버 백엔드 개발자",
      "source": "원티드",
      "url": "https://www.wanted.co.kr/wd/00001",
      "summary": "네이버 서버 플랫폼팀에서 Java/Spring Boot 기반 백엔드 개발자를 모집합니다.",
      "whyItMatters": "관심 기업 네이버 · 백엔드 개발자 역할 일치 · Spring Boot, Java 스킬 매칭",
      "publishedAt": "2026-07-01T09:00:00",
      "companyName": "네이버"
    }
  ],
  "tokenUsage": {
    "inputTokens": 320,
    "outputTokens": 850
  }
}
```

| Field | Notes |
|---|---|
| `summary` | LLM 경로: `overallSummary` (한 문장). Fallback: `{date} 기준, {companies}에서 추천 공고 {n}건을 선별했습니다.` |
| `content` | `# 오늘의 채용 브리핑` 헤딩으로 시작. 6개 필수 섹션 포함 Markdown (LLM 경로 또는 템플릿 fallback 모두 동일 구조) |
| `articles[].publishedAt` | 항상 `"{briefingDate}T09:00:00"` — Spring `LocalDateTime.parse()` 호환 |
| `articles[].companyName` | Agent 전용 필드; Spring `AgentBriefingResponse.AgentArticle`에 없으므로 Jackson이 무시 |
| `tokenUsage` | LLM 없으면 `{inputTokens: 0, outputTokens: 0}` |

Spring saves `title`, `summary`, `content`, `tokenUsage` to `briefing_reports`, and each `articles` item to `briefing_articles`.

---

## Request/Response Flow Summary

```
[Daily Collection]
Spring Scheduler / POST /api/admin/collections/daily
    │
    ├─ Spring: aggregate seed keywords from user_briefing_preferences
    ├─ Spring: create collection_jobs row (PENDING → PROCESSING)
    ├─ Spring → Agent: POST /collections/daily (with seedKeywords)
    ├─ Agent: return raw jobPostings (deterministic stub in 1st MVP)
    ├─ Spring: upsert job_postings via CandidatePoolService
    └─ Spring: mark collection_jobs COMPLETED (or FAILED)

[Briefing Generation — per user]
User POST /api/briefings/generate  OR  Spring BriefingScheduler
    │
    ├─ (Scheduler path only) Filter: only process users with briefing_email_enabled = true
    ├─ Spring: load user preferences from user_briefing_preferences
    ├─ Spring: load job_postings for today's date
    ├─ Spring: pre-score candidates; take top 30
    ├─ Spring: create briefing_jobs row (PENDING → PROCESSING)
    ├─ Spring → Agent: POST /briefings/generate (with candidatePool)
    ├─ Agent: filter → re-rank → select top 7 → enrich (LLM/fallback) → synthesize (LLM/fallback) → quality check
    ├─ Spring: save briefing_reports + briefing_articles
    ├─ Spring: mark briefing_jobs COMPLETED (or FAILED)
    └─ (Scheduler path, if EMAIL_AUTO_SEND_ENABLED=true) Spring: send email via EmailDeliveryService; record in delivery_logs
```

The Agent server is called **only by the Spring Boot backend** (`AgentClient`). The frontend never calls the Agent directly. Agent endpoints do not use the `/api` prefix.

---

## Development

### Running Locally

```bash
cd apps/agent
poetry install
poetry run uvicorn app.main:app --reload --port 8000
```

### Testing

```bash
# Collection endpoint tests
poetry run pytest tests/test_collections.py -v

# DailyCollectionService unit tests
poetry run pytest tests/test_daily_collection.py -v

# Adapter tests (FixtureAdapter, JasoseolAdapter — all offline, no real network)
poetry run pytest tests/adapters/ -v

# Normalization and deduplication tests
poetry run pytest tests/test_normalization.py tests/test_deduplication.py -v

# Briefing generation tests
poetry run pytest tests/test_briefings.py tests/test_user_briefing.py -v

# All tests
poetry run pytest

# Lint
poetry run ruff check .
```

### Manual Verification — Daily Collection

#### 1. Fixture mode (default — no network calls)

No `.env` changes needed. Start the agent and send a request directly:

```bash
cd apps/agent
poetry run uvicorn app.main:app --reload --port 8000
```

```bash
curl -s -X POST http://localhost:8000/collections/daily \
  -H "Content-Type: application/json" \
  -d '{
    "collectionJobId": 1,
    "collectDate": "2026-07-02",
    "categories": ["JOB_POSTING"],
    "seedKeywords": {
      "roles": ["백엔드 개발자", "서버 개발자"],
      "companies": ["삼성", "현대", "LG", "SK"],
      "companySizes": ["대기업"],
      "industries": ["IT/인터넷", "반도체"],
      "skills": ["Java", "Spring Boot", "SQL"],
      "locations": ["서울", "경기"],
      "experienceLevels": ["신입", "인턴"],
      "employmentTypes": ["정규직", "인턴"],
      "keywords": []
    },
    "options": {
      "lookbackDays": 7,
      "deadlineWithinDays": 14,
      "discoveryLimitPerSource": 25,
      "detailFetchLimitPerSource": 25,
      "maxResultsPerSource": 25,
      "maxTotalResults": 100
    },
    "companyProfiles": [],
    "officialCompanySources": []
  }' | jq .
```

Expected: `jobPostings` contains 3–5 entries with `"source": "fixture"`. `warnings` is `[]`.

#### 2. Real source mode (JasoseolAdapter — live network)

Add the following to `apps/agent/.env` (create the file if it does not exist):

```
JOB_COLLECTION_USE_FIXTURE=false
JOB_COLLECTION_ENABLE_JASOSEOL=true
```

Restart the agent, then send the same request above.

Expected:
- `jobPostings` contains entries with `"source": "jasoseol"` and real `sourceUrl` values from jasoseol.com.
- `skills`, `roles`, `location`, `experience_level` may be `null`/`[]` — these fields are not reliably available from the page HTML.
- First run may take several seconds (network + concurrent page fetches).
- `warnings` may contain timeout or HTTP error messages for individual pages; these are non-fatal.

#### 3. Both adapters active

```
JOB_COLLECTION_USE_FIXTURE=true
JOB_COLLECTION_ENABLE_JASOSEOL=true
```

Postings from both sources are merged, deduplicated, and returned together.

#### 4. Category gate — non-JOB_POSTING request

```bash
curl -s -X POST http://localhost:8000/collections/daily \
  -H "Content-Type: application/json" \
  -d '{"collectDate": "2026-07-02", "categories": ["COMPANY_NEWS"]}' | jq .
```

Expected: `jobPostings: []`, `stats.jobPostingCount: 0`.

### Local Integration Test (end-to-end with Spring backend)

Requires both backend (`./gradlew bootRun --args='--spring.profiles.active=local'`) and agent (`poetry run uvicorn app.main:app --reload --port 8000`) running.

#### Step 1 — Trigger daily collection via admin endpoint

```bash
curl -s -X POST http://localhost:8080/api/admin/collections/daily \
  -H "Content-Type: application/json" \
  -d '{"collectDate": "2026-07-02", "categories": ["JOB_POSTING"]}' | jq .
```

Spring calls the Agent, receives `jobPostings`, and upserts them into the `job_postings` table.

Expected response from Spring:

```json
{
  "success": true,
  "data": {
    "collectionJobId": 1,
    "status": "COMPLETED",
    "collectDate": "2026-07-02",
    "collectedCount": 3,
    "savedCount": 3,
    "deduplicatedCount": 0,
    "errorMessage": null
  }
}
```

#### Step 2 — Verify job_postings in DB (via backend API)

There is no direct read API for `job_postings` in 1st MVP. Confirm indirectly by proceeding to briefing generation (Step 3) — if the candidate pool is empty, the briefing will have 0 articles.

Alternatively, query the DB directly while running locally:

```sql
SELECT id, source, company_name, title, deadline, content_hash
FROM job_postings
WHERE collected_date = '2026-07-02'
ORDER BY id DESC
LIMIT 20;
```

#### Step 3 — Generate briefing (requires auth cookie)

```bash
curl -s -X POST http://localhost:8080/api/briefings/generate \
  -H "Content-Type: application/json" \
  -H "Cookie: briefy_access_token=<your-jwt>" \
  -d '{"tone": "easy"}' | jq .
```

#### Step 4 — List and view briefing reports

```bash
# List briefing reports
curl -s http://localhost:8080/api/briefings \
  -H "Cookie: briefy_access_token=<your-jwt>" | jq .

# View a specific report
curl -s http://localhost:8080/api/briefings/<reportId> \
  -H "Cookie: briefy_access_token=<your-jwt>" | jq .
```

### Debugging

```bash
export LOG_LEVEL=DEBUG
poetry run uvicorn app.main:app --reload --log-level debug
```

---

## Future: Adding More Sources

1. **Adding more job-board adapters** — implement new `JobBoardAdapter` subclasses (e.g. `WantedAdapter`) in `app/adapters/`. Register them in `DailyCollectionService._build_adapters()` behind a per-source config flag.
2. **Do not add DB access to the Agent** — keep Spring as the single DB owner. Pass data in request bodies.
3. **Do not mix future phases into current code** — create `company_briefing_graph.py` (1.5 MVP) and `industry_briefing_graph.py` (2nd MVP) as new files.

**Phase guidance:**

| File | Phase | Notes |
|---|---|---|
| `daily_collection.py` + `FixtureAdapter` | 1st MVP (current) | Active; fixture mode default |
| `daily_collection.py` + `JasoseolAdapter` | 1st MVP (current) | Active; opt-in via `JOB_COLLECTION_ENABLE_JASOSEOL` |
| `daily_collection.py` + `SaraminAdapter` | 1st MVP (current) | Active; opt-in via `JOB_COLLECTION_ENABLE_SARAMIN` + `SARAMIN_ACCESS_KEY` |
| `daily_collection.py` + `OfficialCompanyAdapter` | 1st MVP (current) | Active when `officialCompanySources` non-empty in request |
| `user_briefing_graph.py` (via `/briefings/generate`) | 1st MVP (current) | Active; LLM enrichment + synthesis with deterministic fallback |
| More real adapters (Wanted, …) | 1st MVP (future) | Add to adapter package |
| `company_briefing_graph.py` | 1.5 MVP | Company news, hiring changes, earnings summaries |
| `industry_briefing_graph.py` | 2nd MVP | IT/AI, semiconductor, platform, finance; information-only |
