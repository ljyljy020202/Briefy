-- V10__add_samsung_careers_sources.sql
-- Adds SAMSUNG_CAREERS company_sources for 삼성전자 and 삼성SDS.
--
-- Both rows:
--   source_type = 'OFFICIAL_CAREER'
--   adapter_type = 'CUSTOM'
--   status       = 'PENDING'   ← never fetched until activated to ACTIVE
--   parser_key   = 'SAMSUNG_CAREERS'
--
-- Confirmed company code mapping (samsungcareers.com /subsid/detail/{code}):
--   C10CAA = 삼성전자 DX부문
--   C10CAH = 삼성전자 DS부문
--   C60    = 삼성SDS
--
-- Investigation status (2026-08-17):
--   The listing API (POST /hr/list.data) works — no WAF, no auth.
--   0 active postings for C10CAA, C10CAH, C60 at seed time.
--   API path is stable (verified via C31/삼성SDI filter).
--   Activate to ACTIVE once postings appear.
--
-- samsungcareers.com was originally excluded from V6 as a group portal.
-- This migration adds only the two companies with company_id registered
-- in V5 (삼성전자, 삼성sds). Other Samsung subsidiaries are out of scope.

-- =============================================================================
-- 1. SAMSUNG_CAREERS — 삼성전자 (DX부문 + DS부문)
-- =============================================================================

INSERT INTO company_sources (
    company_id, source_type, source_url, adapter_type, status,
    config_json, last_verified_at, last_collected_at, created_at, updated_at
)
SELECT
    c.id,
    'OFFICIAL_CAREER',
    'https://www.samsungcareers.com/hr/',
    'CUSTOM',
    'PENDING',
    '{"parser_key": "SAMSUNG_CAREERS", "com_codes": ["C10CAA", "C10CAH"], "max_discover": 50, "max_fetch": 20}',
    NULL, NULL, NOW(6), NOW(6)
FROM companies c
WHERE c.normalized_name = '삼성전자'
  AND NOT EXISTS (
    SELECT 1 FROM company_sources cs
    WHERE cs.company_id = c.id
      AND cs.source_type = 'OFFICIAL_CAREER'
      AND cs.source_url  = 'https://www.samsungcareers.com/hr/'
  );

-- =============================================================================
-- 2. SAMSUNG_CAREERS — 삼성SDS
-- =============================================================================

INSERT INTO company_sources (
    company_id, source_type, source_url, adapter_type, status,
    config_json, last_verified_at, last_collected_at, created_at, updated_at
)
SELECT
    c.id,
    'OFFICIAL_CAREER',
    'https://www.samsungcareers.com/hr/',
    'CUSTOM',
    'PENDING',
    '{"parser_key": "SAMSUNG_CAREERS", "com_codes": ["C60"], "max_discover": 50, "max_fetch": 20}',
    NULL, NULL, NOW(6), NOW(6)
FROM companies c
WHERE c.normalized_name = '삼성sds'
  AND NOT EXISTS (
    SELECT 1 FROM company_sources cs
    WHERE cs.company_id = c.id
      AND cs.source_type = 'OFFICIAL_CAREER'
      AND cs.source_url  = 'https://www.samsungcareers.com/hr/'
  );

-- =============================================================================
-- POST-MIGRATION VERIFICATION QUERIES
-- =============================================================================
--
-- 1. Confirm 2 new rows inserted (삼성전자 + 삼성SDS)
--
-- SELECT co.canonical_name, cs.status, cs.config_json
-- FROM   company_sources cs
-- JOIN   companies co ON co.id = cs.company_id
-- WHERE  JSON_UNQUOTE(JSON_EXTRACT(cs.config_json, '$.parser_key')) = 'SAMSUNG_CAREERS'
-- ORDER  BY co.canonical_name;
--
-- 2. Confirm both are PENDING (expected: 0 ACTIVE)
--
-- SELECT COUNT(*) AS active_samsung
-- FROM   company_sources cs
-- JOIN   companies co ON co.id = cs.company_id
-- WHERE  JSON_UNQUOTE(JSON_EXTRACT(cs.config_json, '$.parser_key')) = 'SAMSUNG_CAREERS'
--   AND  cs.status = 'ACTIVE';
