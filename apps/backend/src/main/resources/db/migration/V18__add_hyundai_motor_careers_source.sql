-- V18__add_hyundai_motor_careers_source.sql
-- Hyundai Motor Careers 어댑터(HYUNDAI_MOTOR_CAREERS)를 사용하는 현대자동차 소스 추가.
--
-- 대상 회사 (normalized_name 기준, V5에서 이미 존재):
--   현대자동차
--
-- API (인증 불필요):
--   List:   GET https://talent.hyundai.com/api/rec/AP-HM-FO-02730
--           params: hgrCd=1, lang=ko, secCode=, jdRecuCate={01|02}, secLoad=Y
--           → JSON: {status, data: {applyList, cnt, themaInfo, fldList, secList}}
--
--   Detail: GET https://talent.hyundai.com/api/rec/AP-HM-FO-02800
--           params: hgrCd=1, lang=ko, recuYy, recuType, recuCls
--           → JSON: {status, data: {applyInfo: {..., privJdDtl, privMustReq}}}
--
-- NetFUNNEL: /apply/applyList.hc 경로에만 적용됨.
--   theme hall API (/api/rec/...) 는 NetFUNNEL 없이 공개 접근 가능.
--
-- Company filter: logoNm == "현대" — 제네시스·계열사 혼입 방지.
--
-- External ID: "{recuYy}_{recuType}_{recuCls}" (예: "2026_N2_295")
-- Source URL: https://talent.hyundai.com/apply/applyView.hc?recuYy=...&recuType=...&recuCls=...
--
-- Live 확인 (2026-08-25):
--   22건 활성 공고 확인 (tab01: 20건, tab02: 2건), 전부 logoNm=현대
--   인증·쿠키·NetFUNNEL 우회 불필요 (HTTP 200 JSON)
--
-- Status: PENDING — 운영 수집 확인 후 ACTIVE 전환.
-- Idempotency: NOT EXISTS (company_id + source_type + source_url) 복합 조건.

-- ── 현대자동차 ────────────────────────────────────────────────────────────────────
INSERT INTO company_sources (
    company_id, source_type, source_url, adapter_type, status,
    config_json, last_verified_at, last_collected_at, created_at, updated_at
)
SELECT
    c.id,
    'OFFICIAL_CAREER',
    'https://talent.hyundai.com/theme/hall.hc',
    'CUSTOM',
    'PENDING',
    '{"parser_key": "HYUNDAI_MOTOR_CAREERS", "max_fetch": 50}',
    NULL, NULL, NOW(6), NOW(6)
FROM companies c
WHERE c.normalized_name = '현대자동차'
  AND NOT EXISTS (
    SELECT 1 FROM company_sources cs
    WHERE cs.company_id = c.id
      AND cs.source_type = 'OFFICIAL_CAREER'
      AND cs.source_url  = 'https://talent.hyundai.com/theme/hall.hc'
  );

-- =============================================================================
-- POST-MIGRATION VERIFICATION QUERIES
-- =============================================================================
--
-- 1. Confirm 1 new row inserted (PENDING)
--
-- SELECT co.canonical_name, cs.status, cs.source_url,
--        JSON_UNQUOTE(JSON_EXTRACT(cs.config_json, '$.parser_key')) AS parser_key
-- FROM   company_sources cs
-- JOIN   companies co ON co.id = cs.company_id
-- WHERE  JSON_UNQUOTE(JSON_EXTRACT(cs.config_json, '$.parser_key'))
--        = 'HYUNDAI_MOTOR_CAREERS';
-- Expected: 1 row (현대자동차) PENDING
