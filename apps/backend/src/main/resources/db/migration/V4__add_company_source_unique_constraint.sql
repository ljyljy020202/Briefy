-- V4__add_company_source_unique_constraint.sql
-- Prevent duplicate (company_id, source_type, source_url) registrations in company_sources.
--
-- Rationale:
--   Without this constraint, the same URL can be inserted multiple times for the same company,
--   causing duplicate HTTP fetches during OfficialCompanyAdapter collection runs.
--
-- NULL handling:
--   MySQL treats each NULL as distinct in a UNIQUE index, so rows with source_url = NULL
--   are still allowed to co-exist.  Application code should validate that source_url is
--   non-null before inserting OFFICIAL_CAREER sources (see source_url validation note in
--   the audit report).
--
-- Pre-deployment check:
--   Run the following query before applying this migration.
--   If it returns any rows the migration will fail; remove duplicates manually first.
--
--   SELECT company_id, source_type, source_url, COUNT(*) AS cnt
--   FROM   company_sources
--   WHERE  source_url IS NOT NULL
--   GROUP  BY company_id, source_type, source_url
--   HAVING cnt > 1;
--
-- source_url is VARCHAR(500) — InnoDB requires a key-prefix for columns wider than 191 chars
-- under utf8mb4 (191 × 4 bytes = 764 < 767 byte limit).

ALTER TABLE company_sources
    ADD CONSTRAINT uq_company_sources_company_type_url
        UNIQUE (company_id, source_type, source_url(191));
