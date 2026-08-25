-- V19__activate_verified_official_career_sources.sql
-- 검증된 13개 공식 채용 source를 PENDING → ACTIVE 전환.
--
-- 대상 및 근거:
--
--   [LG_CAREERS] careers.lg.com/apply — 공개 JSON API, 인증 불필요
--     LG전자        (LGE)  : discovered=5  parsed=5  live 2026-08-25
--     LG유플러스     (LGU)  : discovered=8  parsed=8  live 2026-08-25
--     LG에너지솔루션 (LGES) : discovered=3  parsed=3  live 2026-08-25
--     LG이노텍       (LGIT) : discovered=1  parsed=1  live 2026-08-25
--
--   [SAMSUNG_CAREERS] samsungcareers.com/hr/ — 공개 POST+HTML, 인증 불필요
--     삼성디스플레이 (C90)  : discovered=1  parsed=1  live 2026-08-25
--     삼성SDI        (C31)  : discovered=1  parsed=1  live 2026-08-25
--     삼성생명       (E11)  : discovered=0  parsed=0  정상 빈 응답 (ACTIVE 기준 충족)
--     삼성화재       (E21)  : discovered=0  parsed=0  정상 빈 응답 (ACTIVE 기준 충족)
--     삼성카드       (E31)  : discovered=0  parsed=0  정상 빈 응답 (ACTIVE 기준 충족)
--     삼성증권       (E40)  : discovered=0  parsed=0  정상 빈 응답 (ACTIVE 기준 충족)
--
--   삼성 금융4사 ACTIVE 근거:
--     · 공식 com_code(E11/E21/E31/E40)가 Samsung Careers UI에서 확인됨
--     · HTTP 200 + divCnt data-value="0" — 차단·schema 오류·shell 응답 아님
--     · 동일 SAMSUNG_CAREERS parser가 C90/C31에서 실제 공고 live 검증됨
--     · 각 회사 fixture에서 compCd 계약 검증됨
--
--   [SK_CAREERS] skcareers.com/Recruit — 공개 form POST, 인증 불필요
--     SK텔레콤  (10005) : discovered=9  parsed=9  live 2026-08-25
--     SK하이닉스 (10004) : discovered=2  parsed=2  live 2026-08-25
--
--   [HYUNDAI_MOTOR_CAREERS] talent.hyundai.com — 공개 GET JSON API, NetFUNNEL 미적용
--     현대자동차         : discovered=22 parsed=22 live 2026-08-25
--
-- 기존 ACTIVE 유지 (이번 migration에서 변경 없음):
--   삼성전자  (V13 ACTIVE) — live 재확인: discovered=3  parsed=3
--   삼성SDS   (V13 ACTIVE) — live 재확인: discovered=1  parsed=1
--   LG CNS    (V12 ACTIVE)
--   에이블리   (V12 ACTIVE)
--   카카오    (V12 ACTIVE)
--
-- Safety: 각 UPDATE는 normalized_name + source_url + parser_key + PENDING 조건
--   으로 범위를 한정하여 다른 source에 영향을 주지 않는다.
--   기존 migration(V10–V18)은 수정하지 않는다.

-- ═══════════════════════════════════════════════════════════════════════════════
-- LG_CAREERS — careers.lg.com/apply
-- ═══════════════════════════════════════════════════════════════════════════════

-- ── LG전자 (LGE) ─────────────────────────────────────────────────────────────
UPDATE company_sources cs
JOIN companies co ON co.id = cs.company_id
SET cs.status = 'ACTIVE', cs.updated_at = NOW(6)
WHERE co.normalized_name = 'lg전자'
  AND cs.source_url = 'https://careers.lg.com/apply'
  AND JSON_UNQUOTE(JSON_EXTRACT(cs.config_json, '$.parser_key')) = 'LG_CAREERS'
  AND cs.status = 'PENDING';

-- ── LG유플러스 (LGU) ──────────────────────────────────────────────────────────
UPDATE company_sources cs
JOIN companies co ON co.id = cs.company_id
SET cs.status = 'ACTIVE', cs.updated_at = NOW(6)
WHERE co.normalized_name = 'lg유플러스'
  AND cs.source_url = 'https://careers.lg.com/apply'
  AND JSON_UNQUOTE(JSON_EXTRACT(cs.config_json, '$.parser_key')) = 'LG_CAREERS'
  AND cs.status = 'PENDING';

-- ── LG에너지솔루션 (LGES) ─────────────────────────────────────────────────────
UPDATE company_sources cs
JOIN companies co ON co.id = cs.company_id
SET cs.status = 'ACTIVE', cs.updated_at = NOW(6)
WHERE co.normalized_name = 'lg에너지솔루션'
  AND cs.source_url = 'https://careers.lg.com/apply'
  AND JSON_UNQUOTE(JSON_EXTRACT(cs.config_json, '$.parser_key')) = 'LG_CAREERS'
  AND cs.status = 'PENDING';

-- ── LG이노텍 (LGIT) ───────────────────────────────────────────────────────────
UPDATE company_sources cs
JOIN companies co ON co.id = cs.company_id
SET cs.status = 'ACTIVE', cs.updated_at = NOW(6)
WHERE co.normalized_name = 'lg이노텍'
  AND cs.source_url = 'https://careers.lg.com/apply'
  AND JSON_UNQUOTE(JSON_EXTRACT(cs.config_json, '$.parser_key')) = 'LG_CAREERS'
  AND cs.status = 'PENDING';

-- ═══════════════════════════════════════════════════════════════════════════════
-- SAMSUNG_CAREERS — samsungcareers.com/hr/
-- ═══════════════════════════════════════════════════════════════════════════════

-- ── 삼성디스플레이 (C90) ──────────────────────────────────────────────────────
UPDATE company_sources cs
JOIN companies co ON co.id = cs.company_id
SET cs.status = 'ACTIVE', cs.updated_at = NOW(6)
WHERE co.normalized_name = '삼성디스플레이'
  AND cs.source_url = 'https://www.samsungcareers.com/hr/'
  AND JSON_UNQUOTE(JSON_EXTRACT(cs.config_json, '$.parser_key')) = 'SAMSUNG_CAREERS'
  AND cs.status = 'PENDING';

-- ── 삼성SDI (C31) ─────────────────────────────────────────────────────────────
UPDATE company_sources cs
JOIN companies co ON co.id = cs.company_id
SET cs.status = 'ACTIVE', cs.updated_at = NOW(6)
WHERE co.normalized_name = '삼성sdi'
  AND cs.source_url = 'https://www.samsungcareers.com/hr/'
  AND JSON_UNQUOTE(JSON_EXTRACT(cs.config_json, '$.parser_key')) = 'SAMSUNG_CAREERS'
  AND cs.status = 'PENDING';

-- ── 삼성생명 (E11) ────────────────────────────────────────────────────────────
UPDATE company_sources cs
JOIN companies co ON co.id = cs.company_id
SET cs.status = 'ACTIVE', cs.updated_at = NOW(6)
WHERE co.normalized_name = '삼성생명'
  AND cs.source_url = 'https://www.samsungcareers.com/hr/'
  AND JSON_UNQUOTE(JSON_EXTRACT(cs.config_json, '$.parser_key')) = 'SAMSUNG_CAREERS'
  AND cs.status = 'PENDING';

-- ── 삼성화재 (E21) ────────────────────────────────────────────────────────────
UPDATE company_sources cs
JOIN companies co ON co.id = cs.company_id
SET cs.status = 'ACTIVE', cs.updated_at = NOW(6)
WHERE co.normalized_name = '삼성화재'
  AND cs.source_url = 'https://www.samsungcareers.com/hr/'
  AND JSON_UNQUOTE(JSON_EXTRACT(cs.config_json, '$.parser_key')) = 'SAMSUNG_CAREERS'
  AND cs.status = 'PENDING';

-- ── 삼성카드 (E31) ────────────────────────────────────────────────────────────
UPDATE company_sources cs
JOIN companies co ON co.id = cs.company_id
SET cs.status = 'ACTIVE', cs.updated_at = NOW(6)
WHERE co.normalized_name = '삼성카드'
  AND cs.source_url = 'https://www.samsungcareers.com/hr/'
  AND JSON_UNQUOTE(JSON_EXTRACT(cs.config_json, '$.parser_key')) = 'SAMSUNG_CAREERS'
  AND cs.status = 'PENDING';

-- ── 삼성증권 (E40) ────────────────────────────────────────────────────────────
UPDATE company_sources cs
JOIN companies co ON co.id = cs.company_id
SET cs.status = 'ACTIVE', cs.updated_at = NOW(6)
WHERE co.normalized_name = '삼성증권'
  AND cs.source_url = 'https://www.samsungcareers.com/hr/'
  AND JSON_UNQUOTE(JSON_EXTRACT(cs.config_json, '$.parser_key')) = 'SAMSUNG_CAREERS'
  AND cs.status = 'PENDING';

-- ═══════════════════════════════════════════════════════════════════════════════
-- SK_CAREERS — skcareers.com/Recruit
-- ═══════════════════════════════════════════════════════════════════════════════

-- ── SK텔레콤 (10005) ──────────────────────────────────────────────────────────
UPDATE company_sources cs
JOIN companies co ON co.id = cs.company_id
SET cs.status = 'ACTIVE', cs.updated_at = NOW(6)
WHERE co.normalized_name = 'sk텔레콤'
  AND cs.source_url = 'https://www.skcareers.com/Recruit'
  AND JSON_UNQUOTE(JSON_EXTRACT(cs.config_json, '$.parser_key')) = 'SK_CAREERS'
  AND cs.status = 'PENDING';

-- ── SK하이닉스 (10004) ────────────────────────────────────────────────────────
UPDATE company_sources cs
JOIN companies co ON co.id = cs.company_id
SET cs.status = 'ACTIVE', cs.updated_at = NOW(6)
WHERE co.normalized_name = 'sk하이닉스'
  AND cs.source_url = 'https://www.skcareers.com/Recruit'
  AND JSON_UNQUOTE(JSON_EXTRACT(cs.config_json, '$.parser_key')) = 'SK_CAREERS'
  AND cs.status = 'PENDING';

-- ═══════════════════════════════════════════════════════════════════════════════
-- HYUNDAI_MOTOR_CAREERS — talent.hyundai.com
-- ═══════════════════════════════════════════════════════════════════════════════

-- ── 현대자동차 ────────────────────────────────────────────────────────────────
UPDATE company_sources cs
JOIN companies co ON co.id = cs.company_id
SET cs.status = 'ACTIVE', cs.updated_at = NOW(6)
WHERE co.normalized_name = '현대자동차'
  AND cs.source_url = 'https://talent.hyundai.com/theme/hall.hc'
  AND JSON_UNQUOTE(JSON_EXTRACT(cs.config_json, '$.parser_key')) = 'HYUNDAI_MOTOR_CAREERS'
  AND cs.status = 'PENDING';

-- =============================================================================
-- POST-MIGRATION VERIFICATION QUERIES
-- =============================================================================
--
-- 1. 13건 ACTIVE 전환 확인
--
-- SELECT co.canonical_name,
--        JSON_UNQUOTE(JSON_EXTRACT(cs.config_json, '$.parser_key')) AS parser_key,
--        cs.status
-- FROM   company_sources cs
-- JOIN   companies co ON co.id = cs.company_id
-- WHERE  JSON_UNQUOTE(JSON_EXTRACT(cs.config_json, '$.parser_key'))
--        IN ('LG_CAREERS','SAMSUNG_CAREERS','SK_CAREERS','HYUNDAI_MOTOR_CAREERS')
-- ORDER  BY parser_key, co.canonical_name;
--
-- Expected: 13 rows (4 LG + 6 Samsung + 2 SK + 1 Hyundai), all ACTIVE
--
-- 2. 기존 ACTIVE source 유지 확인 (삼성전자, 삼성SDS)
--
-- SELECT co.canonical_name, cs.status
-- FROM   company_sources cs
-- JOIN   companies co ON co.id = cs.company_id
-- WHERE  co.normalized_name IN ('삼성전자','삼성sds')
--   AND  JSON_UNQUOTE(JSON_EXTRACT(cs.config_json, '$.parser_key')) = 'SAMSUNG_CAREERS';
-- Expected: both ACTIVE (unchanged from V13)
