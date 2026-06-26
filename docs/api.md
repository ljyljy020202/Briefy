# Backend API Reference

This document is the source of truth for all backend REST endpoints and the Agent server contract.  
Treat `docs/database.md` as the companion reference for the underlying schema.

---

## Contents

1. [Overview](#overview)
2. [Common Response Format](#common-response-format)
3. [Error Codes](#error-codes)
4. [Auth API](#auth-api)
5. [User API](#user-api)
6. [Topic API](#topic-api)
7. [My Topic Subscription API](#my-topic-subscription-api)
8. [Dashboard API](#dashboard-api)
9. [Briefing API](#briefing-api)
10. [Feedback API](#feedback-api)
11. [Admin API](#admin-api)
12. [Agent Server API Contract](#agent-server-api-contract)
13. [Frontend Integration Notes](#frontend-integration-notes)
14. [MVP Implementation Order](#mvp-implementation-order)

---

## Overview

| Item | Value |
|---|---|
| Backend base URL (local) | `http://localhost:8080` |
| Backend API prefix | `/api` |
| Agent base URL (local) | `http://localhost:8000` |
| Agent API prefix | _(none — no `/api` prefix)_ |
| Auth mechanism | Google OAuth 2.0 Authorization Code Flow + JWT HttpOnly Cookie |
| MVP delivery channel | Email |
| Report content format | Markdown |

**Calling convention**

- All backend endpoints are prefixed with `/api`.
- Agent endpoints are **not** prefixed with `/api` and are **never** called directly by the frontend.
- All backend responses follow the [common response wrapper](#common-response-format).
- Authenticated endpoints require a valid JWT. The JWT is issued by the backend as an HttpOnly cookie — the frontend never reads or stores it directly.
- Current-user endpoints (`/me`) derive the authenticated user from the JWT cookie; never pass a `userId` in the request body.

---

## Common Response Format

Every backend endpoint returns this JSON envelope.

### Success (with body)

```json
{
  "success": true,
  "data": { },
  "error": null
}
```

### Success (no body)

```json
{
  "success": true,
  "data": null,
  "error": null
}
```

### Failure

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "ERROR_CODE",
    "message": "Human readable error message"
  }
}
```

The HTTP status code always reflects the outcome:
- `200 OK` for success
- `201 Created` where a resource is created
- `400 Bad Request` for validation errors
- `401 Unauthorized` for missing or invalid JWT
- `403 Forbidden` for insufficient role or wrong owner
- `404 Not Found` for missing resources
- `500 Internal Server Error` for unexpected failures

---

## Error Codes

| Code | HTTP Status | Description |
|---|---|---|
| `UNAUTHORIZED` | 401 | JWT cookie is missing or expired |
| `FORBIDDEN` | 403 | Authenticated but not authorized (wrong role or not the owner) |
| `VALIDATION_ERROR` | 400 | Request body or query param failed validation |
| `USER_NOT_FOUND` | 404 | Target user does not exist |
| `TOPIC_NOT_FOUND` | 404 | Referenced topic does not exist or is inactive |
| `USER_TOPIC_NOT_FOUND` | 404 | The subscription row does not exist or is not owned by the caller |
| `DUPLICATE_USER_TOPIC` | 409 | The same `(userId, topicId, keyword)` combination already exists and is active |
| `BRIEFING_REPORT_NOT_FOUND` | 404 | Briefing report does not exist or is not owned by the caller |
| `BRIEFING_JOB_FAILED` | 500 | Briefing generation job ended in `FAILED` status |
| `AGENT_SERVER_ERROR` | 502 | The Agent server returned an error or was unreachable |
| `DELIVERY_FAILED` | 500 | Email delivery attempt failed |
| `INTERNAL_SERVER_ERROR` | 500 | Unhandled server-side error |

---

## Auth API

### 1-1. Start Google OAuth Login

```
GET /api/oauth2/authorize/google
```

**Auth:** Public

**Description:** Redirects the browser to the Google OAuth consent page. The frontend navigates to this URL when the user clicks the Google login button.

**Response:**

```
302 Redirect → Google OAuth consent page
```

---

### 1-2. Google OAuth Callback

```
GET /api/oauth2/callback/google?code={code}
```

**Auth:** Public

**Description:**
Handles the redirect from Google after the user grants consent. The backend:
1. Exchanges the `code` for a Google access token
2. Fetches Google user info (email, name, profile image)
3. Finds the existing user by `(provider=GOOGLE, providerId)` or creates a new one
4. Issues a JWT as an HttpOnly cookie
5. Redirects the browser to the appropriate frontend page

**Cookie set:**

| Attribute | Value |
|---|---|
| Name | `briefy_access_token` |
| HttpOnly | `true` |
| Secure | `true` in production, `false` in local |
| SameSite | `Lax` (same domain) or `None` (cross-domain frontend/backend) |
| Path | `/` |

**Redirect:**

| Condition | Destination |
|---|---|
| `onboardingCompleted = false` | `{FRONTEND_URL}/onboarding` |
| `onboardingCompleted = true` | `{FRONTEND_URL}/dashboard` |

**Response:**

```
302 or 303 Redirect (with Set-Cookie header)
```

---

### 1-3. Logout

```
POST /api/auth/logout
```

**Auth:** Required

**Description:** Expires the JWT cookie by setting `Max-Age=0`.

**Response:**

```json
{
  "success": true,
  "data": null,
  "error": null
}
```

---

## User API

### 2-1. Get Current User

```
GET /api/users/me
```

**Auth:** Required

**Description:** Returns the authenticated user's profile. The frontend calls this on app load to determine login state and onboarding status.

**Response:**

```json
{
  "success": true,
  "data": {
    "id": 1,
    "email": "user@gmail.com",
    "nickname": "Jiye",
    "profileImageUrl": "https://lh3.googleusercontent.com/...",
    "role": "USER",
    "onboardingCompleted": true
  },
  "error": null
}
```

**Possible errors:** `UNAUTHORIZED`

---

### 2-2. Complete or Update Onboarding

```
PATCH /api/users/me/onboarding
```

**Auth:** Required

**Description:** Marks onboarding as completed and optionally sets a display nickname. Call this after the user finishes selecting topics and keywords on the onboarding screen. Setting `onboardingCompleted = true` is permanent in MVP.

**Request:**

```json
{
  "nickname": "Jiye"
}
```

| Field | Type | Required | Notes |
|---|---|---|---|
| `nickname` | String | No | Max 100 chars |

**Response:**

```json
{
  "success": true,
  "data": {
    "onboardingCompleted": true
  },
  "error": null
}
```

**Possible errors:** `UNAUTHORIZED`, `VALIDATION_ERROR`

---

## Topic API

### 3-1. Get All Active Topics

```
GET /api/topics
```

**Auth:** Required

**Description:** Returns all topics where `is_active = true`, ordered by `displayOrder`. Used to populate the topic selection UI during onboarding and in the settings screen.

**Response:**

```json
{
  "success": true,
  "data": [
    {
      "id": 1,
      "name": "Target Role",
      "slug": "target-role",
      "category": "JOB_PREFERENCE",
      "description": "목표 직무 (예: 백엔드 개발자, 풀스택 개발자)",
      "displayOrder": 1
    },
    {
      "id": 2,
      "name": "Target Companies",
      "slug": "target-companies",
      "category": "JOB_PREFERENCE",
      "description": "관심 회사 (예: 네이버, 카카오, 라인)",
      "displayOrder": 2
    },
    {
      "id": 3,
      "name": "Skills / Competencies",
      "slug": "skills",
      "category": "JOB_PREFERENCE",
      "description": "핵심 스킬 (예: Spring Boot, Java, Kotlin)",
      "displayOrder": 3
    }
  ],
  "error": null
}
```

**1st MVP preference categories (Job Briefing):** Target Role · Target Companies · Skills / Competencies · Location · Experience Level · Employment Type

**Later phases:** Interested Company Briefing (1.5 MVP) · Industry / Market Briefing (2nd MVP)

> The `Topic` + `UserTopic` tables are a generic preference subscription system. The seed rows and category labels change per MVP phase, but the API contract stays the same.

**Possible errors:** `UNAUTHORIZED`

---

## My Topic Subscription API

A subscription (`user_topics` row) is one `(topic, keyword)` pair. A user can subscribe to the same topic with multiple keywords.

### 4-1. Get Current User's Topic Subscriptions

```
GET /api/me/topics
```

**Auth:** Required

**Description:** Returns all active subscriptions for the authenticated user.

**Response:**

```json
{
  "success": true,
  "data": [
    {
      "id": 10,
      "topicId": 1,
      "topicName": "Target Role",
      "keyword": "백엔드 개발자",
      "priority": 1,
      "isActive": true
    },
    {
      "id": 11,
      "topicId": 3,
      "topicName": "Skills / Competencies",
      "keyword": "Spring Boot",
      "priority": 1,
      "isActive": true
    }
  ],
  "error": null
}
```

**Possible errors:** `UNAUTHORIZED`

---

### 4-2. Add One Topic Keyword Subscription

```
POST /api/me/topics
```

**Auth:** Required

**Request:**

```json
{
  "topicId": 1,
  "keyword": "백엔드 개발자",
  "priority": 1
}
```

| Field | Type | Required | Notes |
|---|---|---|---|
| `topicId` | Long | Yes | Must reference an active topic |
| `keyword` | String | Yes | Max 100 chars |
| `priority` | Integer | No | Default `1`; higher = more weight |

**Response:**

```json
{
  "success": true,
  "data": {
    "id": 10,
    "topicId": 1,
    "topicName": "Target Role",
    "keyword": "백엔드 개발자",
    "priority": 1,
    "isActive": true
  },
  "error": null
}
```

**HTTP Status:** `201 Created`

**Possible errors:** `UNAUTHORIZED`, `VALIDATION_ERROR`, `TOPIC_NOT_FOUND`, `DUPLICATE_USER_TOPIC`

---

### 4-3. Add Multiple Topic Keyword Subscriptions (Bulk)

```
POST /api/me/topics/bulk
```

**Auth:** Required

**Description:** Adds multiple topic + keyword pairs in one request. Designed for the onboarding flow. Already-active duplicates are **silently skipped** (not rejected). `createdCount` reflects only newly created rows.

**Request:**

```json
{
  "topics": [
    {
      "topicId": 1,
      "keywords": ["백엔드 개발자", "풀스택 개발자"]
    },
    {
      "topicId": 2,
      "keywords": ["네이버", "카카오", "라인"]
    },
    {
      "topicId": 3,
      "keywords": ["Spring Boot", "Java", "Kotlin"]
    }
  ]
}
```

| Field | Type | Required | Notes |
|---|---|---|---|
| `topics` | Array | Yes | 1–20 topic entries |
| `topics[].topicId` | Long | Yes | Must reference an active topic |
| `topics[].keywords` | Array\<String\> | Yes | 1–10 keywords per topic |

**Response:**

```json
{
  "success": true,
  "data": {
    "createdCount": 5
  },
  "error": null
}
```

`createdCount` is the number of rows **newly inserted** (skipped duplicates are not counted).

**Possible errors:** `UNAUTHORIZED`, `VALIDATION_ERROR`, `TOPIC_NOT_FOUND`

---

### 4-4. Delete a Topic Keyword Subscription

```
DELETE /api/me/topics/{userTopicId}
```

**Auth:** Required

**Description:** Soft-deletes the subscription by setting `isActive = false`. The row is not removed from the database so that briefing history remains coherent.

**Path param:** `userTopicId` — the `id` from the `user_topics` table

**Response:**

```json
{
  "success": true,
  "data": null,
  "error": null
}
```

**Possible errors:** `UNAUTHORIZED`, `USER_TOPIC_NOT_FOUND`, `FORBIDDEN`

---

## Dashboard API

### 5-1. Get Dashboard Summary

```
GET /api/dashboard
```

**Auth:** Required

**Description:** Returns a combined summary for the dashboard UI in a single request. Aggregates user info, active topic subscriptions (grouped by topic), the next scheduled delivery time, the latest briefing report preview, and the latest delivery status.

**Response:**

```json
{
  "success": true,
  "data": {
    "user": {
      "nickname": "Jiye",
      "email": "user@gmail.com",
      "onboardingCompleted": true
    },
    "subscribedTopics": [
      {
        "topicName": "Target Role",
        "keywords": ["백엔드 개발자", "풀스택 개발자"]
      },
      {
        "topicName": "Target Companies",
        "keywords": ["네이버", "카카오", "라인"]
      },
      {
        "topicName": "Skills / Competencies",
        "keywords": ["Spring Boot", "Java", "Kotlin"]
      }
    ],
    "nextDeliveryTime": "2026-06-06T08:00:00",
    "latestBriefing": {
      "id": 100,
      "title": "오늘의 채용 브리핑",
      "reportDate": "2026-06-05"
    },
    "latestDeliveryStatus": "SENT"
  },
  "error": null
}
```

| Field | Notes |
|---|---|
| `subscribedTopics` | Active subscriptions only, grouped by topic name |
| `nextDeliveryTime` | Derived from `notification_settings.delivery_time` + `timezone`; `null` if not configured |
| `latestBriefing` | The most recent `briefing_reports` row for this user; `null` if none |
| `latestDeliveryStatus` | `SENT`, `PENDING`, `FAILED`, or `null` if no delivery attempt yet |

**Possible errors:** `UNAUTHORIZED`

---

## Briefing API

### 6-1. Manually Generate Briefing

```
POST /api/briefings/generate
```

**Auth:** Required

**Description:** Triggers an immediate briefing generation. The backend creates a `briefing_jobs` row, calls the Agent server, persists the result, and returns the report reference. This call is **synchronous** in MVP — the HTTP response is returned only after generation is complete.

**Request:**

```json
{
  "tone": "easy"
}
```

| Field | Type | Required | Notes |
|---|---|---|---|
| `tone` | String | No | Passed to Agent as-is. Example values: `"easy"`, `"professional"`. Default: `"easy"` |

**Processing flow:**

```
1. Load authenticated user
2. Load active user_topics for the user
3. Create briefing_jobs row (status = PENDING, triggerType = MANUAL)
4. Update job status → PROCESSING, set startedAt
5. Call Agent: POST /briefings/generate
6. On success:
   a. Insert briefing_reports row
   b. Insert briefing_articles rows
   c. Update job status → COMPLETED, set completedAt
7. On failure:
   a. Update job status → FAILED, set errorMessage
   b. Return BRIEFING_JOB_FAILED or AGENT_SERVER_ERROR
```

**Response:**

```json
{
  "success": true,
  "data": {
    "briefingReportId": 100,
    "jobId": 50,
    "status": "COMPLETED"
  },
  "error": null
}
```

**HTTP Status:** `201 Created`

**Possible errors:** `UNAUTHORIZED`, `VALIDATION_ERROR`, `AGENT_SERVER_ERROR`, `BRIEFING_JOB_FAILED`

---

### 6-2. Get Briefing Report List

```
GET /api/briefings?page=0&size=10
```

**Auth:** Required

**Description:** Returns the authenticated user's briefing history with pagination. Does not include full `content`; use [6-3](#6-3-get-briefing-report-detail) for the full report.

**Query params:**

| Param | Type | Default | Notes |
|---|---|---|---|
| `page` | Integer | `0` | 0-indexed page number |
| `size` | Integer | `10` | Items per page; max `50` |

**Response:**

```json
{
  "success": true,
  "data": {
    "content": [
      {
        "id": 100,
        "title": "오늘의 채용 브리핑 — 백엔드 개발자",
        "summary": "오늘 신규 공고 3건, 마감 임박 공고 2건이 선호도와 매칭됐습니다.",
        "reportDate": "2026-06-05",
        "articleCount": 5,
        "createdAt": "2026-06-05T08:00:00"
      }
    ],
    "page": 0,
    "size": 10,
    "totalElements": 1,
    "totalPages": 1
  },
  "error": null
}
```

**Possible errors:** `UNAUTHORIZED`, `VALIDATION_ERROR`

---

### 6-3. Get Briefing Report Detail

```
GET /api/briefings/{id}
```

**Auth:** Required

**Description:** Returns the full briefing report including Markdown `content` and all source articles. Users can only access their own reports.

**Path param:** `id` — `briefing_reports.id`

**Response:**

```json
{
  "success": true,
  "data": {
    "id": 100,
    "title": "오늘의 AI/백엔드 브리핑",
    "summary": "오늘은 OpenAI, Claude Code, Spring 관련 업데이트가 주요 이슈였습니다.",
    "content": "## 오늘의 핵심 요약\n\n...",
    "reportDate": "2026-06-05",
    "articles": [
      {
        "title": "Example Article",
        "source": "OpenAI Blog",
        "url": "https://example.com",
        "summary": "One-paragraph summary of the article.",
        "whyItMatters": "Explanation of relevance to the user's topics.",
        "publishedAt": "2026-06-05T00:00:00"
      }
    ]
  },
  "error": null
}
```

**Notes:**
- `content` is Markdown. The frontend must render it with a sanitized Markdown renderer.
- `articles` may be empty if the Agent returned no structured articles.

**Possible errors:** `UNAUTHORIZED`, `BRIEFING_REPORT_NOT_FOUND`, `FORBIDDEN`

---

## Feedback API

### 7-1. Create Feedback for a Briefing Report

```
POST /api/briefings/{id}/feedback
```

**Auth:** Required

**Description:** Records a user's reaction to a briefing report. Users can only submit feedback on their own reports. Multiple feedback entries per report are allowed in MVP (e.g. re-rating is permitted).

**Path param:** `id` — `briefing_reports.id`

**Request:**

```json
{
  "feedbackType": "USEFUL",
  "comment": "AI 뉴스가 특히 좋았어요."
}
```

| Field | Type | Required | Notes |
|---|---|---|---|
| `feedbackType` | String | Yes | See values below |
| `comment` | String | No | Max 1000 chars |

**`feedbackType` values:**

| Value | Meaning |
|---|---|
| `USEFUL` | The briefing was helpful |
| `NOT_USEFUL` | The briefing was not helpful |
| `WANT_MORE` | User wants more content like this |
| `LESS_LIKE_THIS` | User wants less content like this |

**Response:**

```json
{
  "success": true,
  "data": {
    "id": 1,
    "feedbackType": "USEFUL",
    "comment": "AI 뉴스가 특히 좋았어요."
  },
  "error": null
}
```

**HTTP Status:** `201 Created`

**Notes:** MVP stores feedback only. Future personalization (adjusting `user_topics.priority` based on feedback) is out of scope.

**Possible errors:** `UNAUTHORIZED`, `VALIDATION_ERROR`, `BRIEFING_REPORT_NOT_FOUND`, `FORBIDDEN`

---

## Admin API

All admin endpoints require the authenticated user to have `role = ADMIN`. Non-admin users receive `403 FORBIDDEN`.

### 8-1. Get Briefing Jobs (Admin)

```
GET /api/admin/briefing-jobs?status=FAILED&page=0&size=20
```

**Auth:** Required, ADMIN only

**Query params:**

| Param | Type | Default | Notes |
|---|---|---|---|
| `status` | String | _(all)_ | Filter by `BriefingJobStatus`: `PENDING`, `PROCESSING`, `COMPLETED`, `FAILED` |
| `page` | Integer | `0` | |
| `size` | Integer | `20` | Max `100` |

**Response:**

```json
{
  "success": true,
  "data": {
    "content": [
      {
        "id": 50,
        "userId": 1,
        "status": "FAILED",
        "triggerType": "SCHEDULED",
        "scheduledAt": "2026-06-05T08:00:00",
        "startedAt": "2026-06-05T08:00:01",
        "completedAt": null,
        "errorMessage": "Agent server timeout",
        "retryCount": 1,
        "createdAt": "2026-06-05T08:00:00"
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1
  },
  "error": null
}
```

**Possible errors:** `UNAUTHORIZED`, `FORBIDDEN`, `VALIDATION_ERROR`

---

### 8-2. Get Delivery Logs (Admin)

```
GET /api/admin/delivery-logs?status=FAILED&page=0&size=20
```

**Auth:** Required, ADMIN only

**Query params:**

| Param | Type | Default | Notes |
|---|---|---|---|
| `status` | String | _(all)_ | Filter by `DeliveryStatus`: `PENDING`, `SENT`, `FAILED` |
| `page` | Integer | `0` | |
| `size` | Integer | `20` | Max `100` |

**Response:**

```json
{
  "success": true,
  "data": {
    "content": [
      {
        "id": 70,
        "briefingReportId": 100,
        "userId": 1,
        "channel": "EMAIL",
        "status": "FAILED",
        "recipient": "user@gmail.com",
        "sentAt": null,
        "errorMessage": "Email delivery failed",
        "createdAt": "2026-06-05T08:01:00"
      }
    ],
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1
  },
  "error": null
}
```

**Possible errors:** `UNAUTHORIZED`, `FORBIDDEN`, `VALIDATION_ERROR`

---

## Agent Server API Contract

The Agent server is an internal FastAPI service. It is **called only by the Spring Boot backend** and must not be directly accessible to the public internet or the frontend.

Agent endpoints do **not** use the `/api` prefix.

---

### 9-1. Health Check

```
GET /health
```

**Auth:** None (internal network only)

**Response:**

```json
{
  "status": "ok"
}
```

---

### 9-2. Generate Briefing

```
POST /briefings/generate
```

**Called by:** Spring Boot `AgentClient` (WebClient)

**Auth:** None (internal network only; restrict via Docker network or security group in production)

**Request:**

```json
{
  "userId": 1,
  "topics": [
    {
      "name": "Target Role",
      "keywords": ["백엔드 개발자", "풀스택 개발자"]
    },
    {
      "name": "Target Companies",
      "keywords": ["네이버", "카카오", "라인"]
    },
    {
      "name": "Skills / Competencies",
      "keywords": ["Spring Boot", "Java", "Kotlin"]
    },
    {
      "name": "Location",
      "keywords": ["서울", "판교"]
    },
    {
      "name": "Experience Level",
      "keywords": ["신입", "3년 이상"]
    }
  ],
  "date": "2026-06-05",
  "tone": "easy"
}
```

| Field | Type | Notes |
|---|---|---|
| `userId` | Long | For logging and tracing only; Agent does not persist it |
| `topics` | Array | Active topic + keyword subscriptions for this user |
| `topics[].name` | String | Topic display name |
| `topics[].keywords` | Array\<String\> | User-entered keywords for this topic |
| `date` | String | ISO-8601 date (`YYYY-MM-DD`); the briefing covers this day |
| `tone` | String | Passed through from the frontend or scheduler |

**Response:**

```json
{
  "title": "오늘의 채용 브리핑 — 백엔드 개발자",
  "summary": "오늘 네이버·카카오·라인에서 백엔드 포지션 3건이 신규 등록됐고, 마감 임박 공고 2건이 있습니다.",
  "content": "## 오늘의 핵심 요약\n\n...",
  "articles": [
    {
      "title": "네이버 — 백엔드 개발자 (Spring Boot) 채용",
      "source": "채용 플랫폼",
      "url": "https://example.com/job/123",
      "summary": "네이버 서치 플랫폼팀에서 Spring Boot · Java 경력 3년 이상 백엔드 개발자를 모집합니다.",
      "whyItMatters": "목표 회사(네이버)이며 핵심 스킬(Spring Boot, Java)과 정확히 매칭됩니다.",
      "publishedAt": "2026-06-05T00:00:00"
    }
  ],
  "tokenUsage": {
    "inputTokens": 8000,
    "outputTokens": 1500
  }
}
```

| Field | Notes |
|---|---|
| `content` | Full briefing in **Markdown** |
| `articles` | May be an empty array if no articles were found |
| `tokenUsage` | Stored in `briefing_reports.token_input` / `token_output` for cost tracking |

**Error handling (backend side):** If the Agent returns a non-2xx status or is unreachable, the backend marks the job as `FAILED` with `errorMessage` and returns `AGENT_SERVER_ERROR` to the caller.

---

## Frontend Integration Notes

### Environment variable

Set in `.env.local`:

```
NEXT_PUBLIC_API_BASE_URL=http://localhost:8080
```

Never hardcode the backend URL in component or page files. All backend calls go through `src/lib/api.ts`.

### OAuth login

The login button must navigate the browser (full page redirect) to:

```
${NEXT_PUBLIC_API_BASE_URL}/api/oauth2/authorize/google
```

Do not use `fetch` or `XMLHttpRequest` for this redirect. The browser must follow the redirect so Google can set its own cookies.

### Authenticated fetch calls

Every backend `fetch` call must include:

```ts
fetch(`${process.env.NEXT_PUBLIC_API_BASE_URL}/api/...`, {
  credentials: "include",
});
```

Without `credentials: "include"` the browser will not send the JWT cookie.

### Login state detection

On app load (e.g. in the root layout or a `useAuth` hook), call:

```
GET /api/users/me
```

- If the response is `200`, the user is authenticated. Use the returned data to populate user context.
- If the response is `401 UNAUTHORIZED`, the user is not logged in. Redirect to the landing page.

Do **not** store the JWT or any user session data in `localStorage` or `sessionStorage`.

### Rendering report content

`briefing_reports.content` is Markdown. Render it with a sanitized Markdown parser (e.g. `react-markdown` with `rehype-sanitize`). Do not use `dangerouslySetInnerHTML` with unsanitized content.

---

## MVP Implementation Order

Suggested order for implementing backend features. Each step produces runnable, testable code before the next begins.

| Step | Scope |
|---|---|
| 1 | `ApiResponse<T>` wrapper class + global exception handler + all error codes |
| 2 | `User` entity, `UserRepository`, `UserService` (find or create by provider) |
| 3 | `Topic` entity + seed data, `UserTopic` entity; Topic API and My Topic Subscription API |
| 4 | Google OAuth security config, callback handler, JWT issue + cookie, `GET /api/users/me`, `PATCH /api/users/me/onboarding` |
| 5 | Frontend: landing page → OAuth redirect → onboarding → dashboard skeleton (wired to `/api/users/me` and topic APIs) |
| 6 | Agent server stub: `POST /briefings/generate` returns a hardcoded Markdown response |
| 7 | Spring `AgentClient` (WebClient): calls Agent stub, maps response to internal DTOs |
| 8 | `BriefingJob`, `BriefingReport`, `BriefingArticle` entities; `POST /api/briefings/generate`, `GET /api/briefings`, `GET /api/briefings/{id}` |
| 9 | Feedback API: `UserFeedback` entity, `POST /api/briefings/{id}/feedback` |
| 10 | `DeliveryLog` entity, `FakeEmailSender` (logs to console), `NotificationSetting` entity; delivery flow wired to briefing generation |
| 11 | Scheduler: Spring `@Scheduled` task that enqueues `briefing_jobs` for all users based on `notification_settings.delivery_time` |
| 12 | Dashboard API (`GET /api/dashboard`); Admin APIs (`/api/admin/briefing-jobs`, `/api/admin/delivery-logs`) |
