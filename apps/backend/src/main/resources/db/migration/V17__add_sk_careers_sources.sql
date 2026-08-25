-- V17__add_sk_careers_sources.sql
-- SK Careers 어댑터(SK_CAREERS)를 사용하는 2개 회사 소스 추가.
--
-- 대상 회사 (normalized_name 기준, V5에서 이미 존재):
--   sk텔레콤   corp_code=10005 / expected_corp_name="SK telecom"
--   sk하이닉스  corp_code=10004 / expected_corp_name="SK hynix"
--
-- API (인증 불필요):
--   POST https://www.skcareers.com/Recruit/GetRecruitList
--     form data: sort, searchText, corpCode, jobRole, recruitType,
--                workingType, workingRegion
--     → JSON: {success, totalCount, list: [{noticeID, title, corpName, ...}]}
--
-- corpCode 출처 (공개 API, 인증 불필요):
--   POST https://www.skcareers.com/Recruit/GetAutocomplete
--   body: type=CorpCode
--   → [{Key: "10005", Value: "SK telecom"}, ...]
--
-- External ID: noticeID 문자열 (예: "R261849")
-- Source URL: https://www.skcareers.com/Recruit/Detail/{noticeID}
--
-- Live 확인 (2026-08-25):
--   10005 = SK telecom (SK텔레콤): 9건 활성 공고 확인 (HTTP 200 JSON)
--   10004 = SK hynix  (SK하이닉스): 2건 활성 공고 확인 (HTTP 200 JSON)
--
-- Status: PENDING — 운영 수집 확인 후 ACTIVE 전환.
-- Idempotency: NOT EXISTS (company_id + source_type + source_url) 복합 조건.

-- ── SK텔레콤 (corp_code=10005) ────────────────────────────────────────────────
INSERT INTO company_sources (
    company_id, source_type, source_url, adapter_type, status,
    config_json, last_verified_at, last_collected_at, created_at, updated_at
)
SELECT
    c.id,
    'OFFICIAL_CAREER',
    'https://www.skcareers.com/Recruit',
    'CUSTOM',
    'PENDING',
    '{"parser_key": "SK_CAREERS", "corp_code": "10005", "expected_corp_name": "SK telecom", "max_fetch": 50}',
    NULL, NULL, NOW(6), NOW(6)
FROM companies c
WHERE c.normalized_name = 'sk텔레콤'
  AND NOT EXISTS (
    SELECT 1 FROM company_sources cs
    WHERE cs.company_id = c.id
      AND cs.source_type = 'OFFICIAL_CAREER'
      AND cs.source_url  = 'https://www.skcareers.com/Recruit'
  );

-- ── SK하이닉스 (corp_code=10004) ──────────────────────────────────────────────
INSERT INTO company_sources (
    company_id, source_type, source_url, adapter_type, status,
    config_json, last_verified_at, last_collected_at, created_at, updated_at
)
SELECT
    c.id,
    'OFFICIAL_CAREER',
    'https://www.skcareers.com/Recruit',
    'CUSTOM',
    'PENDING',
    '{"parser_key": "SK_CAREERS", "corp_code": "10004", "expected_corp_name": "SK hynix", "max_fetch": 50}',
    NULL, NULL, NOW(6), NOW(6)
FROM companies c
WHERE c.normalized_name = 'sk하이닉스'
  AND NOT EXISTS (
    SELECT 1 FROM company_sources cs
    WHERE cs.company_id = c.id
      AND cs.source_type = 'OFFICIAL_CAREER'
      AND cs.source_url  = 'https://www.skcareers.com/Recruit'
  );

-- =============================================================================
-- POST-MIGRATION VERIFICATION QUERIES
-- =============================================================================
--
-- 1. Confirm 2 new rows inserted (PENDING)
--
-- SELECT co.canonical_name, cs.status,
--        JSON_UNQUOTE(JSON_EXTRACT(cs.config_json, '$.corp_code')) AS corp_code
-- FROM   company_sources cs
-- JOIN   companies co ON co.id = cs.company_id
-- WHERE  JSON_UNQUOTE(JSON_EXTRACT(cs.config_json, '$.parser_key')) = 'SK_CAREERS'
-- ORDER  BY co.canonical_name;
-- Expected: 2 rows (SK텔레콤, SK하이닉스) both PENDING
