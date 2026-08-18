-- V13__activate_samsung_careers_sources.sql
-- 삼성전자·삼성SDS company_sources를 PENDING → ACTIVE 전환.
--
-- 변경 사유:
--   V12 당시 기준("1건 이상 발견")으로 보류했으나, 공고가 없는 상태에서
--   PENDING을 유지하면 daily_collection이 해당 소스를 건너뛰어 공고가 새로
--   게시돼도 자동 수집이 불가능하다.
--   활성화 기준을 "사이트 도달 가능 + API 정상 응답"으로 완화한다.
--   실제로 POST /hr/list.data 호출 시 HTTP 200 + 정상 HTML 반환이 확인됨.
--   공고가 없으면 수집 결과가 0건으로 끝날 뿐 오류가 발생하지 않는다.

-- ── 삼성전자 ─────────────────────────────────────────────────────────────────
UPDATE company_sources cs
JOIN companies co ON co.id = cs.company_id
SET cs.status = 'ACTIVE', cs.updated_at = NOW(6)
WHERE co.id = 30
  AND JSON_UNQUOTE(JSON_EXTRACT(cs.config_json, '$.parser_key')) = 'SAMSUNG_CAREERS'
  AND cs.status = 'PENDING';

-- ── 삼성SDS ──────────────────────────────────────────────────────────────────
UPDATE company_sources cs
JOIN companies co ON co.id = cs.company_id
SET cs.status = 'ACTIVE', cs.updated_at = NOW(6)
WHERE co.id = 31
  AND JSON_UNQUOTE(JSON_EXTRACT(cs.config_json, '$.parser_key')) = 'SAMSUNG_CAREERS'
  AND cs.status = 'PENDING';

-- =============================================================================
-- POST-MIGRATION VERIFICATION
-- =============================================================================
--
-- SELECT co.canonical_name, cs.status,
--        JSON_UNQUOTE(JSON_EXTRACT(cs.config_json, '$.parser_key')) AS parser_key
-- FROM company_sources cs JOIN companies co ON co.id = cs.company_id
-- WHERE co.id IN (30, 31)
-- ORDER BY co.id;
-- Expected: 삼성전자=ACTIVE, 삼성SDS=ACTIVE
