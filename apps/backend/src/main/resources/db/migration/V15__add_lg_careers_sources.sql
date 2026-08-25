-- V15__add_lg_careers_sources.sql
-- LG Careers API (LG_CAREERS 파서)를 사용하는 4개 관계사 소스 등록.
--
-- 대상 회사 (normalized_name 기준):
--   lg전자  (LGE)  — V5에서 이미 존재
--   lg유플러스 (LGU) — V5에서 이미 존재
--   lg에너지솔루션 (LGES) — V14에서 추가
--   lg이노텍 (LGIT) — V14에서 추가
--
-- API (LG_CNS_CAREERS 와 동일한 엔드포인트):
--   목록: POST https://api.careers.lg.com/rmk/job/retrieveJobNoticesList
--   상세: POST https://api.careers.lg.com/rmk/job/retrieveJobNoticesDetail
--
-- 혼입 방지:
--   config_json.company_code — 목록 단계 companyCode 필터
--   config_json.expected_company_name — 상세 단계 companyName 재검증
--
-- 기존 LG_CNS_CAREERS 소스와 충돌 없음:
--   parser_key="LG_CAREERS" vs "LG_CNS_CAREERS" — 별도 레지스트리 키.
--
-- Idempotency: NOT EXISTS (company_id + source_type + source_url) 복합 조건.
-- Status: PENDING — 실제 수집 시작 전 운영 확인 후 ACTIVE 전환 필요.

-- ── LG전자 (LGE) ──────────────────────────────────────────────────────────────
INSERT INTO company_sources (
    company_id, source_type, source_url, adapter_type, status,
    config_json, last_verified_at, last_collected_at, created_at, updated_at
)
SELECT
    c.id,
    'OFFICIAL_CAREER',
    'https://careers.lg.com/apply',
    'CUSTOM',
    'PENDING',
    '{"parser_key": "LG_CAREERS", "company_code": "LGE", "expected_company_name": "LG전자", "max_discover": 50, "max_fetch": 30}',
    NULL, NULL, NOW(6), NOW(6)
FROM companies c
WHERE c.normalized_name = 'lg전자'
  AND NOT EXISTS (
    SELECT 1 FROM company_sources cs
    WHERE cs.company_id = c.id
      AND cs.source_type = 'OFFICIAL_CAREER'
      AND cs.source_url  = 'https://careers.lg.com/apply'
  );

-- ── LG유플러스 (LGU) ──────────────────────────────────────────────────────────
INSERT INTO company_sources (
    company_id, source_type, source_url, adapter_type, status,
    config_json, last_verified_at, last_collected_at, created_at, updated_at
)
SELECT
    c.id,
    'OFFICIAL_CAREER',
    'https://careers.lg.com/apply',
    'CUSTOM',
    'PENDING',
    '{"parser_key": "LG_CAREERS", "company_code": "LGU", "expected_company_name": "LG유플러스", "max_discover": 50, "max_fetch": 30}',
    NULL, NULL, NOW(6), NOW(6)
FROM companies c
WHERE c.normalized_name = 'lg유플러스'
  AND NOT EXISTS (
    SELECT 1 FROM company_sources cs
    WHERE cs.company_id = c.id
      AND cs.source_type = 'OFFICIAL_CAREER'
      AND cs.source_url  = 'https://careers.lg.com/apply'
  );

-- ── LG에너지솔루션 (LGES) ─────────────────────────────────────────────────────
INSERT INTO company_sources (
    company_id, source_type, source_url, adapter_type, status,
    config_json, last_verified_at, last_collected_at, created_at, updated_at
)
SELECT
    c.id,
    'OFFICIAL_CAREER',
    'https://careers.lg.com/apply',
    'CUSTOM',
    'PENDING',
    '{"parser_key": "LG_CAREERS", "company_code": "LGES", "expected_company_name": "LG에너지솔루션", "max_discover": 50, "max_fetch": 30}',
    NULL, NULL, NOW(6), NOW(6)
FROM companies c
WHERE c.normalized_name = 'lg에너지솔루션'
  AND NOT EXISTS (
    SELECT 1 FROM company_sources cs
    WHERE cs.company_id = c.id
      AND cs.source_type = 'OFFICIAL_CAREER'
      AND cs.source_url  = 'https://careers.lg.com/apply'
  );

-- ── LG이노텍 (LGIT) ───────────────────────────────────────────────────────────
INSERT INTO company_sources (
    company_id, source_type, source_url, adapter_type, status,
    config_json, last_verified_at, last_collected_at, created_at, updated_at
)
SELECT
    c.id,
    'OFFICIAL_CAREER',
    'https://careers.lg.com/apply',
    'CUSTOM',
    'PENDING',
    '{"parser_key": "LG_CAREERS", "company_code": "LGIT", "expected_company_name": "LG이노텍", "max_discover": 50, "max_fetch": 30}',
    NULL, NULL, NOW(6), NOW(6)
FROM companies c
WHERE c.normalized_name = 'lg이노텍'
  AND NOT EXISTS (
    SELECT 1 FROM company_sources cs
    WHERE cs.company_id = c.id
      AND cs.source_type = 'OFFICIAL_CAREER'
      AND cs.source_url  = 'https://careers.lg.com/apply'
  );

-- =============================================================================
-- POST-MIGRATION VERIFICATION QUERIES
-- =============================================================================
--
-- 1. Confirm 4 new rows inserted (LG전자, LG유플러스, LG에너지솔루션, LG이노텍)
--
-- SELECT co.canonical_name, cs.status,
--        JSON_UNQUOTE(JSON_EXTRACT(cs.config_json, '$.company_code')) AS code
-- FROM   company_sources cs
-- JOIN   companies co ON co.id = cs.company_id
-- WHERE  JSON_UNQUOTE(JSON_EXTRACT(cs.config_json, '$.parser_key')) = 'LG_CAREERS'
-- ORDER  BY co.canonical_name;
-- Expected: 4 rows, all PENDING
--
-- 2. Confirm LG_CNS_CAREERS source is unaffected
--
-- SELECT co.canonical_name, cs.status
-- FROM   company_sources cs
-- JOIN   companies co ON co.id = cs.company_id
-- WHERE  JSON_UNQUOTE(JSON_EXTRACT(cs.config_json, '$.parser_key')) = 'LG_CNS_CAREERS';
-- Expected: 1 row (LG CNS), ACTIVE
