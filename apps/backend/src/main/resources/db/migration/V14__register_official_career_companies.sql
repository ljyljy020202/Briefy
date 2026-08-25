-- V14__register_official_career_companies.sql
-- Register companies and aliases required for official career site collection.
--
-- Target companies (14):
--   LG그룹: LG전자*, LG유플러스*, LG에너지솔루션, LG이노텍
--   삼성그룹: 삼성전자*, 삼성디스플레이, 삼성SDI, 삼성생명, 삼성화재, 삼성카드, 삼성증권
--   SK그룹: SK텔레콤*, SK하이닉스*
--   현대그룹: 현대자동차*
--
--   (* already seeded in V5 — companies section is skipped via NOT EXISTS guard)
--
-- company_sources are NOT added here; see a subsequent migration.
--
-- industry_codes values follow the Frontend option set used in BriefingService scoring:
--   IT/소프트웨어 | 게임 | 핀테크 | 이커머스 | 의료/헬스케어 | 교육
--   배터리·디스플레이·반도체·보험·카드·증권 제조/금융 계열사는 해당 카테고리 없음 → NULL
--
-- normalized_name / normalized_alias = CompanyNameNormalizer.normalize():
--   name.strip().toLowerCase(Locale.ROOT)
--   Korean unchanged; ASCII letters folded to lower-case.
--
-- Idempotency: every INSERT is guarded by NOT EXISTS on normalized_name / normalized_alias.
-- Running this migration more than once produces no duplicates.

-- =============================================================================
-- 1. COMPANIES
-- =============================================================================

-- ── LG그룹 신규 ──────────────────────────────────────────────────────────────

-- LG전자, LG유플러스 → V5에 이미 존재. NOT EXISTS guard로 자동 skip.

-- normalized_name: 'LG에너지솔루션'.strip().toLowerCase(ROOT) = 'lg에너지솔루션'
-- industry_codes NULL: 배터리/에너지 — Frontend option set 미해당
INSERT INTO companies (canonical_name, normalized_name, company_size, industry_codes, is_active, created_at, updated_at)
SELECT 'LG에너지솔루션', 'lg에너지솔루션', '대기업', NULL, 1, NOW(6), NOW(6) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM companies WHERE normalized_name = 'lg에너지솔루션');

-- normalized_name: 'LG이노텍'.strip().toLowerCase(ROOT) = 'lg이노텍'
-- industry_codes NULL: 전자부품/광학소재 — Frontend option set 미해당
INSERT INTO companies (canonical_name, normalized_name, company_size, industry_codes, is_active, created_at, updated_at)
SELECT 'LG이노텍', 'lg이노텍', '대기업', NULL, 1, NOW(6), NOW(6) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM companies WHERE normalized_name = 'lg이노텍');

-- ── 삼성그룹 신규 ─────────────────────────────────────────────────────────────

-- 삼성전자 → V5에 이미 존재. NOT EXISTS guard로 자동 skip.

-- normalized_name: '삼성디스플레이'.strip().toLowerCase(ROOT) = '삼성디스플레이'
-- industry_codes NULL: 디스플레이 제조 — Frontend option set 미해당
INSERT INTO companies (canonical_name, normalized_name, company_size, industry_codes, is_active, created_at, updated_at)
SELECT '삼성디스플레이', '삼성디스플레이', '대기업', NULL, 1, NOW(6), NOW(6) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM companies WHERE normalized_name = '삼성디스플레이');

-- normalized_name: '삼성SDI'.strip().toLowerCase(ROOT) = '삼성sdi'
-- industry_codes NULL: 배터리/전자재료 — Frontend option set 미해당
INSERT INTO companies (canonical_name, normalized_name, company_size, industry_codes, is_active, created_at, updated_at)
SELECT '삼성SDI', '삼성sdi', '대기업', NULL, 1, NOW(6), NOW(6) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM companies WHERE normalized_name = '삼성sdi');

-- normalized_name: '삼성생명'.strip().toLowerCase(ROOT) = '삼성생명'
-- industry_codes NULL: 보험 — Frontend option set 핀테크는 tech-first 스타트업 맥락으로 부적합
INSERT INTO companies (canonical_name, normalized_name, company_size, industry_codes, is_active, created_at, updated_at)
SELECT '삼성생명', '삼성생명', '대기업', NULL, 1, NOW(6), NOW(6) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM companies WHERE normalized_name = '삼성생명');

-- normalized_name: '삼성화재'.strip().toLowerCase(ROOT) = '삼성화재'
-- industry_codes NULL: 손해보험 — Frontend option set 미해당
INSERT INTO companies (canonical_name, normalized_name, company_size, industry_codes, is_active, created_at, updated_at)
SELECT '삼성화재', '삼성화재', '대기업', NULL, 1, NOW(6), NOW(6) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM companies WHERE normalized_name = '삼성화재');

-- normalized_name: '삼성카드'.strip().toLowerCase(ROOT) = '삼성카드'
-- industry_codes NULL: 카드/여신금융 — Frontend option set 미해당
INSERT INTO companies (canonical_name, normalized_name, company_size, industry_codes, is_active, created_at, updated_at)
SELECT '삼성카드', '삼성카드', '대기업', NULL, 1, NOW(6), NOW(6) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM companies WHERE normalized_name = '삼성카드');

-- normalized_name: '삼성증권'.strip().toLowerCase(ROOT) = '삼성증권'
-- industry_codes NULL: 증권/자본시장 — Frontend option set 미해당
INSERT INTO companies (canonical_name, normalized_name, company_size, industry_codes, is_active, created_at, updated_at)
SELECT '삼성증권', '삼성증권', '대기업', NULL, 1, NOW(6), NOW(6) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM companies WHERE normalized_name = '삼성증권');

-- ── SK그룹, 현대그룹 ──────────────────────────────────────────────────────────

-- SK텔레콤, SK하이닉스, 현대자동차 → V5에 이미 존재. NOT EXISTS guard로 자동 skip.

-- =============================================================================
-- 2. COMPANY ALIASES
-- =============================================================================

-- ── LG전자 ───────────────────────────────────────────────────────────────────

-- normalized_alias: 'LG Electronics'.strip().toLowerCase(ROOT) = 'lg electronics'
INSERT INTO company_aliases (company_id, alias, normalized_alias, created_at)
SELECT c.id, 'LG Electronics', 'lg electronics', NOW(6) FROM companies c
WHERE c.normalized_name = 'lg전자'
  AND NOT EXISTS (SELECT 1 FROM company_aliases WHERE normalized_alias = 'lg electronics');

-- ── LG유플러스 ────────────────────────────────────────────────────────────────

-- normalized_alias: 'LG U+'.strip().toLowerCase(ROOT) = 'lg u+'
INSERT INTO company_aliases (company_id, alias, normalized_alias, created_at)
SELECT c.id, 'LG U+', 'lg u+', NOW(6) FROM companies c
WHERE c.normalized_name = 'lg유플러스'
  AND NOT EXISTS (SELECT 1 FROM company_aliases WHERE normalized_alias = 'lg u+');

-- normalized_alias: 'LGU+'.strip().toLowerCase(ROOT) = 'lgu+'
INSERT INTO company_aliases (company_id, alias, normalized_alias, created_at)
SELECT c.id, 'LGU+', 'lgu+', NOW(6) FROM companies c
WHERE c.normalized_name = 'lg유플러스'
  AND NOT EXISTS (SELECT 1 FROM company_aliases WHERE normalized_alias = 'lgu+');

-- ── LG에너지솔루션 ────────────────────────────────────────────────────────────

-- normalized_alias: 'LG Energy Solution' → 'lg energy solution'
INSERT INTO company_aliases (company_id, alias, normalized_alias, created_at)
SELECT c.id, 'LG Energy Solution', 'lg energy solution', NOW(6) FROM companies c
WHERE c.normalized_name = 'lg에너지솔루션'
  AND NOT EXISTS (SELECT 1 FROM company_aliases WHERE normalized_alias = 'lg energy solution');

-- normalized_alias: 'LGES' → 'lges'
INSERT INTO company_aliases (company_id, alias, normalized_alias, created_at)
SELECT c.id, 'LGES', 'lges', NOW(6) FROM companies c
WHERE c.normalized_name = 'lg에너지솔루션'
  AND NOT EXISTS (SELECT 1 FROM company_aliases WHERE normalized_alias = 'lges');

-- ── LG이노텍 ──────────────────────────────────────────────────────────────────

-- normalized_alias: 'LG Innotek' → 'lg innotek'
INSERT INTO company_aliases (company_id, alias, normalized_alias, created_at)
SELECT c.id, 'LG Innotek', 'lg innotek', NOW(6) FROM companies c
WHERE c.normalized_name = 'lg이노텍'
  AND NOT EXISTS (SELECT 1 FROM company_aliases WHERE normalized_alias = 'lg innotek');

-- ── 삼성전자 ─────────────────────────────────────────────────────────────────

-- normalized_alias: 'Samsung Electronics' → 'samsung electronics'
INSERT INTO company_aliases (company_id, alias, normalized_alias, created_at)
SELECT c.id, 'Samsung Electronics', 'samsung electronics', NOW(6) FROM companies c
WHERE c.normalized_name = '삼성전자'
  AND NOT EXISTS (SELECT 1 FROM company_aliases WHERE normalized_alias = 'samsung electronics');

-- ── 삼성디스플레이 ────────────────────────────────────────────────────────────

-- normalized_alias: 'Samsung Display' → 'samsung display'
INSERT INTO company_aliases (company_id, alias, normalized_alias, created_at)
SELECT c.id, 'Samsung Display', 'samsung display', NOW(6) FROM companies c
WHERE c.normalized_name = '삼성디스플레이'
  AND NOT EXISTS (SELECT 1 FROM company_aliases WHERE normalized_alias = 'samsung display');

-- ── 삼성SDI ───────────────────────────────────────────────────────────────────

-- normalized_alias: 'Samsung SDI' → 'samsung sdi'
INSERT INTO company_aliases (company_id, alias, normalized_alias, created_at)
SELECT c.id, 'Samsung SDI', 'samsung sdi', NOW(6) FROM companies c
WHERE c.normalized_name = '삼성sdi'
  AND NOT EXISTS (SELECT 1 FROM company_aliases WHERE normalized_alias = 'samsung sdi');

-- ── 삼성생명 ─────────────────────────────────────────────────────────────────

-- normalized_alias: 'Samsung Life Insurance' → 'samsung life insurance'
INSERT INTO company_aliases (company_id, alias, normalized_alias, created_at)
SELECT c.id, 'Samsung Life Insurance', 'samsung life insurance', NOW(6) FROM companies c
WHERE c.normalized_name = '삼성생명'
  AND NOT EXISTS (SELECT 1 FROM company_aliases WHERE normalized_alias = 'samsung life insurance');

-- ── 삼성화재 ─────────────────────────────────────────────────────────────────

-- normalized_alias: 'Samsung Fire & Marine Insurance' → 'samsung fire & marine insurance'
INSERT INTO company_aliases (company_id, alias, normalized_alias, created_at)
SELECT c.id, 'Samsung Fire & Marine Insurance', 'samsung fire & marine insurance', NOW(6) FROM companies c
WHERE c.normalized_name = '삼성화재'
  AND NOT EXISTS (SELECT 1 FROM company_aliases WHERE normalized_alias = 'samsung fire & marine insurance');

-- ── 삼성카드 ─────────────────────────────────────────────────────────────────

-- normalized_alias: 'Samsung Card' → 'samsung card'
INSERT INTO company_aliases (company_id, alias, normalized_alias, created_at)
SELECT c.id, 'Samsung Card', 'samsung card', NOW(6) FROM companies c
WHERE c.normalized_name = '삼성카드'
  AND NOT EXISTS (SELECT 1 FROM company_aliases WHERE normalized_alias = 'samsung card');

-- ── 삼성증권 ─────────────────────────────────────────────────────────────────

-- normalized_alias: 'Samsung Securities' → 'samsung securities'
INSERT INTO company_aliases (company_id, alias, normalized_alias, created_at)
SELECT c.id, 'Samsung Securities', 'samsung securities', NOW(6) FROM companies c
WHERE c.normalized_name = '삼성증권'
  AND NOT EXISTS (SELECT 1 FROM company_aliases WHERE normalized_alias = 'samsung securities');

-- ── SK텔레콤 ─────────────────────────────────────────────────────────────────

-- normalized_alias: 'SK Telecom' → 'sk telecom'
INSERT INTO company_aliases (company_id, alias, normalized_alias, created_at)
SELECT c.id, 'SK Telecom', 'sk telecom', NOW(6) FROM companies c
WHERE c.normalized_name = 'sk텔레콤'
  AND NOT EXISTS (SELECT 1 FROM company_aliases WHERE normalized_alias = 'sk telecom');

-- normalized_alias: 'SKT' → 'skt'
INSERT INTO company_aliases (company_id, alias, normalized_alias, created_at)
SELECT c.id, 'SKT', 'skt', NOW(6) FROM companies c
WHERE c.normalized_name = 'sk텔레콤'
  AND NOT EXISTS (SELECT 1 FROM company_aliases WHERE normalized_alias = 'skt');

-- ── SK하이닉스 ────────────────────────────────────────────────────────────────

-- normalized_alias: 'SK hynix' → 'sk hynix'
-- Note: official English name uses lower-case 'h' (sk hynix)
INSERT INTO company_aliases (company_id, alias, normalized_alias, created_at)
SELECT c.id, 'SK hynix', 'sk hynix', NOW(6) FROM companies c
WHERE c.normalized_name = 'sk하이닉스'
  AND NOT EXISTS (SELECT 1 FROM company_aliases WHERE normalized_alias = 'sk hynix');

-- ── 현대자동차 ───────────────────────────────────────────────────────────────

-- normalized_alias: 'Hyundai Motor Company' → 'hyundai motor company'
INSERT INTO company_aliases (company_id, alias, normalized_alias, created_at)
SELECT c.id, 'Hyundai Motor Company', 'hyundai motor company', NOW(6) FROM companies c
WHERE c.normalized_name = '현대자동차'
  AND NOT EXISTS (SELECT 1 FROM company_aliases WHERE normalized_alias = 'hyundai motor company');

-- normalized_alias: 'Hyundai Motor' → 'hyundai motor'
INSERT INTO company_aliases (company_id, alias, normalized_alias, created_at)
SELECT c.id, 'Hyundai Motor', 'hyundai motor', NOW(6) FROM companies c
WHERE c.normalized_name = '현대자동차'
  AND NOT EXISTS (SELECT 1 FROM company_aliases WHERE normalized_alias = 'hyundai motor');
