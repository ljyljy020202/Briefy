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
6. [Briefing Category API](#briefing-category-api)
7. [My Briefing Preference API](#my-briefing-preference-api)
8. [Dashboard API](#dashboard-api)
9. [Briefing API](#briefing-api)
10. [Feedback API](#feedback-api)
11. [Admin API](#admin-api)
12. [Agent Server API Contract](#agent-server-api-contract)
13. [Frontend Integration Notes](#frontend-integration-notes)
14. [MVP Implementation Order](#mvp-implementation-order)

> **Recent additions:** `PATCH /api/users/me/briefing-email-subscription` (§2-3), `DELETE /api/users/me` (§2-4)

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
| `BRIEFING_CATEGORY_NOT_FOUND` | 404 | Referenced briefing category does not exist or is inactive |
| `BRIEFING_PREFERENCE_NOT_FOUND` | 404 | The preference row does not exist or is not owned by the caller |
| `DUPLICATE_BRIEFING_PREFERENCE` | 409 | An active preference for the same `(userId, categoryId)` already exists |
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
    "reportEmail": "user@gmail.com",
    "profileImageUrl": "https://lh3.googleusercontent.com/...",
    "role": "USER",
    "onboardingCompleted": true,
    "briefingEmailEnabled": true
  },
  "error": null
}
```

| Field | Notes |
|---|---|
| `briefingEmailEnabled` | `true` if the user is subscribed to automatic email briefings. Default: `true`. |

**Possible errors:** `UNAUTHORIZED`

---

### 2-2. Complete or Update Onboarding

```
PATCH /api/users/me/onboarding
```

**Auth:** Required

**Description:** Marks onboarding as completed and optionally sets a display nickname. Call this after the user finishes setting up briefing preferences on the onboarding screen. Setting `onboardingCompleted = true` is permanent in MVP.

**Request:**

```json
{
  "nickname": "Jiye",
  "reportEmail": "user@gmail.com"
}
```

| Field | Type | Required | Notes |
|---|---|---|---|
| `nickname` | String | No | Max 100 chars |
| `reportEmail` | String | No | Email address for briefing delivery |

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

### 2-3. Update Briefing Email Subscription

```
PATCH /api/users/me/briefing-email-subscription
```

**Auth:** Required

**Description:** Enables or disables automatic email delivery of scheduled briefings for the authenticated user. When disabled, the daily scheduler skips this user — no briefing is generated and no email is sent.

**Request:**

```json
{
  "enabled": false
}
```

| Field | Type | Required | Notes |
|---|---|---|---|
| `enabled` | Boolean | Yes | `true` = subscribe, `false` = unsubscribe |

**Response:**

```json
{
  "success": true,
  "data": {
    "briefingEmailEnabled": false
  },
  "error": null
}
```

**Possible errors:** `UNAUTHORIZED`, `VALIDATION_ERROR`, `USER_NOT_FOUND`

---

### 2-4. Delete Account

```
DELETE /api/users/me
```

**Auth:** Required

**Description:** Permanently deletes the authenticated user's account and all associated data. Expires the JWT cookie in the response.

**Response:**

```json
{
  "success": true,
  "data": null,
  "error": null
}
```

The response includes a `Set-Cookie` header that expires the `briefy_access_token` cookie.

**Possible errors:** `UNAUTHORIZED`

---

## Briefing Category API

A briefing category defines a type of briefing the service can produce (e.g. job postings, company news, industry trends). Categories are predefined and seeded at startup; users cannot create or modify them. Only `is_active = true` categories are returned.

### 3-1. Get All Active Briefing Categories

```
GET /api/briefing-categories
```

**Auth:** Required

**Description:** Returns all categories where `is_active = true`, ordered by `displayOrder`. Used to populate the preference setup UI during onboarding and in the settings screen.

**Response:**

```json
{
  "success": true,
  "data": [
    {
      "id": 1,
      "code": "JOB_POSTING",
      "displayName": "채용 공고 브리핑",
      "phase": "FIRST",
      "active": true
    }
  ],
  "error": null
}
```

**Active categories per phase:**

| Phase | `code` | Visible in UI |
|---|---|---|
| 1st MVP | `JOB_POSTING` | Yes |
| 1.5 MVP | `COMPANY_NEWS` | Not yet (seeded but `isActive = false`) |
| 2nd MVP | `INDUSTRY_TREND` | Not yet (seeded but `isActive = false`) |

**Possible errors:** `UNAUTHORIZED`

---

## My Briefing Preference API

A briefing preference (`user_briefing_preferences` row) stores a user's conditions for one briefing category. There is at most one active preference per `(user, category)` pair.

### 4-1. Get Current User's Briefing Preferences

```
GET /api/me/briefing-preferences
```

**Auth:** Required

**Description:** Returns all active briefing preferences for the authenticated user.

**Response:**

```json
{
  "success": true,
  "data": [
    {
      "id": 10,
      "categoryCode": "JOB_POSTING",
      "categoryDisplayName": "채용 공고 브리핑",
      "active": true,
      "preference": {
        "roles": ["백엔드 개발자", "풀스택 개발자"],
        "companies": ["네이버", "카카오", "라인"],
        "companySizes": ["대기업", "중견기업"],
        "industries": ["IT/인터넷", "게임"],
        "skills": ["Spring Boot", "Java", "Kotlin"],
        "locations": ["서울", "판교"],
        "experienceLevels": ["신입", "3년 이상"],
        "employmentTypes": ["정규직"]
      },
      "createdAt": "2026-06-28T09:00:00",
      "updatedAt": "2026-06-28T10:00:00"
    }
  ],
  "error": null
}
```

**Possible errors:** `UNAUTHORIZED`

---

### 4-2. Create a Briefing Preference

```
POST /api/me/briefing-preferences
```

**Auth:** Required

**Description:** Creates a preference for a briefing category. At most one active preference is allowed per category. If an active preference for `categoryId` already exists, returns `DUPLICATE_BRIEFING_PREFERENCE`.

**Request — `JOB_POSTING` category:**

```json
{
  "categoryId": 1,
  "preference": {
    "roles": ["백엔드 개발자", "풀스택 개발자"],
    "companies": ["네이버", "카카오", "라인"],
    "companySizes": ["대기업", "중견기업"],
    "industries": ["IT/인터넷", "게임"],
    "skills": ["Spring Boot", "Java", "Kotlin"],
    "locations": ["서울", "판교"],
    "experienceLevels": ["신입", "3년 이상"],
    "employmentTypes": ["정규직"]
  }
}
```

| Field | Type | Required | Notes |
|---|---|---|---|
| `categoryId` | Long | Yes | Must reference an active briefing category |
| `preference` | Object | Yes | Category-specific preference data |
| `preference.roles` | Array\<String\> | No | Target job roles |
| `preference.companies` | Array\<String\> | No | Target companies |
| `preference.companySizes` | Array\<String\> | No | e.g. `대기업`, `중견기업`, `스타트업` |
| `preference.industries` | Array\<String\> | No | e.g. `IT/인터넷`, `게임`, `핀테크` |
| `preference.skills` | Array\<String\> | No | Required or preferred skills |
| `preference.locations` | Array\<String\> | No | Preferred work locations |
| `preference.experienceLevels` | Array\<String\> | No | e.g. `신입`, `3년 이상` |
| `preference.employmentTypes` | Array\<String\> | No | e.g. `정규직`, `계약직` |

At least one preference field must be non-empty.

**Response:**

```json
{
  "success": true,
  "data": {
    "id": 10,
    "categoryCode": "JOB_POSTING",
    "categoryDisplayName": "채용 공고 브리핑",
    "active": true,
    "preference": {
      "roles": ["백엔드 개발자", "풀스택 개발자"],
      "companies": ["네이버", "카카오", "라인"],
      "companySizes": ["대기업", "중견기업"],
      "industries": ["IT/인터넷", "게임"],
      "skills": ["Spring Boot", "Java", "Kotlin"],
      "locations": ["서울", "판교"],
      "experienceLevels": ["신입", "3년 이상"],
      "employmentTypes": ["정규직"]
    },
    "createdAt": "2026-06-28T10:00:00",
    "updatedAt": "2026-06-28T10:00:00"
  },
  "error": null
}
```

**HTTP Status:** `201 Created`

**Possible errors:** `UNAUTHORIZED`, `VALIDATION_ERROR`, `BRIEFING_CATEGORY_NOT_FOUND`, `DUPLICATE_BRIEFING_PREFERENCE`

---

### 4-3. Update a Briefing Preference

```
PATCH /api/me/briefing-preferences/{id}
```

**Auth:** Required

**Description:** Partially updates the preference JSON for an existing preference row. Only fields present in the request body are merged; omitted fields retain their current values. Users can only modify their own preferences.

**Path param:** `id` — `user_briefing_preferences.id`

**Request:**

```json
{
  "preference": {
    "roles": ["백엔드 개발자", "풀스택 개발자", "DevOps 엔지니어"],
    "skills": ["Spring Boot", "Java", "Kotlin", "Docker"]
  }
}
```

All `preference` fields are optional. Only the fields provided are merged into the stored JSON.

**Response:**

```json
{
  "success": true,
  "data": {
    "id": 10,
    "categoryCode": "JOB_POSTING",
    "categoryDisplayName": "채용 공고 브리핑",
    "active": true,
    "preference": {
      "roles": ["백엔드 개발자", "풀스택 개발자", "DevOps 엔지니어"],
      "companies": ["네이버", "카카오", "라인"],
      "companySizes": ["대기업", "중견기업"],
      "industries": ["IT/인터넷", "게임"],
      "skills": ["Spring Boot", "Java", "Kotlin", "Docker"],
      "locations": ["서울", "판교"],
      "experienceLevels": ["신입", "3년 이상"],
      "employmentTypes": ["정규직"]
    },
    "createdAt": "2026-06-28T10:00:00",
    "updatedAt": "2026-06-28T11:30:00"
  },
  "error": null
}
```

**Possible errors:** `UNAUTHORIZED`, `VALIDATION_ERROR`, `BRIEFING_PREFERENCE_NOT_FOUND`, `FORBIDDEN`

---

### 4-4. Delete a Briefing Preference

```
DELETE /api/me/briefing-preferences/{id}
```

**Auth:** Required

**Description:** Soft-deletes the preference by setting `isActive = false`. The row is not removed from the database so that briefing history remains coherent.

**Path param:** `id` — `user_briefing_preferences.id`

**Response:**

```json
{
  "success": true,
  "data": null,
  "error": null
}
```

**Possible errors:** `UNAUTHORIZED`, `BRIEFING_PREFERENCE_NOT_FOUND`, `FORBIDDEN`

---

## Dashboard API

### 5-1. Get Dashboard Summary

```
GET /api/dashboard
```

**Auth:** Required

**Description:** Returns a combined summary for the dashboard UI in a single request. Aggregates user info, active briefing preferences, the next scheduled delivery time, the latest briefing report preview, and the latest delivery status.

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
    "briefingPreferences": [
      {
        "categoryCode": "JOB_POSTING",
        "categoryDisplayName": "채용 공고 브리핑",
        "preference": {
          "roles": ["백엔드 개발자", "풀스택 개발자"],
          "companies": ["네이버", "카카오", "라인"],
          "companySizes": ["대기업", "중견기업"],
          "industries": ["IT/인터넷", "게임"],
          "skills": ["Spring Boot", "Java", "Kotlin"],
          "locations": ["서울", "판교"],
          "experienceLevels": ["신입", "3년 이상"],
          "employmentTypes": ["정규직"]
        }
      }
    ],
    "nextDeliveryTime": null,
    "latestBriefing": {
      "id": 100,
      "title": "오늘의 채용 브리핑 — 백엔드 개발자 (2026-06-28)",
      "summary": "네이버·카카오에서 신규 공고 3건, 마감 임박 공고 2건이 확인됐습니다.",
      "reportDate": "2026-06-28",
      "articleCount": 5,
      "createdAt": "2026-06-28T08:00:00"
    },
    "latestDeliveryStatus": null,
    "recentReports": [
      {
        "id": 100,
        "title": "오늘의 채용 브리핑 — 백엔드 개발자 (2026-06-28)",
        "summary": "네이버·카카오에서 신규 공고 3건, 마감 임박 공고 2건이 확인됐습니다.",
        "reportDate": "2026-06-28",
        "articleCount": 5,
        "createdAt": "2026-06-28T08:00:00"
      }
    ]
  },
  "error": null
}
```

| Field | Notes |
|---|---|
| `briefingPreferences` | Active preferences only, one entry per active category |
| `nextDeliveryTime` | `null` — delivery-time personalization not yet implemented; scheduler runs at fixed 08:00 KST (`briefy.scheduler.enabled: false` by default) |
| `latestBriefing` | Full `BriefingListItem` for the most recent report; `null` if no reports exist |
| `latestDeliveryStatus` | Most recent delivery status (`SENT`, `PENDING`, or `FAILED`) from `delivery_logs`; `null` if no delivery has been attempted |
| `recentReports` | Up to 3 most recent `BriefingListItem` records, ordered by date descending; empty array if no reports exist |

**Possible errors:** `UNAUTHORIZED`

---

## Briefing API

### 6-1. Manually Generate Briefing

```
POST /api/briefings/generate
```

**Auth:** Required

**Description:** Triggers an immediate briefing generation. The backend creates a `briefing_jobs` row, calls the Agent server, persists the result, and returns the report reference. This call is **synchronous** in MVP — the HTTP response is returned only after generation is complete.

Requires that the daily candidate pool (`job_postings`) has already been collected for today's date by `DailyCollectWorkflow`.

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
2. Load active user_briefing_preferences for the user
3. Load today's job_postings candidate pool from DB
4. Pre-score candidates against user preferences; select top 30
5. Create briefing_jobs row (status = PENDING, triggerType = MANUAL)
6. Update job status → PROCESSING, set startedAt
7. Call Agent: POST /briefings/generate with preference + candidatePool
8. On success:
   a. Insert briefing_reports row
   b. Insert briefing_articles rows
   c. Update job status → COMPLETED, set completedAt
9. On failure:
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
        "reportDate": "2026-06-28",
        "articleCount": 5,
        "createdAt": "2026-06-28T08:00:00"
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
    "title": "오늘의 채용 브리핑 — 백엔드 개발자 (2026-06-28)",
    "summary": "네이버·카카오에서 신규 공고 3건, 마감 임박 공고 2건이 확인됐습니다.",
    "content": "## 📌 신규 공고\n\n...\n\n## ⏰ 마감 임박 공고\n\n...\n\n## 💡 오늘의 추천 액션\n\n...",
    "reportDate": "2026-06-28",
    "articles": [
      {
        "title": "네이버 — 백엔드 개발자 (Spring Boot) 채용",
        "source": "채용 플랫폼",
        "url": "https://example.com/job/123",
        "summary": "네이버 서치 플랫폼팀에서 Spring Boot · Java 경력 3년 이상 백엔드 개발자를 모집합니다.",
        "whyItMatters": "목표 회사(네이버)이며 핵심 스킬(Spring Boot, Java)과 정확히 매칭됩니다.",
        "publishedAt": "2026-06-28T00:00:00"
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
  "comment": "오늘 공고 매칭이 정확했어요."
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
    "comment": "오늘 공고 매칭이 정확했어요."
  },
  "error": null
}
```

**HTTP Status:** `201 Created`

**Notes:** MVP stores feedback only. Future personalization (adjusting ranking weights in `UserBriefingWorkflow` based on feedback) is out of scope.

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
        "scheduledAt": "2026-06-28T08:00:00",
        "startedAt": "2026-06-28T08:00:01",
        "completedAt": null,
        "errorMessage": "Agent server timeout",
        "retryCount": 1,
        "createdAt": "2026-06-28T08:00:00"
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

### 8-2. Trigger Daily Collection (Admin)

```
POST /api/admin/collections/daily
```

**Auth:** Required, ADMIN only

**Description:** Manually triggers the daily job-posting collection for a given date. The backend aggregates seed keywords from all active `user_briefing_preferences`, calls the Agent `POST /collections/daily`, and upserts the returned job postings into the `job_postings` candidate pool. Returns a summary of the collection run.

**Request:**

```json
{
  "collectDate": "2026-07-01",
  "categories": ["JOB_POSTING"]
}
```

| Field | Type | Required | Notes |
|---|---|---|---|
| `collectDate` | String (ISO-8601) | No | Date to collect for; defaults to today |
| `categories` | Array\<String\> | No | Category codes to collect; defaults to `["JOB_POSTING"]` |

**Response:**

```json
{
  "success": true,
  "data": {
    "collectionJobId": 42,
    "status": "COMPLETED",
    "collectDate": "2026-07-01",
    "collectedCount": 5,
    "savedCount": 5,
    "deduplicatedCount": 0,
    "errorMessage": null
  },
  "error": null
}
```

| Field | Notes |
|---|---|
| `collectedCount` | Total raw postings returned by the Agent |
| `savedCount` | New rows inserted into `job_postings` (skips existing URLs) |
| `deduplicatedCount` | Rows skipped due to URL already existing |
| `status` | `COMPLETED`, `FAILED`, or `SKIPPED` (if already active for the date) |

**Possible errors:** `UNAUTHORIZED`, `FORBIDDEN`, `AGENT_SERVER_ERROR`

---

### 8-4. Get Delivery Logs (Admin)

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
        "createdAt": "2026-06-28T08:01:00"
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

The Agent server is an internal FastAPI service. It is **called only by the Spring Boot backend** (`AgentClient`) and must not be directly accessible to the public internet or the frontend.

Agent endpoints do **not** use the `/api` prefix.

The Agent exposes two distinct workflows:
- **`POST /collections/daily`** — run by the backend scheduler once per day; collects job postings into the shared candidate pool.
- **`POST /briefings/generate`** — run per user; reads from the candidate pool and generates a personalized Markdown briefing.

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

### 9-2. Trigger Daily Collection

```
POST /collections/daily
```

**Called by:** Spring Boot daily scheduler (`AgentClient`)

**Auth:** None (internal network only; restrict via Docker network or security group in production)

**Description:** Accepts seed keywords, Company Registry data, and official company sources assembled by Spring. Runs the V2 10-stage collection pipeline via the configured adapters and returns the processed list to Spring. Spring then upserts postings into `job_postings` and `job_posting_sources`. The Agent does not access the database.

Default mode (`JOB_COLLECTION_USE_FIXTURE=true`) returns deterministic fixture postings with no network calls. Set `JOB_COLLECTION_ENABLE_REAL_SOURCES=true` and/or `JOB_COLLECTION_ENABLE_SARAMIN=true` to enable real source collection.

Must run before `POST /briefings/generate` so the candidate pool is populated.

**Request:**

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

| Field | Type | Notes |
|---|---|---|
| `collectionJobId` | Long | `collection_jobs.id`; echo'd in response for correlation |
| `collectDate` | String | ISO-8601 date (`YYYY-MM-DD`); the date being collected for |
| `categories` | Array\<String\> | Briefing category codes to collect for |
| `seedKeywords` | Object | Aggregated by Spring from all active `user_briefing_preferences` |
| `seedKeywords.companySizes` | Array\<String\> | From `preference_json.companySizes` |
| `seedKeywords.industries` | Array\<String\> | From `preference_json.industries` |
| `options.discoveryLimitPerSource` | Int | Max URLs enumerated per source (default: 50) |
| `options.detailFetchLimitPerSource` | Int | Max detail fetches per source (default: 50) |
| `options.maxResultsPerSource` | Int | Max postings selected per source (default: 50) |
| `options.maxTotalResults` | Int | Hard cap on total returned postings (default: 200) |
| `companyProfiles` | Array | Company Registry rows for the companies in `seedKeywords.companies` |
| `officialCompanySources` | Array | Active `company_sources` rows; dispatched by `OfficialCompanyAdapter` |

**Response:**

```json
{
  "collectionJobId": 42,
  "collectDate": "2026-07-01",
  "jobPostings": [
    {
      "source": "fixture",
      "sourceUrl": "https://fixture.local/jobs/00123",
      "companyName": "네이버",
      "title": "네이버 — 백엔드 개발자",
      "position": "백엔드 개발자",
      "employmentType": "정규직",
      "experienceLevel": "신입",
      "location": "서울",
      "deadline": "2026-07-15",
      "skills": ["Spring Boot", "Java"],
      "roles": ["백엔드 개발자"],
      "description": "[픽스처 데이터] 채용 공고 설명",
      "postedAt": "2026-07-01T09:00:00",
      "contentHash": "a3f2...sha256hex...64chars",
      "sourceRecordKey": "b1c2...sha256hex...64chars",
      "canonicalFingerprint": "c3d4...sha256hex...64chars",
      "sourceRefs": [
        {
          "source": "fixture",
          "sourceExternalId": null,
          "sourceUrl": "https://fixture.local/jobs/00123",
          "sourceRecordKey": "b1c2...sha256hex...64chars"
        }
      ]
    }
  ],
  "companyIssues": [],
  "industryIssues": [],
  "stats": {
    "discovered": 5,
    "fetched": 5,
    "parsed": 5,
    "exactDuplicates": 0,
    "crossSourceMerged": 0,
    "expiredFiltered": 0,
    "staleFiltered": 0,
    "truncated": 0,
    "final": 5,
    "collectedCount": 5,
    "deduplicatedCount": 0,
    "jobPostingCount": 5
  },
  "warnings": []
}
```

| Field | Notes |
|---|---|
| `jobPostings` | Processed postings returned to Spring for upsert into `job_postings` / `job_posting_sources` |
| `jobPostings[].source` | `"fixture"` in default mode; `"jasoseol"`, `"saramin"`, etc. with real sources |
| `jobPostings[].sourceRecordKey` | SHA-256 identity key; stored in `job_posting_sources.source_record_key` |
| `jobPostings[].canonicalFingerprint` | Cross-source merge key; identifies the same posting across platforms |
| `jobPostings[].sourceRefs` | All source records merged into this canonical posting |
| `companyIssues` | Always `[]` in 1st MVP |
| `industryIssues` | Always `[]` in 1st MVP |
| `stats.discovered` | URLs enumerated before fetching |
| `stats.fetched` | Detail pages actually fetched |
| `stats.parsed` | Postings successfully parsed |
| `stats.exactDuplicates` | Items removed by same-source dedup |
| `stats.crossSourceMerged` | Items merged across sources |
| `stats.expiredFiltered` | Items removed due to expired deadline |
| `stats.staleFiltered` | Items removed due to lookback window |
| `stats.final` | Final count returned |
| `stats.collectedCount` | Alias for `discovered` (backward compat) |
| `stats.deduplicatedCount` | Alias for `exactDuplicates` (backward compat) |
| `stats.jobPostingCount` | Alias for `final` (backward compat) |
| `warnings` | Non-fatal issues from adapters (timeouts, parse errors, HTTP errors) |

**Error handling (backend side):** Log the failure and proceed; user briefing generation for the day may return empty results but should not fail hard.

---

### 9-3. Generate Briefing

```
POST /briefings/generate
```

**Called by:** Spring Boot `AgentClient` (WebClient)

**Auth:** None (internal network only; restrict via Docker network or security group in production)

**Description:** Runs `UserBriefingWorkflow`. Receives a pre-scored `candidatePool` assembled by Spring, filters past-deadline / invalid postings, re-ranks by combined score, selects the top 7, and assembles a Markdown briefing. Does **not** call external sources or the database — all input data is in the request body.

LLM enrichment (enrichment + synthesis via `gpt-4o-mini`) is enabled when `OPENAI_API_KEY` is set. Both LLM nodes fall back to deterministic equivalents when the key is absent or any LLM call fails — the pipeline never returns HTTP 500. `tokenUsage` reflects actual LLM token usage when enabled; it is `{inputTokens: 0, outputTokens: 0}` in fallback mode.

**Request:**

```json
{
  "userId": 1,
  "category": "JOB_POSTING",
  "preference": {
    "roles": ["백엔드 개발자", "풀스택 개발자"],
    "companies": ["네이버", "카카오", "라인"],
    "companySizes": ["대기업", "중견기업"],
    "industries": ["IT/인터넷", "게임"],
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

| Field | Type | Notes |
|---|---|---|
| `userId` | Long | For logging and tracing only; Agent does not persist it |
| `category` | String | Briefing category code (e.g. `JOB_POSTING`) |
| `preference` | Object | The user's `preference_json` from `user_briefing_preferences` |
| `briefingDate` | String | ISO-8601 date (`YYYY-MM-DD`); used for deadline filtering |
| `tone` | String | Forwarded from the frontend or scheduler; used as tone hint in LLM prompts |
| `candidatePool.jobPostings` | Array | Top 30 pre-scored `job_postings` rows selected by Spring; sorted by `preScore` desc |
| `candidatePool.jobPostings[].preScore` | Integer | Score assigned by Spring's preference-matching logic |
| `candidatePool.companyIssues` | Array | Always `[]` in 1st MVP |
| `candidatePool.industryIssues` | Array | Always `[]` in 1st MVP |

**Response:**

```json
{
  "title": "오늘의 채용 브리핑 — 백엔드 개발자 (2026-07-01)",
  "summary": "2026-07-01 기준, 네이버·카카오에서 추천 공고 2건을 선별했습니다.",
  "content": "## 오늘의 핵심 요약\n\n...\n\n## 🏆 추천 공고 TOP 2\n\n...\n\n## 💡 오늘의 지원 추천 액션\n\n...",
  "articles": [
    {
      "title": "네이버 백엔드 개발자",
      "source": "원티드",
      "url": "https://www.wanted.co.kr/wd/00001",
      "summary": "네이버 — 백엔드 개발자 채용",
      "whyItMatters": "관심 기업(네이버) · 백엔드 개발자 포지션 매칭 · 스킬 매칭: Spring Boot, Java",
      "publishedAt": "2026-07-01T09:00:00",
      "companyName": "네이버"
    }
  ],
  "tokenUsage": {
    "inputTokens": 0,
    "outputTokens": 0
  }
}
```

| Field | Notes |
|---|---|
| `content` | Full briefing in **Markdown** |
| `articles` | Each element is one job posting selected for the report (up to 7). May be empty if `candidatePool` is empty or all postings are past-deadline. |
| `tokenUsage` | Reflects actual LLM token usage (Call 1 enrichment + Call 2 synthesis) when `OPENAI_API_KEY` is set; `{inputTokens: 0, outputTokens: 0}` in fallback mode |

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

### Page routing

| Route | Description | APIs called |
|---|---|---|
| `/login` | Google 로그인. 이미 인증된 기존 회원은 `/dashboard`로, 온보딩 미완료 회원은 `/onboarding`으로 리디렉션. | `GET /api/users/me` |
| `/onboarding` | 신규 회원 온보딩 (미인증 또는 온보딩 미완료). 온보딩이 이미 완료된 경우 `/dashboard`로 리디렉션. | `GET /api/users/me`, `GET /api/briefing-categories`, `POST /api/me/briefing-preferences`, `PATCH /api/users/me/onboarding` |
| `/dashboard` | 로그인 후 메인 화면. | `GET /api/dashboard` |
| `/reports` | 브리핑 목록 (페이지네이션). | `GET /api/briefings` |
| `/reports/[id]` | 브리핑 상세 보기. | `GET /api/briefings/{id}` |
| `/mypage` | 계정 정보 및 브리핑 선호도 관리. 이메일 수신 설정, 로그아웃, 계정 삭제는 이 페이지에서만 가능. | `GET /api/users/me`, `GET /api/me/briefing-preferences`, `GET /api/briefing-categories`, `PATCH /api/users/me/onboarding`, `POST /api/me/briefing-preferences`, `PATCH /api/me/briefing-preferences/{id}`, `DELETE /api/me/briefing-preferences/{id}`, `PATCH /api/users/me/briefing-email-subscription`, `POST /api/auth/logout`, `DELETE /api/users/me` |

---

## MVP Implementation Order

Suggested order for implementing backend features. Each step produces runnable, testable code before the next begins.

| Step | Scope |
|---|---|
| 1 | `ApiResponse<T>` wrapper class + global exception handler + all error codes |
| 2 | `User` entity, `UserRepository`, `UserService` (find or create by provider) |
| 3 | `BriefingCategory` entity + seed data, `UserBriefingPreference` entity; BriefingCategory API and My Briefing Preference API |
| 4 | Google OAuth security config, callback handler, JWT issue + cookie, `GET /api/users/me`, `PATCH /api/users/me/onboarding` |
| 5 | Frontend: landing page → OAuth redirect → onboarding → dashboard skeleton (wired to `/api/users/me` and briefing preference APIs) |
| 6 | Agent server stub: `POST /collections/daily` returns hardcoded counts; `POST /briefings/generate` returns a hardcoded Markdown response |
| 7 | Spring `AgentClient` (WebClient): calls Agent stub, maps response to internal DTOs |
| 8 | `BriefingJob`, `BriefingReport`, `BriefingArticle` entities; `POST /api/briefings/generate`, `GET /api/briefings`, `GET /api/briefings/{id}` |
| 9 | Feedback API: `UserFeedback` entity, `POST /api/briefings/{id}/feedback` |
| 10 | `DeliveryLog` entity, `FakeEmailSender` (logs to console), `NotificationSetting` entity; delivery flow wired to briefing generation |
| 11 | Scheduler: Spring `@Scheduled` task that (a) triggers `POST /collections/daily` on the Agent each morning, then (b) enqueues `briefing_jobs` for all users based on `notification_settings.delivery_time` |
| 12 | Dashboard API (`GET /api/dashboard`); Admin APIs (`/api/admin/briefing-jobs`, `/api/admin/delivery-logs`) |
