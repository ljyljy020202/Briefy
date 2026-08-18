-- V12__activate_verified_official_sources.sql
-- 검증 완료된 공식 채용 소스를 PENDING → ACTIVE 전환.
--
-- 활성화 기준:
--   1. live smoke 통과 (도달 가능, 1건 이상 발견, 샘플 파싱 성공)
--   2. companyName / 법인 필터 동작 확인
--   3. 최소 1건의 실제 공고 발견
--   4. robots.txt 준수 확인
--
-- 활성화 대상 (2026-08-17):
--   ABLY_CAREERS  — 51건, 에이블리 단독, robots.txt User-agent: * 허용
--   KAKAO_CAREERS — 8건, 카카오 본사 (company=KAKAO 필터), robots.txt 허용
--   LG_CNS_CAREERS — 24건, LG CNS 단독 (companyCode=CNS 필터), robots.txt 허용
--
-- 보류 (PENDING 유지):
--   SAMSUNG_CAREERS(삼성전자) — 0건 (C10CAA, C10CAH에 현재 공고 없음)
--   SAMSUNG_CAREERS(삼성SDS)  — 0건 (C60에 현재 공고 없음)
--
-- 이미 ACTIVE (변경 없음):
--   NAVER_CAREERS, TOSS_CAREERS, DAANGN_CAREERS
--
-- V6 migration 수정 없음. 기존 PENDING source를 이 migration에서만 ACTIVE 처리.

-- ── ABLY_CAREERS ─────────────────────────────────────────────────────────────
UPDATE company_sources cs
JOIN companies co ON co.id = cs.company_id
SET cs.status = 'ACTIVE', cs.updated_at = NOW(6)
WHERE co.normalized_name = '에이블리'
  AND JSON_UNQUOTE(JSON_EXTRACT(cs.config_json, '$.parser_key')) = 'ABLY_CAREERS'
  AND cs.status = 'PENDING';

-- ── KAKAO_CAREERS ────────────────────────────────────────────────────────────
UPDATE company_sources cs
JOIN companies co ON co.id = cs.company_id
SET cs.status = 'ACTIVE', cs.updated_at = NOW(6)
WHERE co.normalized_name = '카카오'
  AND JSON_UNQUOTE(JSON_EXTRACT(cs.config_json, '$.parser_key')) = 'KAKAO_CAREERS'
  AND cs.status = 'PENDING';

-- ── LG_CNS_CAREERS ───────────────────────────────────────────────────────────
UPDATE company_sources cs
JOIN companies co ON co.id = cs.company_id
SET cs.status = 'ACTIVE', cs.updated_at = NOW(6)
WHERE co.normalized_name = 'lg cns'
  AND JSON_UNQUOTE(JSON_EXTRACT(cs.config_json, '$.parser_key')) = 'LG_CNS_CAREERS'
  AND cs.status = 'PENDING';

-- =============================================================================
-- POST-MIGRATION VERIFICATION
-- =============================================================================
--
-- SELECT co.canonical_name, cs.status,
--        JSON_UNQUOTE(JSON_EXTRACT(cs.config_json, '$.parser_key')) AS parser_key
-- FROM company_sources cs JOIN companies co ON co.id = cs.company_id
-- WHERE co.id IN (15, 4, 27)
-- ORDER BY co.id;
-- Expected: ABLY_CAREERS=ACTIVE, KAKAO_CAREERS=ACTIVE, LG_CNS_CAREERS=ACTIVE
--
-- SELECT co.canonical_name, cs.status
-- FROM company_sources cs JOIN companies co ON co.id = cs.company_id
-- WHERE JSON_UNQUOTE(JSON_EXTRACT(cs.config_json, '$.parser_key')) = 'SAMSUNG_CAREERS';
-- Expected: 삼성전자=PENDING, 삼성SDS=PENDING
