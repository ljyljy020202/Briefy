-- V11__add_lg_cns_careers_source.sql
-- Adds LG_CNS_CAREERS company_source for LG CNS (company_id=27).
--
-- Source:
--   API: https://api.careers.lg.com/rmk/job/retrieveJobNoticesList
--   Filter: companyCodeList: ["CNS"]
--   SPA: https://careers.lg.com
--
-- robots.txt (careers.lg.com):
--   User-agent: * — Briefy-Agent/1.0 허용.
--   ClaudeBot Disallow: / 는 Briefy-Agent/1.0 에 미적용.
--
-- Investigation (2026-08-17):
--   24 active LG CNS postings confirmed via POST /rmk/job/retrieveJobNoticesList.
--   companyCodeList: ["CNS"] 필터 확인; companyName="LG CNS" 이중 검증 구현됨.
--   상세 조회 POST /rmk/job/retrieveJobNoticesDetail → recList(역할/위치) 포함.
--   인증·세션 불필요. 단일 응답(페이지네이션 없음, listCount=24).
--   경력 타입: A=신입 B=경력 C=인턴 D=신입·경력 E=산학장학생.
--   위치: recList[].locationName (예: "마곡 및 기타").
--   역할: recList[].jobGroupName — IT서비스 대분류 제외, 세부 직군만 사용.
--
-- Status: PENDING — 활성 공고 확인 완료; 운영 수집 시작 전 ACTIVE 전환 필요.
-- LG 관계사 혼입 방지: companyCode != "CNS" 및 companyName != "LG CNS" 건 skip.

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
    '{"parser_key": "LG_CNS_CAREERS", "max_discover": 50, "max_fetch": 30}',
    NULL, NULL, NOW(6), NOW(6)
FROM companies c
WHERE c.normalized_name = 'lg cns'
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
-- 1. Confirm 1 new row inserted (LG CNS)
--
-- SELECT co.canonical_name, cs.status, cs.config_json
-- FROM   company_sources cs
-- JOIN   companies co ON co.id = cs.company_id
-- WHERE  JSON_UNQUOTE(JSON_EXTRACT(cs.config_json, '$.parser_key')) = 'LG_CNS_CAREERS'
-- ORDER  BY co.canonical_name;
--
-- 2. Confirm status is PENDING
--
-- SELECT COUNT(*) AS active_lg_cns
-- FROM   company_sources cs
-- JOIN   companies co ON co.id = cs.company_id
-- WHERE  JSON_UNQUOTE(JSON_EXTRACT(cs.config_json, '$.parser_key')) = 'LG_CNS_CAREERS'
--   AND  cs.status = 'ACTIVE';
-- Expected: 0
