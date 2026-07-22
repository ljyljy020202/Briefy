-- V8__fix_greeting_source_urls.sql
-- Fix GREETING source_url for 현대오토에버 and CJ올리브영.
--
-- Problem: /ko/home is a branding/hub page; actual job listing is at /ko/apply.
--          The GREETING parser fetches only source_url, so /ko/home yields 0 jobs.
--
-- Fix:
--   현대오토에버 : /ko/home → /ko/apply
--   CJ올리브영   : /ko/home → /ko/apply
--   무신사       : unchanged (/ko/home embeds job cards directly)
--
-- Verification state is reset to PENDING/UNVERIFIED per the policy that any
-- core field change (source_url) invalidates the previous verification result.
--
-- Idempotency: each UPDATE only fires when the old source_url is still present,
-- so re-running this migration on an already-patched database is safe.

UPDATE company_sources cs
    JOIN companies c ON cs.company_id = c.id
SET cs.source_url          = 'https://career.hyundai-autoever.com/ko/apply',
    cs.status              = 'PENDING',
    cs.verification_status  = 'UNVERIFIED',
    cs.verification_message = NULL,
    cs.last_verified_at     = NULL,
    cs.updated_at           = NOW(6)
WHERE c.normalized_name = '현대오토에버'
  AND cs.source_type    = 'OFFICIAL_CAREER'
  AND cs.source_url     = 'https://career.hyundai-autoever.com/ko/home';

UPDATE company_sources cs
    JOIN companies c ON cs.company_id = c.id
SET cs.source_url          = 'https://career.oliveyoung.com/ko/apply',
    cs.status              = 'PENDING',
    cs.verification_status  = 'UNVERIFIED',
    cs.verification_message = NULL,
    cs.last_verified_at     = NULL,
    cs.updated_at           = NOW(6)
WHERE c.normalized_name = 'cj올리브영'
  AND cs.source_type    = 'OFFICIAL_CAREER'
  AND cs.source_url     = 'https://career.oliveyoung.com/ko/home';
