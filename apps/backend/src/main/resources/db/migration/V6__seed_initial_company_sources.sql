-- V6__seed_initial_company_sources.sql
-- Initial company_sources seed — 18 rows.
--
-- All rows:
--   source_type       = 'OFFICIAL_CAREER'
--   adapter_type      = 'CUSTOM'
--   status            = 'PENDING'  ← CompanySourceRepository queries status='ACTIVE';
--                                    PENDING rows are never fetched by the collector.
--   last_verified_at  = NULL       ← not yet verified
--   last_collected_at = NULL       ← not yet collected
--
-- CUSTOM config_json contract:
--   {"parser_key": "<KEY>", "max_discover": 50, "max_fetch": 20}
--
-- parser_key registry (14 distinct keys):
--   Implemented:
--     GREETING         — careers.greeting.works hosted pages
--   Pending implementation:
--     NAVER_CAREERS    — recruit.navercorp.com
--     KAKAO_CAREERS    — careers.kakao.com
--     LINE_CAREERS     — careers.linecorp.com
--     TOSS_CAREERS     — toss.im/career
--     DAANGN_CAREERS   — careers.daangn.com
--     COUPANG_CAREERS  — coupang.jobs
--     WOOWA_CAREERS    — career.woowahan.com
--     ABLY_CAREERS     — ably.team/recruit
--     DUNAMU_CAREERS   — dunamu.com/careers
--     SOCAR_CAREERS    — socarcorp.kr/careers
--     BUCKETPLACE_CAREERS — bucketplace.com/careers
--     GREENHOUSE       — job-boards.greenhouse.io ATS
--     ROUNDHR          — *.recruit.roundhr.com ATS
--     WORKDAY          — *.myworkdayjobs.com ATS
--     SENDBIRD_CAREERS — sendbird.com/ko/careers
--
-- Excluded (group-level portals shared across subsidiaries):
--   Samsung Careers, SK Careers, LG Careers, CJ 그룹채용,
--   현대자동차그룹 채용, KT 그룹채용
--
-- source_url values are taken verbatim from VERIFIED_SOURCE_ROWS only.
-- No URL guessing. No /sitemap.xml suffix appended.
--
-- UNIQUE constraint guarded: (company_id, source_type, source_url(191)) — V4
-- Idempotency: every INSERT is guarded by NOT EXISTS — safe to re-run.

-- =============================================================================
-- 1. NAVER_CAREERS
-- =============================================================================

INSERT INTO company_sources (company_id, source_type, source_url, adapter_type, status, config_json, last_verified_at, last_collected_at, created_at, updated_at)
SELECT c.id,
       'OFFICIAL_CAREER',
       'https://recruit.navercorp.com/rcrt/list.do',
       'CUSTOM',
       'PENDING',
       '{"parser_key": "NAVER_CAREERS", "max_discover": 50, "max_fetch": 20}',
       NULL, NULL, NOW(6), NOW(6)
FROM companies c
WHERE c.normalized_name = '네이버'
  AND NOT EXISTS (
    SELECT 1 FROM company_sources cs
    WHERE cs.company_id = c.id
      AND cs.source_type = 'OFFICIAL_CAREER'
      AND cs.source_url  = 'https://recruit.navercorp.com/rcrt/list.do'
  );

-- =============================================================================
-- 2. KAKAO_CAREERS
-- =============================================================================

INSERT INTO company_sources (company_id, source_type, source_url, adapter_type, status, config_json, last_verified_at, last_collected_at, created_at, updated_at)
SELECT c.id,
       'OFFICIAL_CAREER',
       'https://careers.kakao.com/jobs',
       'CUSTOM',
       'PENDING',
       '{"parser_key": "KAKAO_CAREERS", "max_discover": 50, "max_fetch": 20}',
       NULL, NULL, NOW(6), NOW(6)
FROM companies c
WHERE c.normalized_name = '카카오'
  AND NOT EXISTS (
    SELECT 1 FROM company_sources cs
    WHERE cs.company_id = c.id
      AND cs.source_type = 'OFFICIAL_CAREER'
      AND cs.source_url  = 'https://careers.kakao.com/jobs'
  );

-- =============================================================================
-- 3. LINE_CAREERS
-- =============================================================================

INSERT INTO company_sources (company_id, source_type, source_url, adapter_type, status, config_json, last_verified_at, last_collected_at, created_at, updated_at)
SELECT c.id,
       'OFFICIAL_CAREER',
       'https://careers.linecorp.com/ko/jobs/',
       'CUSTOM',
       'PENDING',
       '{"parser_key": "LINE_CAREERS", "max_discover": 50, "max_fetch": 20}',
       NULL, NULL, NOW(6), NOW(6)
FROM companies c
WHERE c.normalized_name = '라인플러스'
  AND NOT EXISTS (
    SELECT 1 FROM company_sources cs
    WHERE cs.company_id = c.id
      AND cs.source_type = 'OFFICIAL_CAREER'
      AND cs.source_url  = 'https://careers.linecorp.com/ko/jobs/'
  );

-- =============================================================================
-- 4. TOSS_CAREERS
-- =============================================================================

INSERT INTO company_sources (company_id, source_type, source_url, adapter_type, status, config_json, last_verified_at, last_collected_at, created_at, updated_at)
SELECT c.id,
       'OFFICIAL_CAREER',
       'https://toss.im/career/jobs',
       'CUSTOM',
       'PENDING',
       '{"parser_key": "TOSS_CAREERS", "max_discover": 50, "max_fetch": 20}',
       NULL, NULL, NOW(6), NOW(6)
FROM companies c
WHERE c.normalized_name = '토스'
  AND NOT EXISTS (
    SELECT 1 FROM company_sources cs
    WHERE cs.company_id = c.id
      AND cs.source_type = 'OFFICIAL_CAREER'
      AND cs.source_url  = 'https://toss.im/career/jobs'
  );

-- =============================================================================
-- 5. DAANGN_CAREERS
-- =============================================================================

INSERT INTO company_sources (company_id, source_type, source_url, adapter_type, status, config_json, last_verified_at, last_collected_at, created_at, updated_at)
SELECT c.id,
       'OFFICIAL_CAREER',
       'https://careers.daangn.com/jobs/',
       'CUSTOM',
       'PENDING',
       '{"parser_key": "DAANGN_CAREERS", "max_discover": 50, "max_fetch": 20}',
       NULL, NULL, NOW(6), NOW(6)
FROM companies c
WHERE c.normalized_name = '당근'
  AND NOT EXISTS (
    SELECT 1 FROM company_sources cs
    WHERE cs.company_id = c.id
      AND cs.source_type = 'OFFICIAL_CAREER'
      AND cs.source_url  = 'https://careers.daangn.com/jobs/'
  );

-- =============================================================================
-- 6. COUPANG_CAREERS
-- =============================================================================

INSERT INTO company_sources (company_id, source_type, source_url, adapter_type, status, config_json, last_verified_at, last_collected_at, created_at, updated_at)
SELECT c.id,
       'OFFICIAL_CAREER',
       'https://www.coupang.jobs/kr/jobs/',
       'CUSTOM',
       'PENDING',
       '{"parser_key": "COUPANG_CAREERS", "max_discover": 50, "max_fetch": 20}',
       NULL, NULL, NOW(6), NOW(6)
FROM companies c
WHERE c.normalized_name = '쿠팡'
  AND NOT EXISTS (
    SELECT 1 FROM company_sources cs
    WHERE cs.company_id = c.id
      AND cs.source_type = 'OFFICIAL_CAREER'
      AND cs.source_url  = 'https://www.coupang.jobs/kr/jobs/'
  );

-- =============================================================================
-- 7. WOOWA_CAREERS
-- =============================================================================

INSERT INTO company_sources (company_id, source_type, source_url, adapter_type, status, config_json, last_verified_at, last_collected_at, created_at, updated_at)
SELECT c.id,
       'OFFICIAL_CAREER',
       'https://career.woowahan.com/',
       'CUSTOM',
       'PENDING',
       '{"parser_key": "WOOWA_CAREERS", "max_discover": 50, "max_fetch": 20}',
       NULL, NULL, NOW(6), NOW(6)
FROM companies c
WHERE c.normalized_name = '우아한형제들'
  AND NOT EXISTS (
    SELECT 1 FROM company_sources cs
    WHERE cs.company_id = c.id
      AND cs.source_type = 'OFFICIAL_CAREER'
      AND cs.source_url  = 'https://career.woowahan.com/'
  );

-- =============================================================================
-- 8. GREETING — 무신사
-- =============================================================================

INSERT INTO company_sources (company_id, source_type, source_url, adapter_type, status, config_json, last_verified_at, last_collected_at, created_at, updated_at)
SELECT c.id,
       'OFFICIAL_CAREER',
       'https://www.musinsacareers.com/ko/home',
       'CUSTOM',
       'PENDING',
       '{"parser_key": "GREETING", "max_discover": 50, "max_fetch": 20}',
       NULL, NULL, NOW(6), NOW(6)
FROM companies c
WHERE c.normalized_name = '무신사'
  AND NOT EXISTS (
    SELECT 1 FROM company_sources cs
    WHERE cs.company_id = c.id
      AND cs.source_type = 'OFFICIAL_CAREER'
      AND cs.source_url  = 'https://www.musinsacareers.com/ko/home'
  );

-- =============================================================================
-- 9. ABLY_CAREERS
-- =============================================================================

INSERT INTO company_sources (company_id, source_type, source_url, adapter_type, status, config_json, last_verified_at, last_collected_at, created_at, updated_at)
SELECT c.id,
       'OFFICIAL_CAREER',
       'https://ably.team/recruit',
       'CUSTOM',
       'PENDING',
       '{"parser_key": "ABLY_CAREERS", "max_discover": 50, "max_fetch": 20}',
       NULL, NULL, NOW(6), NOW(6)
FROM companies c
WHERE c.normalized_name = '에이블리'
  AND NOT EXISTS (
    SELECT 1 FROM company_sources cs
    WHERE cs.company_id = c.id
      AND cs.source_type = 'OFFICIAL_CAREER'
      AND cs.source_url  = 'https://ably.team/recruit'
  );

-- =============================================================================
-- 10. DUNAMU_CAREERS
-- =============================================================================

INSERT INTO company_sources (company_id, source_type, source_url, adapter_type, status, config_json, last_verified_at, last_collected_at, created_at, updated_at)
SELECT c.id,
       'OFFICIAL_CAREER',
       'https://www.dunamu.com/careers/jobs',
       'CUSTOM',
       'PENDING',
       '{"parser_key": "DUNAMU_CAREERS", "max_discover": 50, "max_fetch": 20}',
       NULL, NULL, NOW(6), NOW(6)
FROM companies c
WHERE c.normalized_name = '두나무'
  AND NOT EXISTS (
    SELECT 1 FROM company_sources cs
    WHERE cs.company_id = c.id
      AND cs.source_type = 'OFFICIAL_CAREER'
      AND cs.source_url  = 'https://www.dunamu.com/careers/jobs'
  );

-- =============================================================================
-- 11. SOCAR_CAREERS
-- =============================================================================

INSERT INTO company_sources (company_id, source_type, source_url, adapter_type, status, config_json, last_verified_at, last_collected_at, created_at, updated_at)
SELECT c.id,
       'OFFICIAL_CAREER',
       'https://www.socarcorp.kr/careers/jobs',
       'CUSTOM',
       'PENDING',
       '{"parser_key": "SOCAR_CAREERS", "max_discover": 50, "max_fetch": 20}',
       NULL, NULL, NOW(6), NOW(6)
FROM companies c
WHERE c.normalized_name = '쏘카'
  AND NOT EXISTS (
    SELECT 1 FROM company_sources cs
    WHERE cs.company_id = c.id
      AND cs.source_type = 'OFFICIAL_CAREER'
      AND cs.source_url  = 'https://www.socarcorp.kr/careers/jobs'
  );

-- =============================================================================
-- 12. GREETING — 현대오토에버
-- =============================================================================

INSERT INTO company_sources (company_id, source_type, source_url, adapter_type, status, config_json, last_verified_at, last_collected_at, created_at, updated_at)
SELECT c.id,
       'OFFICIAL_CAREER',
       'https://career.hyundai-autoever.com/ko/home',
       'CUSTOM',
       'PENDING',
       '{"parser_key": "GREETING", "max_discover": 50, "max_fetch": 20}',
       NULL, NULL, NOW(6), NOW(6)
FROM companies c
WHERE c.normalized_name = '현대오토에버'
  AND NOT EXISTS (
    SELECT 1 FROM company_sources cs
    WHERE cs.company_id = c.id
      AND cs.source_type = 'OFFICIAL_CAREER'
      AND cs.source_url  = 'https://career.hyundai-autoever.com/ko/home'
  );

-- =============================================================================
-- 13. GREETING — CJ올리브영
-- =============================================================================

INSERT INTO company_sources (company_id, source_type, source_url, adapter_type, status, config_json, last_verified_at, last_collected_at, created_at, updated_at)
SELECT c.id,
       'OFFICIAL_CAREER',
       'https://career.oliveyoung.com/ko/home',
       'CUSTOM',
       'PENDING',
       '{"parser_key": "GREETING", "max_discover": 50, "max_fetch": 20}',
       NULL, NULL, NOW(6), NOW(6)
FROM companies c
WHERE c.normalized_name = 'cj올리브영'
  AND NOT EXISTS (
    SELECT 1 FROM company_sources cs
    WHERE cs.company_id = c.id
      AND cs.source_type = 'OFFICIAL_CAREER'
      AND cs.source_url  = 'https://career.oliveyoung.com/ko/home'
  );

-- =============================================================================
-- 14. BUCKETPLACE_CAREERS
-- =============================================================================

INSERT INTO company_sources (company_id, source_type, source_url, adapter_type, status, config_json, last_verified_at, last_collected_at, created_at, updated_at)
SELECT c.id,
       'OFFICIAL_CAREER',
       'https://www.bucketplace.com/careers/',
       'CUSTOM',
       'PENDING',
       '{"parser_key": "BUCKETPLACE_CAREERS", "max_discover": 50, "max_fetch": 20}',
       NULL, NULL, NOW(6), NOW(6)
FROM companies c
WHERE c.normalized_name = '오늘의집'
  AND NOT EXISTS (
    SELECT 1 FROM company_sources cs
    WHERE cs.company_id = c.id
      AND cs.source_type = 'OFFICIAL_CAREER'
      AND cs.source_url  = 'https://www.bucketplace.com/careers/'
  );

-- =============================================================================
-- 15. GREENHOUSE — 크래프톤
-- =============================================================================

INSERT INTO company_sources (company_id, source_type, source_url, adapter_type, status, config_json, last_verified_at, last_collected_at, created_at, updated_at)
SELECT c.id,
       'OFFICIAL_CAREER',
       'https://job-boards.greenhouse.io/krafton',
       'CUSTOM',
       'PENDING',
       '{"parser_key": "GREENHOUSE", "max_discover": 50, "max_fetch": 20}',
       NULL, NULL, NOW(6), NOW(6)
FROM companies c
WHERE c.normalized_name = '크래프톤'
  AND NOT EXISTS (
    SELECT 1 FROM company_sources cs
    WHERE cs.company_id = c.id
      AND cs.source_type = 'OFFICIAL_CAREER'
      AND cs.source_url  = 'https://job-boards.greenhouse.io/krafton'
  );

-- =============================================================================
-- 16. ROUNDHR — 리디
-- =============================================================================

INSERT INTO company_sources (company_id, source_type, source_url, adapter_type, status, config_json, last_verified_at, last_collected_at, created_at, updated_at)
SELECT c.id,
       'OFFICIAL_CAREER',
       'https://ridi.recruit.roundhr.com/home',
       'CUSTOM',
       'PENDING',
       '{"parser_key": "ROUNDHR", "max_discover": 50, "max_fetch": 20}',
       NULL, NULL, NOW(6), NOW(6)
FROM companies c
WHERE c.normalized_name = '리디'
  AND NOT EXISTS (
    SELECT 1 FROM company_sources cs
    WHERE cs.company_id = c.id
      AND cs.source_type = 'OFFICIAL_CAREER'
      AND cs.source_url  = 'https://ridi.recruit.roundhr.com/home'
  );

-- =============================================================================
-- 17. WORKDAY — 야놀자
-- =============================================================================

INSERT INTO company_sources (company_id, source_type, source_url, adapter_type, status, config_json, last_verified_at, last_collected_at, created_at, updated_at)
SELECT c.id,
       'OFFICIAL_CAREER',
       'https://yanolja.wd102.myworkdayjobs.com/ko-KR/External_Yanolja',
       'CUSTOM',
       'PENDING',
       '{"parser_key": "WORKDAY", "max_discover": 50, "max_fetch": 20}',
       NULL, NULL, NOW(6), NOW(6)
FROM companies c
WHERE c.normalized_name = '야놀자'
  AND NOT EXISTS (
    SELECT 1 FROM company_sources cs
    WHERE cs.company_id = c.id
      AND cs.source_type = 'OFFICIAL_CAREER'
      AND cs.source_url  = 'https://yanolja.wd102.myworkdayjobs.com/ko-KR/External_Yanolja'
  );

-- =============================================================================
-- 18. SENDBIRD_CAREERS
-- =============================================================================

INSERT INTO company_sources (company_id, source_type, source_url, adapter_type, status, config_json, last_verified_at, last_collected_at, created_at, updated_at)
SELECT c.id,
       'OFFICIAL_CAREER',
       'https://sendbird.com/ko/careers',
       'CUSTOM',
       'PENDING',
       '{"parser_key": "SENDBIRD_CAREERS", "max_discover": 50, "max_fetch": 20}',
       NULL, NULL, NOW(6), NOW(6)
FROM companies c
WHERE c.normalized_name = '센드버드'
  AND NOT EXISTS (
    SELECT 1 FROM company_sources cs
    WHERE cs.company_id = c.id
      AND cs.source_type = 'OFFICIAL_CAREER'
      AND cs.source_url  = 'https://sendbird.com/ko/careers'
  );

-- =============================================================================
-- POST-MIGRATION VERIFICATION QUERIES
-- =============================================================================
--
-- 1. company별 source 수 (expected: 18 rows, each company_name appears once)
--
-- SELECT co.canonical_name, COUNT(cs.id) AS source_count
-- FROM   company_sources cs
-- JOIN   companies co ON co.id = cs.company_id
-- WHERE  cs.source_type = 'OFFICIAL_CAREER'
-- GROUP  BY co.canonical_name
-- ORDER  BY co.canonical_name;
--
-- 2. 중복 source URL (expected: empty)
--
-- SELECT company_id, source_type, source_url, COUNT(*) AS cnt
-- FROM   company_sources
-- WHERE  source_url IS NOT NULL
-- GROUP  BY company_id, source_type, source_url
-- HAVING cnt > 1;
--
-- 3. parser_key 누락 CUSTOM source (expected: empty)
--
-- SELECT id, company_id, source_url, config_json
-- FROM   company_sources
-- WHERE  adapter_type = 'CUSTOM'
--   AND  (config_json IS NULL
--         OR JSON_UNQUOTE(JSON_EXTRACT(config_json, '$.parser_key')) IS NULL);
--
-- 4. ACTIVE source가 없는지 확인 (expected: 0)
--
-- SELECT COUNT(*) AS active_count
-- FROM   company_sources
-- WHERE  status = 'ACTIVE';
--
-- 5. parser_key 분포 (expected: 16 distinct keys across 18 rows)
--
-- SELECT JSON_UNQUOTE(JSON_EXTRACT(config_json, '$.parser_key')) AS parser_key,
--        GROUP_CONCAT(co.canonical_name ORDER BY co.canonical_name) AS companies
-- FROM   company_sources cs
-- JOIN   companies co ON co.id = cs.company_id
-- WHERE  cs.adapter_type = 'CUSTOM'
-- GROUP  BY parser_key
-- ORDER  BY parser_key;
