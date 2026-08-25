-- V16__add_samsung_subsidiary_sources.sql
-- Samsung Careers 어댑터(SAMSUNG_CAREERS)를 사용하는 6개 계열사 소스 추가.
--
-- 대상 회사 (company_id는 하드코딩 없이 normalized_name 조회):
--   삼성디스플레이 (C90)   — V14에서 등록
--   삼성SDI        (C31)   — V14에서 등록
--   삼성생명       (E11)   — V14에서 등록
--   삼성화재       (E21)   — V14에서 등록
--   삼성카드       (E31)   — V14에서 등록
--   삼성증권       (E40)   — V14에서 등록
--
-- 기존 소스 유지:
--   삼성전자 (C10CAA, C10CAH) — V10에서 추가, V13에서 ACTIVE
--   삼성SDS  (C60)            — V10에서 추가, V13에서 ACTIVE
--
-- API:
--   POST https://www.samsungcareers.com/hr/list.data (strCompany[]={com_code})
--   GET  https://www.samsungcareers.com/recruit/detail.data?seqno={seq}
--   compCd 검증: detail.result.compCd 가 com_codes 에 포함되는지 재확인
--
-- Live 확인 (2026-08-25):
--   C90 = 삼성디스플레이: 1건 활성 공고 확인 (HTTP 200 + 정상 HTML)
--   C31 = 삼성SDI:        1건 활성 공고 확인 (HTTP 200 + 정상 HTML)
--   E11 = 삼성생명:       0건 (divCnt data-value="0") — 유효한 빈 응답
--   E21 = 삼성화재:       0건 (divCnt data-value="0") — 유효한 빈 응답
--   E31 = 삼성카드:       0건 (divCnt data-value="0") — 유효한 빈 응답
--   E40 = 삼성증권:       0건 (divCnt data-value="0") — 유효한 빈 응답
--
-- Status: PENDING — 운영 수집 확인 후 ACTIVE 전환.
-- Idempotency: NOT EXISTS (company_id + source_type + source_url) 복합 조건.

-- ── 삼성디스플레이 (C90) ──────────────────────────────────────────────────────
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
    '{"parser_key": "SAMSUNG_CAREERS", "com_codes": ["C90"], "max_discover": 50, "max_fetch": 20}',
    NULL, NULL, NOW(6), NOW(6)
FROM companies c
WHERE c.normalized_name = '삼성디스플레이'
  AND NOT EXISTS (
    SELECT 1 FROM company_sources cs
    WHERE cs.company_id = c.id
      AND cs.source_type = 'OFFICIAL_CAREER'
      AND cs.source_url  = 'https://www.samsungcareers.com/hr/'
  );

-- ── 삼성SDI (C31) ─────────────────────────────────────────────────────────────
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
    '{"parser_key": "SAMSUNG_CAREERS", "com_codes": ["C31"], "max_discover": 50, "max_fetch": 20}',
    NULL, NULL, NOW(6), NOW(6)
FROM companies c
WHERE c.normalized_name = '삼성sdi'
  AND NOT EXISTS (
    SELECT 1 FROM company_sources cs
    WHERE cs.company_id = c.id
      AND cs.source_type = 'OFFICIAL_CAREER'
      AND cs.source_url  = 'https://www.samsungcareers.com/hr/'
  );

-- ── 삼성생명 (E11) ────────────────────────────────────────────────────────────
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
    '{"parser_key": "SAMSUNG_CAREERS", "com_codes": ["E11"], "max_discover": 50, "max_fetch": 20}',
    NULL, NULL, NOW(6), NOW(6)
FROM companies c
WHERE c.normalized_name = '삼성생명'
  AND NOT EXISTS (
    SELECT 1 FROM company_sources cs
    WHERE cs.company_id = c.id
      AND cs.source_type = 'OFFICIAL_CAREER'
      AND cs.source_url  = 'https://www.samsungcareers.com/hr/'
  );

-- ── 삼성화재 (E21) ────────────────────────────────────────────────────────────
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
    '{"parser_key": "SAMSUNG_CAREERS", "com_codes": ["E21"], "max_discover": 50, "max_fetch": 20}',
    NULL, NULL, NOW(6), NOW(6)
FROM companies c
WHERE c.normalized_name = '삼성화재'
  AND NOT EXISTS (
    SELECT 1 FROM company_sources cs
    WHERE cs.company_id = c.id
      AND cs.source_type = 'OFFICIAL_CAREER'
      AND cs.source_url  = 'https://www.samsungcareers.com/hr/'
  );

-- ── 삼성카드 (E31) ────────────────────────────────────────────────────────────
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
    '{"parser_key": "SAMSUNG_CAREERS", "com_codes": ["E31"], "max_discover": 50, "max_fetch": 20}',
    NULL, NULL, NOW(6), NOW(6)
FROM companies c
WHERE c.normalized_name = '삼성카드'
  AND NOT EXISTS (
    SELECT 1 FROM company_sources cs
    WHERE cs.company_id = c.id
      AND cs.source_type = 'OFFICIAL_CAREER'
      AND cs.source_url  = 'https://www.samsungcareers.com/hr/'
  );

-- ── 삼성증권 (E40) ────────────────────────────────────────────────────────────
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
    '{"parser_key": "SAMSUNG_CAREERS", "com_codes": ["E40"], "max_discover": 50, "max_fetch": 20}',
    NULL, NULL, NOW(6), NOW(6)
FROM companies c
WHERE c.normalized_name = '삼성증권'
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
-- 1. Confirm 6 new rows inserted (PENDING)
--
-- SELECT co.canonical_name, cs.status,
--        JSON_UNQUOTE(JSON_EXTRACT(cs.config_json, '$.com_codes')) AS com_codes
-- FROM   company_sources cs
-- JOIN   companies co ON co.id = cs.company_id
-- WHERE  JSON_UNQUOTE(JSON_EXTRACT(cs.config_json, '$.parser_key')) = 'SAMSUNG_CAREERS'
-- ORDER  BY co.canonical_name;
-- Expected: 8 rows total (삼성전자 ACTIVE, 삼성SDS ACTIVE, 6 new PENDING)
--
-- 2. Confirm existing ACTIVE sources are unaffected
--
-- SELECT co.canonical_name, cs.status
-- FROM   company_sources cs
-- JOIN   companies co ON co.id = cs.company_id
-- WHERE  JSON_UNQUOTE(JSON_EXTRACT(cs.config_json, '$.parser_key')) = 'SAMSUNG_CAREERS'
--   AND  cs.status = 'ACTIVE';
-- Expected: 삼성전자, 삼성SDS (both ACTIVE)
