# Database Design

## Overview

Briefy uses MySQL 8.0 (AWS RDS in production, Docker container in development).  
The backend accesses the database through Spring Boot JPA with Hibernate.

All table and column names use `snake_case`. All timestamps are stored as `DATETIME` (UTC). String enums are stored as `VARCHAR` and validated at the application layer.

---

## Tables

### `users`

Stores Google OAuth users. Briefy supports only Google sign-in for MVP — there is no password column.

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | BIGINT | PK, AUTO_INCREMENT | |
| `email` | VARCHAR(255) | UNIQUE NOT NULL | From OAuth provider |
| `nickname` | VARCHAR(100) | | Display name |
| `profile_image_url` | VARCHAR(500) | | OAuth profile photo URL |
| `provider` | VARCHAR(30) | NOT NULL | See `AuthProvider` |
| `provider_id` | VARCHAR(255) | NOT NULL | Provider's user ID |
| `role` | VARCHAR(30) | NOT NULL | See `UserRole` |
| `status` | VARCHAR(30) | NOT NULL | See `UserStatus` |
| `onboarding_completed` | BOOLEAN | NOT NULL DEFAULT FALSE | True after first topic selection |
| `created_at` | DATETIME | | |
| `updated_at` | DATETIME | | |

**Enums**

| Enum | Values |
|---|---|
| `AuthProvider` | `GOOGLE` |
| `UserRole` | `USER`, `ADMIN` |
| `UserStatus` | `ACTIVE`, `DELETED` |

**Indexes**

```sql
UNIQUE INDEX uq_users_email            (email)
UNIQUE INDEX uq_users_provider         (provider, provider_id)
INDEX        idx_users_status           (status)
```

**Relationships**

- 1:N → `user_topics`
- 1:N → `briefing_jobs`
- 1:N → `briefing_reports`
- 1:N → `delivery_logs`
- 1:N → `user_feedbacks`
- 1:1 → `notification_settings`

---

### `topics`

Predefined preference categories that users can subscribe to. Seeded at application startup; not user-generated.

The `topics` table is a **generic preference subscription system** — the seed rows and category labels change per MVP phase, but the schema and API contract remain the same.

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | BIGINT | PK, AUTO_INCREMENT | |
| `name` | VARCHAR(100) | NOT NULL | Human-readable label |
| `slug` | VARCHAR(100) | UNIQUE NOT NULL | URL-safe identifier (e.g. `target-role`) |
| `category` | VARCHAR(50) | NOT NULL | Broad grouping |
| `description` | VARCHAR(500) | | Shown on preference selection UI |
| `display_order` | INT | | Controls ordering on UI |
| `is_active` | BOOLEAN | NOT NULL DEFAULT TRUE | Inactive rows are hidden from UI |
| `created_at` | DATETIME | | |
| `updated_at` | DATETIME | | |

**1st MVP seed data — Job Briefing**

| name | slug | category | description |
|---|---|---|---|
| Target Role | `target-role` | JOB_PREFERENCE | 목표 직무 (예: 백엔드 개발자, 풀스택 개발자) |
| Target Companies | `target-companies` | JOB_PREFERENCE | 관심 회사 (예: 네이버, 카카오, 라인) |
| Skills / Competencies | `skills` | JOB_PREFERENCE | 핵심 스킬 (예: Spring Boot, Java, Kotlin) |
| Location | `location` | JOB_PREFERENCE | 희망 근무지 (예: 서울, 판교) |
| Experience Level | `experience-level` | JOB_PREFERENCE | 경력 수준 (예: 신입, 3년 이상) |
| Employment Type | `employment-type` | JOB_PREFERENCE | 고용 형태 (예: 정규직, 계약직) |

**Later phases (not seeded in 1st MVP)**

| Phase | Examples |
|---|---|
| 1.5 MVP — Interested Company Briefing | Target companies for news tracking, hiring trend signals |
| 2nd MVP — Industry / Market Briefing | IT/AI, Semiconductor, Platform, Finance, Content |

**Indexes**

```sql
UNIQUE INDEX uq_topics_slug      (slug)
INDEX        idx_topics_category  (category)
INDEX        idx_topics_active    (is_active)
```

**Relationships**

- 1:N → `user_topics`

---

### `user_topics`

Each row is one topic + keyword pair that a user subscribes to. One user can subscribe to the same topic with multiple different keywords.

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | BIGINT | PK, AUTO_INCREMENT | |
| `user_id` | BIGINT | FK `users.id` NOT NULL | |
| `topic_id` | BIGINT | FK `topics.id` NOT NULL | |
| `keyword` | VARCHAR(100) | NOT NULL | User-entered keyword within the topic |
| `priority` | INT | NOT NULL DEFAULT 1 | Higher value = higher weight in briefing |
| `is_active` | BOOLEAN | NOT NULL DEFAULT TRUE | Soft-delete: set to FALSE instead of hard-delete |
| `created_at` | DATETIME | | |
| `updated_at` | DATETIME | | |

**Indexes**

```sql
INDEX        idx_user_topics_user   (user_id)
INDEX        idx_user_topics_topic  (topic_id)
UNIQUE INDEX uq_user_topic_keyword  (user_id, topic_id, keyword)
```

**Notes**

- Deletion is soft: set `is_active = FALSE`. Do not hard-delete rows so that briefing history remains coherent.
- The `priority` field is reserved for future ranking; use default `1` for MVP.

**Relationships**

- N:1 → `users`
- N:1 → `topics`

---

### `briefing_jobs`

Tracks the lifecycle of a briefing generation request. One job produces at most one report.

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | BIGINT | PK, AUTO_INCREMENT | |
| `user_id` | BIGINT | FK `users.id` NOT NULL | |
| `status` | VARCHAR(30) | NOT NULL | See `BriefingJobStatus` |
| `trigger_type` | VARCHAR(30) | NOT NULL | See `BriefingTriggerType` |
| `scheduled_at` | DATETIME | | When the job was intended to run |
| `started_at` | DATETIME | | When processing began |
| `completed_at` | DATETIME | | When job finished (success or failure) |
| `error_message` | TEXT | | Populated on `FAILED` status |
| `retry_count` | INT | NOT NULL DEFAULT 0 | Number of retry attempts |
| `created_at` | DATETIME | | |
| `updated_at` | DATETIME | | |

**Enums**

| Enum | Values |
|---|---|
| `BriefingJobStatus` | `PENDING`, `PROCESSING`, `COMPLETED`, `FAILED` |
| `BriefingTriggerType` | `SCHEDULED`, `MANUAL` |

**State machine**

```
PENDING → PROCESSING → COMPLETED
                    ↘ FAILED (→ retry → PENDING)
```

**Indexes**

```sql
INDEX idx_briefing_jobs_user            (user_id)
INDEX idx_briefing_jobs_status          (status)
INDEX idx_briefing_jobs_scheduled_at    (scheduled_at)
INDEX idx_briefing_jobs_user_scheduled  (user_id, scheduled_at)
```

**Relationships**

- N:1 → `users`
- 1:1 → `briefing_reports`

---

### `briefing_reports`

Stores the generated briefing output. Linked 1:1 to a completed job. The `content` field holds the full briefing as Markdown.

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | BIGINT | PK, AUTO_INCREMENT | |
| `user_id` | BIGINT | FK `users.id` NOT NULL | Denormalized for query convenience |
| `briefing_job_id` | BIGINT | FK `briefing_jobs.id` UNIQUE NOT NULL | 1:1 relationship enforced here |
| `title` | VARCHAR(255) | NOT NULL | Generated title |
| `summary` | VARCHAR(1000) | | Short teaser (used in email subject/preview) |
| `content` | MEDIUMTEXT | NOT NULL | **Full briefing in Markdown format** |
| `report_date` | DATE | NOT NULL | The date the briefing covers |
| `tone` | VARCHAR(30) | | Tone used for LLM generation (e.g. `PROFESSIONAL`) |
| `article_count` | INT | | Number of source articles processed |
| `token_input` | INT | | LLM input tokens used |
| `token_output` | INT | | LLM output tokens used |
| `created_at` | DATETIME | | |
| `updated_at` | DATETIME | | |

**Notes**

- `content` is Markdown for MVP. Future versions may switch to a structured JSON format for richer rendering.
- `token_input` / `token_output` are recorded for cost tracking and are visible to `ADMIN` users.
- `user_id` is denormalized (also accessible via `briefing_job_id → briefing_jobs.user_id`) for efficient single-table queries on the user's briefing history.

**Indexes**

```sql
UNIQUE INDEX uq_briefing_reports_job       (briefing_job_id)
INDEX        idx_briefing_reports_user      (user_id)
INDEX        idx_briefing_reports_date      (report_date)
INDEX        idx_briefing_reports_user_date (user_id, report_date)
```

**Relationships**

- N:1 → `users`
- 1:1 → `briefing_jobs`
- 1:N → `briefing_articles`
- 1:N → `delivery_logs`
- 1:N → `user_feedbacks`

---

### `briefing_articles`

Individual source articles included in a briefing report. Optional for a minimal MVP but recommended because it enables per-article feedback, source attribution, and future deduplication caching.

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | BIGINT | PK, AUTO_INCREMENT | |
| `briefing_report_id` | BIGINT | FK `briefing_reports.id` NOT NULL | |
| `title` | VARCHAR(500) | NOT NULL | Article headline |
| `source` | VARCHAR(255) | | Publisher / domain name |
| `url` | VARCHAR(1000) | | Original article URL |
| `summary` | TEXT | | Agent-generated one-paragraph summary |
| `why_it_matters` | TEXT | | Agent-generated relevance explanation |
| `published_at` | DATETIME | | Original publication time |
| `display_order` | INT | | Order within the report |
| `created_at` | DATETIME | | |

**Indexes**

```sql
INDEX idx_briefing_articles_report (briefing_report_id)
INDEX idx_briefing_articles_url    (url)
```

**Notes**

- `url` index enables deduplication across briefings in a future caching layer.
- No `updated_at`: article rows are write-once after the briefing job completes.

**Relationships**

- N:1 → `briefing_reports`

---

### `delivery_logs`

Records every email delivery attempt and its outcome. One report may have multiple log entries (e.g. initial attempt + retry).

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | BIGINT | PK, AUTO_INCREMENT | |
| `briefing_report_id` | BIGINT | FK `briefing_reports.id` NOT NULL | |
| `user_id` | BIGINT | FK `users.id` NOT NULL | Denormalized for query convenience |
| `channel` | VARCHAR(30) | NOT NULL | See `DeliveryChannel` |
| `status` | VARCHAR(30) | NOT NULL | See `DeliveryStatus` |
| `recipient` | VARCHAR(255) | NOT NULL | Email address (or channel-specific handle) |
| `sent_at` | DATETIME | | Populated on successful send |
| `error_message` | TEXT | | Populated on `FAILED` status |
| `created_at` | DATETIME | | |
| `updated_at` | DATETIME | | |

**Enums**

| Enum | Values | MVP? |
|---|---|---|
| `DeliveryChannel` | `EMAIL` | MVP |
| | `KAKAO`, `SLACK` | Future |
| `DeliveryStatus` | `PENDING`, `SENT`, `FAILED` | |

**Indexes**

```sql
INDEX idx_delivery_logs_report (briefing_report_id)
INDEX idx_delivery_logs_user   (user_id)
INDEX idx_delivery_logs_status (status)
```

**Notes**

- Only `EMAIL` is implemented for MVP. `KAKAO` and `SLACK` enum values are reserved; do not implement them yet.
- `user_id` is denormalized from the report for fast per-user delivery history queries.

**Relationships**

- N:1 → `briefing_reports`
- N:1 → `users`

---

### `user_feedbacks`

Records a user's reaction to a briefing report. MVP stores the data only; future versions will use it to personalize topic weighting.

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | BIGINT | PK, AUTO_INCREMENT | |
| `user_id` | BIGINT | FK `users.id` NOT NULL | |
| `briefing_report_id` | BIGINT | FK `briefing_reports.id` NOT NULL | |
| `feedback_type` | VARCHAR(50) | NOT NULL | See `FeedbackType` |
| `comment` | VARCHAR(1000) | | Optional free-text comment |
| `created_at` | DATETIME | | |

**Enums**

| Enum | Values |
|---|---|
| `FeedbackType` | `USEFUL`, `NOT_USEFUL`, `WANT_MORE`, `LESS_LIKE_THIS` |

**Indexes**

```sql
INDEX idx_user_feedbacks_user   (user_id)
INDEX idx_user_feedbacks_report (briefing_report_id)
```

**Notes**

- No `updated_at`: feedback rows are write-once.
- A user can submit multiple feedback entries per report (e.g. one per article impression) — uniqueness is not enforced at the DB level for MVP.
- Future: aggregate `USEFUL`/`NOT_USEFUL` ratios per topic to adjust `user_topics.priority` automatically.

**Relationships**

- N:1 → `users`
- N:1 → `briefing_reports`

---

### `notification_settings`

One row per user. Stores the user's preferred delivery time and channel toggles. Optional for a minimal MVP but useful for the settings dashboard and future scheduled delivery.

| Column | Type | Constraints | Notes |
|---|---|---|---|
| `id` | BIGINT | PK, AUTO_INCREMENT | |
| `user_id` | BIGINT | FK `users.id` UNIQUE NOT NULL | 1:1 with users |
| `email_enabled` | BOOLEAN | NOT NULL DEFAULT TRUE | |
| `delivery_time` | TIME | | Preferred local time for daily briefing |
| `timezone` | VARCHAR(50) | DEFAULT `'Asia/Seoul'` | IANA timezone identifier |
| `created_at` | DATETIME | | |
| `updated_at` | DATETIME | | |

**Indexes**

```sql
UNIQUE INDEX uq_notification_settings_user (user_id)
```

**Notes**

- Created automatically (with defaults) when a user completes onboarding.
- `delivery_time` + `timezone` together determine when the scheduler enqueues a `briefing_job` for this user.
- Future channels (Kakao, Slack) will add `kakao_enabled`, `slack_enabled` columns rather than a new table.

**Relationships**

- 1:1 → `users`

---

## Entity Relationship Summary

```
users ──────────────────────────────────────────────────────────────────────┐
  │ 1:N user_topics (topic subscriptions + keywords)                        │
  │   N:1 topics                                                            │
  │                                                                         │
  │ 1:N briefing_jobs (generation lifecycle)                                │
  │       1:1 briefing_reports (Markdown content)                          │
  │             1:N briefing_articles (source articles)                     │
  │             1:N delivery_logs (email send attempts)  ←── also N:1 users│
  │             1:N user_feedbacks (reactions)           ←── also N:1 users│
  │                                                                         │
  │ 1:1 notification_settings (delivery preferences)                        │
  └────────────────────────────────────────────────────────────────────────┘
```

---

## Spring Profile / DDL Strategy

| Profile | `ddl-auto` | Use |
|---|---|---|
| `local` | `create-drop` | Schema is recreated on each boot. Safe for development. |
| `dev` | `update` | Schema is migrated in place. Use for shared dev environment. |
| `prod` | `validate` | Hibernate validates schema against entities but makes no changes. Use Flyway or Liquibase for migrations. |

> For production schema migrations, use a migration tool (Flyway recommended). Do not rely on `ddl-auto: update` in production.

---

## Notes on Content Format

- **`briefing_reports.content`** is stored as Markdown for MVP. The frontend renders it with a Markdown parser (e.g. `react-markdown`). Future versions may switch to a structured JSON format (sections, bullets, article cards) for richer client-side rendering.
- **`briefing_articles.summary`** and **`.why_it_matters`** are plain text generated by the agent. No special formatting is assumed.

---

## Alignment with docs/api.md

`docs/api.md` was fully rewritten on 2026-06-05 to match this schema. The following previously-noted conflicts have been resolved:

| Resolved conflict | Resolution in api.md |
|---|---|
| Old `POST /api/auth/register` + `POST /api/auth/login` used email/password (no `password_hash` column in DB) | Removed. Auth is Google OAuth only: `GET /api/oauth2/authorize/google` → `GET /api/oauth2/callback/google` |
| Old `GET /api/preferences` returned `newsLanguages`, `briefingTime`, `lengthPreference` (no matching columns) | Removed. Topic subscriptions are managed via `GET/POST /api/me/topics`; delivery time via `notification_settings` |
| Old briefing response included a stored `categories` array | Removed. Categories are derived at query time from `user_topics → topics.category`, not persisted on the report |

No known inconsistencies remain between this document and `docs/api.md`.
