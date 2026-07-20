-- V5__seed_initial_companies_and_aliases.sql
-- Initial company registry seed — 43 companies, 37 aliases.
--
-- Segments:
--   Service / Platform / Commerce  (22)
--   Large Corp IT / Digital / Mobility (17)
--   Game / Content (4)
--
-- company_size values are taken from the Frontend option set used in onboarding /
-- BriefingService scoring (equalsIgnoreCase):
--   스타트업 | 중소기업 | 중견기업 | 대기업 | 외국계
--   → Set only for companies where classification is unambiguous.
--   → NULL for all others (see report at end of file).
--
-- industry_codes values are taken from the Frontend option set / BriefingService scoring:
--   IT/소프트웨어 | 게임 | 핀테크 | 이커머스 | 의료/헬스케어 | 교육
--   Stored as comma-separated TEXT; only confirmed primary industry is stored.
--   → NULL where no option fits (자동차, 반도체, 전자부품 — see report at end of file).
--
-- normalized_name / normalized_alias = CompanyNameNormalizer.normalize():
--   name.strip().toLowerCase(Locale.ROOT)
--   Korean characters are unchanged; ASCII letters are folded to lower-case.
--
-- ID independence: aliases reference companies via normalized_name subquery.
-- Idempotency: every INSERT is guarded by NOT EXISTS — safe to re-run.
--
-- Excluded aliases (normalized_alias collision — would violate uq_company_aliases_normalized):
--   TOSS        → 'toss'  — same as Toss (토스)         → kept: Toss
--   Naver       → 'naver' — same as NAVER (네이버)      → kept: NAVER
--   KAKAO       → 'kakao' — same as Kakao (카카오)      → kept: Kakao

-- =============================================================================
-- 1. COMPANIES
-- =============================================================================

-- ── Service / Platform / Commerce ────────────────────────────────────────────

INSERT INTO companies (canonical_name, normalized_name, company_size, industry_codes, is_active, created_at, updated_at)
SELECT '네이버', '네이버', '대기업', 'IT/소프트웨어', 1, NOW(6), NOW(6) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM companies WHERE normalized_name = '네이버');

INSERT INTO companies (canonical_name, normalized_name, company_size, industry_codes, is_active, created_at, updated_at)
SELECT '네이버클라우드', '네이버클라우드', NULL, 'IT/소프트웨어', 1, NOW(6), NOW(6) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM companies WHERE normalized_name = '네이버클라우드');

INSERT INTO companies (canonical_name, normalized_name, company_size, industry_codes, is_active, created_at, updated_at)
SELECT '네이버파이낸셜', '네이버파이낸셜', NULL, '핀테크', 1, NOW(6), NOW(6) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM companies WHERE normalized_name = '네이버파이낸셜');

INSERT INTO companies (canonical_name, normalized_name, company_size, industry_codes, is_active, created_at, updated_at)
SELECT '카카오', '카카오', '대기업', 'IT/소프트웨어', 1, NOW(6), NOW(6) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM companies WHERE normalized_name = '카카오');

INSERT INTO companies (canonical_name, normalized_name, company_size, industry_codes, is_active, created_at, updated_at)
SELECT '카카오뱅크', '카카오뱅크', NULL, '핀테크', 1, NOW(6), NOW(6) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM companies WHERE normalized_name = '카카오뱅크');

INSERT INTO companies (canonical_name, normalized_name, company_size, industry_codes, is_active, created_at, updated_at)
SELECT '카카오모빌리티', '카카오모빌리티', NULL, 'IT/소프트웨어', 1, NOW(6), NOW(6) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM companies WHERE normalized_name = '카카오모빌리티');

INSERT INTO companies (canonical_name, normalized_name, company_size, industry_codes, is_active, created_at, updated_at)
SELECT '카카오페이', '카카오페이', NULL, '핀테크', 1, NOW(6), NOW(6) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM companies WHERE normalized_name = '카카오페이');

INSERT INTO companies (canonical_name, normalized_name, company_size, industry_codes, is_active, created_at, updated_at)
SELECT '라인플러스', '라인플러스', NULL, 'IT/소프트웨어', 1, NOW(6), NOW(6) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM companies WHERE normalized_name = '라인플러스');

INSERT INTO companies (canonical_name, normalized_name, company_size, industry_codes, is_active, created_at, updated_at)
SELECT '토스', '토스', NULL, '핀테크', 1, NOW(6), NOW(6) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM companies WHERE normalized_name = '토스');

INSERT INTO companies (canonical_name, normalized_name, company_size, industry_codes, is_active, created_at, updated_at)
SELECT '당근', '당근', NULL, 'IT/소프트웨어', 1, NOW(6), NOW(6) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM companies WHERE normalized_name = '당근');

INSERT INTO companies (canonical_name, normalized_name, company_size, industry_codes, is_active, created_at, updated_at)
SELECT '쿠팡', '쿠팡', NULL, '이커머스', 1, NOW(6), NOW(6) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM companies WHERE normalized_name = '쿠팡');

INSERT INTO companies (canonical_name, normalized_name, company_size, industry_codes, is_active, created_at, updated_at)
SELECT '우아한형제들', '우아한형제들', NULL, 'IT/소프트웨어', 1, NOW(6), NOW(6) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM companies WHERE normalized_name = '우아한형제들');

INSERT INTO companies (canonical_name, normalized_name, company_size, industry_codes, is_active, created_at, updated_at)
SELECT '무신사', '무신사', NULL, '이커머스', 1, NOW(6), NOW(6) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM companies WHERE normalized_name = '무신사');

-- normalized_name: '29CM'.strip().toLowerCase(ROOT) = '29cm'
INSERT INTO companies (canonical_name, normalized_name, company_size, industry_codes, is_active, created_at, updated_at)
SELECT '29CM', '29cm', NULL, '이커머스', 1, NOW(6), NOW(6) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM companies WHERE normalized_name = '29cm');

INSERT INTO companies (canonical_name, normalized_name, company_size, industry_codes, is_active, created_at, updated_at)
SELECT '에이블리', '에이블리', NULL, '이커머스', 1, NOW(6), NOW(6) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM companies WHERE normalized_name = '에이블리');

INSERT INTO companies (canonical_name, normalized_name, company_size, industry_codes, is_active, created_at, updated_at)
SELECT '컬리', '컬리', NULL, '이커머스', 1, NOW(6), NOW(6) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM companies WHERE normalized_name = '컬리');

INSERT INTO companies (canonical_name, normalized_name, company_size, industry_codes, is_active, created_at, updated_at)
SELECT '두나무', '두나무', NULL, '핀테크', 1, NOW(6), NOW(6) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM companies WHERE normalized_name = '두나무');

INSERT INTO companies (canonical_name, normalized_name, company_size, industry_codes, is_active, created_at, updated_at)
SELECT '쏘카', '쏘카', NULL, 'IT/소프트웨어', 1, NOW(6), NOW(6) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM companies WHERE normalized_name = '쏘카');

INSERT INTO companies (canonical_name, normalized_name, company_size, industry_codes, is_active, created_at, updated_at)
SELECT '오늘의집', '오늘의집', NULL, 'IT/소프트웨어', 1, NOW(6), NOW(6) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM companies WHERE normalized_name = '오늘의집');

INSERT INTO companies (canonical_name, normalized_name, company_size, industry_codes, is_active, created_at, updated_at)
SELECT '야놀자', '야놀자', NULL, 'IT/소프트웨어', 1, NOW(6), NOW(6) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM companies WHERE normalized_name = '야놀자');

INSERT INTO companies (canonical_name, normalized_name, company_size, industry_codes, is_active, created_at, updated_at)
SELECT '리디', '리디', NULL, 'IT/소프트웨어', 1, NOW(6), NOW(6) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM companies WHERE normalized_name = '리디');

INSERT INTO companies (canonical_name, normalized_name, company_size, industry_codes, is_active, created_at, updated_at)
SELECT '센드버드', '센드버드', NULL, 'IT/소프트웨어', 1, NOW(6), NOW(6) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM companies WHERE normalized_name = '센드버드');

-- ── Large Corp IT / Digital / Mobility ───────────────────────────────────────

-- industry_codes NULL: 자동차/부품/반도체는 Frontend option set에 해당 카테고리 없음
INSERT INTO companies (canonical_name, normalized_name, company_size, industry_codes, is_active, created_at, updated_at)
SELECT '현대자동차', '현대자동차', '대기업', NULL, 1, NOW(6), NOW(6) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM companies WHERE normalized_name = '현대자동차');

INSERT INTO companies (canonical_name, normalized_name, company_size, industry_codes, is_active, created_at, updated_at)
SELECT '현대오토에버', '현대오토에버', '대기업', 'IT/소프트웨어', 1, NOW(6), NOW(6) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM companies WHERE normalized_name = '현대오토에버');

INSERT INTO companies (canonical_name, normalized_name, company_size, industry_codes, is_active, created_at, updated_at)
SELECT '기아', '기아', '대기업', NULL, 1, NOW(6), NOW(6) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM companies WHERE normalized_name = '기아');

INSERT INTO companies (canonical_name, normalized_name, company_size, industry_codes, is_active, created_at, updated_at)
SELECT '현대모비스', '현대모비스', '대기업', NULL, 1, NOW(6), NOW(6) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM companies WHERE normalized_name = '현대모비스');

-- normalized_name: 'LG CNS'.strip().toLowerCase(ROOT) = 'lg cns'
INSERT INTO companies (canonical_name, normalized_name, company_size, industry_codes, is_active, created_at, updated_at)
SELECT 'LG CNS', 'lg cns', '대기업', 'IT/소프트웨어', 1, NOW(6), NOW(6) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM companies WHERE normalized_name = 'lg cns');

-- normalized_name: 'LG전자'.strip().toLowerCase(ROOT) = 'lg전자'
INSERT INTO companies (canonical_name, normalized_name, company_size, industry_codes, is_active, created_at, updated_at)
SELECT 'LG전자', 'lg전자', '대기업', 'IT/소프트웨어', 1, NOW(6), NOW(6) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM companies WHERE normalized_name = 'lg전자');

-- normalized_name: 'LG유플러스'.strip().toLowerCase(ROOT) = 'lg유플러스'
INSERT INTO companies (canonical_name, normalized_name, company_size, industry_codes, is_active, created_at, updated_at)
SELECT 'LG유플러스', 'lg유플러스', '대기업', 'IT/소프트웨어', 1, NOW(6), NOW(6) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM companies WHERE normalized_name = 'lg유플러스');

INSERT INTO companies (canonical_name, normalized_name, company_size, industry_codes, is_active, created_at, updated_at)
SELECT '삼성전자', '삼성전자', '대기업', 'IT/소프트웨어', 1, NOW(6), NOW(6) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM companies WHERE normalized_name = '삼성전자');

-- normalized_name: '삼성SDS'.strip().toLowerCase(ROOT) = '삼성sds'
INSERT INTO companies (canonical_name, normalized_name, company_size, industry_codes, is_active, created_at, updated_at)
SELECT '삼성SDS', '삼성sds', '대기업', 'IT/소프트웨어', 1, NOW(6), NOW(6) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM companies WHERE normalized_name = '삼성sds');

-- industry_codes NULL: 전자부품/반도체 제조 — Frontend option set에 해당 카테고리 없음
INSERT INTO companies (canonical_name, normalized_name, company_size, industry_codes, is_active, created_at, updated_at)
SELECT '삼성전기', '삼성전기', '대기업', NULL, 1, NOW(6), NOW(6) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM companies WHERE normalized_name = '삼성전기');

-- normalized_name: 'CJ올리브네트웍스'.strip().toLowerCase(ROOT) = 'cj올리브네트웍스'
INSERT INTO companies (canonical_name, normalized_name, company_size, industry_codes, is_active, created_at, updated_at)
SELECT 'CJ올리브네트웍스', 'cj올리브네트웍스', '대기업', 'IT/소프트웨어', 1, NOW(6), NOW(6) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM companies WHERE normalized_name = 'cj올리브네트웍스');

-- normalized_name: 'CJ올리브영'.strip().toLowerCase(ROOT) = 'cj올리브영'
INSERT INTO companies (canonical_name, normalized_name, company_size, industry_codes, is_active, created_at, updated_at)
SELECT 'CJ올리브영', 'cj올리브영', '대기업', '이커머스', 1, NOW(6), NOW(6) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM companies WHERE normalized_name = 'cj올리브영');

INSERT INTO companies (canonical_name, normalized_name, company_size, industry_codes, is_active, created_at, updated_at)
SELECT '롯데이노베이트', '롯데이노베이트', '대기업', 'IT/소프트웨어', 1, NOW(6), NOW(6) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM companies WHERE normalized_name = '롯데이노베이트');

-- normalized_name: 'KT'.strip().toLowerCase(ROOT) = 'kt'
INSERT INTO companies (canonical_name, normalized_name, company_size, industry_codes, is_active, created_at, updated_at)
SELECT 'KT', 'kt', '대기업', 'IT/소프트웨어', 1, NOW(6), NOW(6) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM companies WHERE normalized_name = 'kt');

-- normalized_name: 'KT DS'.strip().toLowerCase(ROOT) = 'kt ds'
INSERT INTO companies (canonical_name, normalized_name, company_size, industry_codes, is_active, created_at, updated_at)
SELECT 'KT DS', 'kt ds', '대기업', 'IT/소프트웨어', 1, NOW(6), NOW(6) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM companies WHERE normalized_name = 'kt ds');

-- normalized_name: 'SK텔레콤'.strip().toLowerCase(ROOT) = 'sk텔레콤'
INSERT INTO companies (canonical_name, normalized_name, company_size, industry_codes, is_active, created_at, updated_at)
SELECT 'SK텔레콤', 'sk텔레콤', '대기업', 'IT/소프트웨어', 1, NOW(6), NOW(6) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM companies WHERE normalized_name = 'sk텔레콤');

-- normalized_name: 'SK하이닉스'.strip().toLowerCase(ROOT) = 'sk하이닉스'
-- industry_codes NULL: 반도체 제조 — Frontend option set에 해당 카테고리 없음
INSERT INTO companies (canonical_name, normalized_name, company_size, industry_codes, is_active, created_at, updated_at)
SELECT 'SK하이닉스', 'sk하이닉스', '대기업', NULL, 1, NOW(6), NOW(6) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM companies WHERE normalized_name = 'sk하이닉스');

-- ── Game / Content ────────────────────────────────────────────────────────────

-- company_size NULL: 대형 게임사지만 한국 공정거래위원회 대기업집단 지정 여부 불명확
INSERT INTO companies (canonical_name, normalized_name, company_size, industry_codes, is_active, created_at, updated_at)
SELECT '크래프톤', '크래프톤', NULL, '게임', 1, NOW(6), NOW(6) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM companies WHERE normalized_name = '크래프톤');

INSERT INTO companies (canonical_name, normalized_name, company_size, industry_codes, is_active, created_at, updated_at)
SELECT '넥슨코리아', '넥슨코리아', NULL, '게임', 1, NOW(6), NOW(6) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM companies WHERE normalized_name = '넥슨코리아');

INSERT INTO companies (canonical_name, normalized_name, company_size, industry_codes, is_active, created_at, updated_at)
SELECT '엔씨소프트', '엔씨소프트', NULL, '게임', 1, NOW(6), NOW(6) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM companies WHERE normalized_name = '엔씨소프트');

INSERT INTO companies (canonical_name, normalized_name, company_size, industry_codes, is_active, created_at, updated_at)
SELECT '넷마블', '넷마블', NULL, '게임', 1, NOW(6), NOW(6) FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM companies WHERE normalized_name = '넷마블');

-- =============================================================================
-- 2. COMPANY ALIASES
-- =============================================================================

-- ── 토스 ─────────────────────────────────────────────────────────────────────
-- Excluded: TOSS → 'toss' (same as Toss below)

INSERT INTO company_aliases (company_id, alias, normalized_alias, created_at)
SELECT c.id, 'Toss', 'toss', NOW(6) FROM companies c
WHERE c.normalized_name = '토스'
  AND NOT EXISTS (SELECT 1 FROM company_aliases WHERE normalized_alias = 'toss');

INSERT INTO company_aliases (company_id, alias, normalized_alias, created_at)
SELECT c.id, '비바리퍼블리카', '비바리퍼블리카', NOW(6) FROM companies c
WHERE c.normalized_name = '토스'
  AND NOT EXISTS (SELECT 1 FROM company_aliases WHERE normalized_alias = '비바리퍼블리카');

INSERT INTO company_aliases (company_id, alias, normalized_alias, created_at)
SELECT c.id, 'Viva Republica', 'viva republica', NOW(6) FROM companies c
WHERE c.normalized_name = '토스'
  AND NOT EXISTS (SELECT 1 FROM company_aliases WHERE normalized_alias = 'viva republica');

-- ── 당근 ─────────────────────────────────────────────────────────────────────

INSERT INTO company_aliases (company_id, alias, normalized_alias, created_at)
SELECT c.id, '당근마켓', '당근마켓', NOW(6) FROM companies c
WHERE c.normalized_name = '당근'
  AND NOT EXISTS (SELECT 1 FROM company_aliases WHERE normalized_alias = '당근마켓');

INSERT INTO company_aliases (company_id, alias, normalized_alias, created_at)
SELECT c.id, 'Daangn', 'daangn', NOW(6) FROM companies c
WHERE c.normalized_name = '당근'
  AND NOT EXISTS (SELECT 1 FROM company_aliases WHERE normalized_alias = 'daangn');

INSERT INTO company_aliases (company_id, alias, normalized_alias, created_at)
SELECT c.id, 'Karrot', 'karrot', NOW(6) FROM companies c
WHERE c.normalized_name = '당근'
  AND NOT EXISTS (SELECT 1 FROM company_aliases WHERE normalized_alias = 'karrot');

-- ── 우아한형제들 ──────────────────────────────────────────────────────────────

INSERT INTO company_aliases (company_id, alias, normalized_alias, created_at)
SELECT c.id, '배달의민족', '배달의민족', NOW(6) FROM companies c
WHERE c.normalized_name = '우아한형제들'
  AND NOT EXISTS (SELECT 1 FROM company_aliases WHERE normalized_alias = '배달의민족');

INSERT INTO company_aliases (company_id, alias, normalized_alias, created_at)
SELECT c.id, 'Woowa Brothers', 'woowa brothers', NOW(6) FROM companies c
WHERE c.normalized_name = '우아한형제들'
  AND NOT EXISTS (SELECT 1 FROM company_aliases WHERE normalized_alias = 'woowa brothers');

-- ── 네이버 ───────────────────────────────────────────────────────────────────
-- Excluded: Naver → 'naver' (same as NAVER below)

INSERT INTO company_aliases (company_id, alias, normalized_alias, created_at)
SELECT c.id, 'NAVER', 'naver', NOW(6) FROM companies c
WHERE c.normalized_name = '네이버'
  AND NOT EXISTS (SELECT 1 FROM company_aliases WHERE normalized_alias = 'naver');

-- ── 네이버클라우드 ────────────────────────────────────────────────────────────

INSERT INTO company_aliases (company_id, alias, normalized_alias, created_at)
SELECT c.id, 'NAVER Cloud', 'naver cloud', NOW(6) FROM companies c
WHERE c.normalized_name = '네이버클라우드'
  AND NOT EXISTS (SELECT 1 FROM company_aliases WHERE normalized_alias = 'naver cloud');

INSERT INTO company_aliases (company_id, alias, normalized_alias, created_at)
SELECT c.id, '네이버 클라우드', '네이버 클라우드', NOW(6) FROM companies c
WHERE c.normalized_name = '네이버클라우드'
  AND NOT EXISTS (SELECT 1 FROM company_aliases WHERE normalized_alias = '네이버 클라우드');

-- ── 라인플러스 ────────────────────────────────────────────────────────────────

INSERT INTO company_aliases (company_id, alias, normalized_alias, created_at)
SELECT c.id, 'LINE Plus', 'line plus', NOW(6) FROM companies c
WHERE c.normalized_name = '라인플러스'
  AND NOT EXISTS (SELECT 1 FROM company_aliases WHERE normalized_alias = 'line plus');

INSERT INTO company_aliases (company_id, alias, normalized_alias, created_at)
SELECT c.id, 'LINE플러스', 'line플러스', NOW(6) FROM companies c
WHERE c.normalized_name = '라인플러스'
  AND NOT EXISTS (SELECT 1 FROM company_aliases WHERE normalized_alias = 'line플러스');

-- ── 카카오 ───────────────────────────────────────────────────────────────────
-- Excluded: KAKAO → 'kakao' (same as Kakao below)

INSERT INTO company_aliases (company_id, alias, normalized_alias, created_at)
SELECT c.id, 'Kakao', 'kakao', NOW(6) FROM companies c
WHERE c.normalized_name = '카카오'
  AND NOT EXISTS (SELECT 1 FROM company_aliases WHERE normalized_alias = 'kakao');

-- ── 카카오뱅크 ───────────────────────────────────────────────────────────────

INSERT INTO company_aliases (company_id, alias, normalized_alias, created_at)
SELECT c.id, 'KakaoBank', 'kakaobank', NOW(6) FROM companies c
WHERE c.normalized_name = '카카오뱅크'
  AND NOT EXISTS (SELECT 1 FROM company_aliases WHERE normalized_alias = 'kakaobank');

INSERT INTO company_aliases (company_id, alias, normalized_alias, created_at)
SELECT c.id, '카카오 뱅크', '카카오 뱅크', NOW(6) FROM companies c
WHERE c.normalized_name = '카카오뱅크'
  AND NOT EXISTS (SELECT 1 FROM company_aliases WHERE normalized_alias = '카카오 뱅크');

-- ── 카카오모빌리티 ───────────────────────────────────────────────────────────

INSERT INTO company_aliases (company_id, alias, normalized_alias, created_at)
SELECT c.id, 'Kakao Mobility', 'kakao mobility', NOW(6) FROM companies c
WHERE c.normalized_name = '카카오모빌리티'
  AND NOT EXISTS (SELECT 1 FROM company_aliases WHERE normalized_alias = 'kakao mobility');

-- ── 카카오페이 ────────────────────────────────────────────────────────────────

INSERT INTO company_aliases (company_id, alias, normalized_alias, created_at)
SELECT c.id, 'Kakao Pay', 'kakao pay', NOW(6) FROM companies c
WHERE c.normalized_name = '카카오페이'
  AND NOT EXISTS (SELECT 1 FROM company_aliases WHERE normalized_alias = 'kakao pay');

-- ── 무신사 ───────────────────────────────────────────────────────────────────

INSERT INTO company_aliases (company_id, alias, normalized_alias, created_at)
SELECT c.id, 'MUSINSA', 'musinsa', NOW(6) FROM companies c
WHERE c.normalized_name = '무신사'
  AND NOT EXISTS (SELECT 1 FROM company_aliases WHERE normalized_alias = 'musinsa');

-- ── 에이블리 ─────────────────────────────────────────────────────────────────

INSERT INTO company_aliases (company_id, alias, normalized_alias, created_at)
SELECT c.id, 'ABLY', 'ably', NOW(6) FROM companies c
WHERE c.normalized_name = '에이블리'
  AND NOT EXISTS (SELECT 1 FROM company_aliases WHERE normalized_alias = 'ably');

INSERT INTO company_aliases (company_id, alias, normalized_alias, created_at)
SELECT c.id, '에이블리코퍼레이션', '에이블리코퍼레이션', NOW(6) FROM companies c
WHERE c.normalized_name = '에이블리'
  AND NOT EXISTS (SELECT 1 FROM company_aliases WHERE normalized_alias = '에이블리코퍼레이션');

-- ── 컬리 ─────────────────────────────────────────────────────────────────────

INSERT INTO company_aliases (company_id, alias, normalized_alias, created_at)
SELECT c.id, 'Kurly', 'kurly', NOW(6) FROM companies c
WHERE c.normalized_name = '컬리'
  AND NOT EXISTS (SELECT 1 FROM company_aliases WHERE normalized_alias = 'kurly');

INSERT INTO company_aliases (company_id, alias, normalized_alias, created_at)
SELECT c.id, '마켓컬리', '마켓컬리', NOW(6) FROM companies c
WHERE c.normalized_name = '컬리'
  AND NOT EXISTS (SELECT 1 FROM company_aliases WHERE normalized_alias = '마켓컬리');

-- ── 두나무 ───────────────────────────────────────────────────────────────────

INSERT INTO company_aliases (company_id, alias, normalized_alias, created_at)
SELECT c.id, 'Dunamu', 'dunamu', NOW(6) FROM companies c
WHERE c.normalized_name = '두나무'
  AND NOT EXISTS (SELECT 1 FROM company_aliases WHERE normalized_alias = 'dunamu');

-- ── 쏘카 ─────────────────────────────────────────────────────────────────────

INSERT INTO company_aliases (company_id, alias, normalized_alias, created_at)
SELECT c.id, 'SOCAR', 'socar', NOW(6) FROM companies c
WHERE c.normalized_name = '쏘카'
  AND NOT EXISTS (SELECT 1 FROM company_aliases WHERE normalized_alias = 'socar');

-- ── 현대오토에버 ─────────────────────────────────────────────────────────────

INSERT INTO company_aliases (company_id, alias, normalized_alias, created_at)
SELECT c.id, 'Hyundai AutoEver', 'hyundai autoever', NOW(6) FROM companies c
WHERE c.normalized_name = '현대오토에버'
  AND NOT EXISTS (SELECT 1 FROM company_aliases WHERE normalized_alias = 'hyundai autoever');

INSERT INTO company_aliases (company_id, alias, normalized_alias, created_at)
SELECT c.id, '현대 오토에버', '현대 오토에버', NOW(6) FROM companies c
WHERE c.normalized_name = '현대오토에버'
  AND NOT EXISTS (SELECT 1 FROM company_aliases WHERE normalized_alias = '현대 오토에버');

-- ── LG CNS ───────────────────────────────────────────────────────────────────

INSERT INTO company_aliases (company_id, alias, normalized_alias, created_at)
SELECT c.id, 'LGCNS', 'lgcns', NOW(6) FROM companies c
WHERE c.normalized_name = 'lg cns'
  AND NOT EXISTS (SELECT 1 FROM company_aliases WHERE normalized_alias = 'lgcns');

INSERT INTO company_aliases (company_id, alias, normalized_alias, created_at)
SELECT c.id, '엘지씨엔에스', '엘지씨엔에스', NOW(6) FROM companies c
WHERE c.normalized_name = 'lg cns'
  AND NOT EXISTS (SELECT 1 FROM company_aliases WHERE normalized_alias = '엘지씨엔에스');

-- ── 삼성SDS ──────────────────────────────────────────────────────────────────

INSERT INTO company_aliases (company_id, alias, normalized_alias, created_at)
SELECT c.id, 'Samsung SDS', 'samsung sds', NOW(6) FROM companies c
WHERE c.normalized_name = '삼성sds'
  AND NOT EXISTS (SELECT 1 FROM company_aliases WHERE normalized_alias = 'samsung sds');

INSERT INTO company_aliases (company_id, alias, normalized_alias, created_at)
SELECT c.id, '삼성에스디에스', '삼성에스디에스', NOW(6) FROM companies c
WHERE c.normalized_name = '삼성sds'
  AND NOT EXISTS (SELECT 1 FROM company_aliases WHERE normalized_alias = '삼성에스디에스');

-- ── CJ올리브영 ───────────────────────────────────────────────────────────────

INSERT INTO company_aliases (company_id, alias, normalized_alias, created_at)
SELECT c.id, '올리브영', '올리브영', NOW(6) FROM companies c
WHERE c.normalized_name = 'cj올리브영'
  AND NOT EXISTS (SELECT 1 FROM company_aliases WHERE normalized_alias = '올리브영');

INSERT INTO company_aliases (company_id, alias, normalized_alias, created_at)
SELECT c.id, 'CJ OliveYoung', 'cj oliveyoung', NOW(6) FROM companies c
WHERE c.normalized_name = 'cj올리브영'
  AND NOT EXISTS (SELECT 1 FROM company_aliases WHERE normalized_alias = 'cj oliveyoung');

-- ── CJ올리브네트웍스 ─────────────────────────────────────────────────────────

INSERT INTO company_aliases (company_id, alias, normalized_alias, created_at)
SELECT c.id, 'CJ OliveNetworks', 'cj olivenetworks', NOW(6) FROM companies c
WHERE c.normalized_name = 'cj올리브네트웍스'
  AND NOT EXISTS (SELECT 1 FROM company_aliases WHERE normalized_alias = 'cj olivenetworks');

-- ── 크래프톤 ─────────────────────────────────────────────────────────────────

INSERT INTO company_aliases (company_id, alias, normalized_alias, created_at)
SELECT c.id, 'KRAFTON', 'krafton', NOW(6) FROM companies c
WHERE c.normalized_name = '크래프톤'
  AND NOT EXISTS (SELECT 1 FROM company_aliases WHERE normalized_alias = 'krafton');

-- ── 넥슨코리아 ───────────────────────────────────────────────────────────────

INSERT INTO company_aliases (company_id, alias, normalized_alias, created_at)
SELECT c.id, 'NEXON', 'nexon', NOW(6) FROM companies c
WHERE c.normalized_name = '넥슨코리아'
  AND NOT EXISTS (SELECT 1 FROM company_aliases WHERE normalized_alias = 'nexon');

INSERT INTO company_aliases (company_id, alias, normalized_alias, created_at)
SELECT c.id, '넥슨', '넥슨', NOW(6) FROM companies c
WHERE c.normalized_name = '넥슨코리아'
  AND NOT EXISTS (SELECT 1 FROM company_aliases WHERE normalized_alias = '넥슨');
