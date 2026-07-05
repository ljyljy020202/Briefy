-- V3__job_posting_multi_source.sql
-- Multi-source job-posting support.
--
-- Changes to job_postings:
--   - Drop the url unique constraint (same posting may arrive from multiple sources).
--   - Add nullable company_id FK to companies (unknown companies stay as free-text).
--   - Add canonical_fingerprint for cross-source identity (indexed, not unique).
--
-- New table job_posting_sources:
--   - One row per (source, url) pair.
--   - source_record_key is a SHA-256 hex of "source\0url" — always 64 chars, unique.
--   - One canonical JobPosting may have many JobPostingSource rows.

-- ---------------------------------------------------------------------------
-- Modify job_postings
-- ---------------------------------------------------------------------------

-- Drop the url unique index (constraint lives as an index in MySQL).
ALTER TABLE job_postings DROP INDEX uq_job_postings_url;

-- Nullable FK to companies registry (optional enrichment).
ALTER TABLE job_postings
    ADD COLUMN company_id BIGINT NULL AFTER company;

ALTER TABLE job_postings
    ADD CONSTRAINT fk_job_postings_company
        FOREIGN KEY (company_id) REFERENCES companies (id);

-- Canonical identity fingerprint — same content from different sources shares one posting.
ALTER TABLE job_postings
    ADD COLUMN canonical_fingerprint VARCHAR(64) NULL AFTER content_hash;

CREATE INDEX IF NOT EXISTS idx_job_postings_company_id
    ON job_postings (company_id);

CREATE INDEX IF NOT EXISTS idx_job_postings_canonical_fingerprint
    ON job_postings (canonical_fingerprint);

-- ---------------------------------------------------------------------------
-- job_posting_sources
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS job_posting_sources
(
    id                  BIGINT        NOT NULL AUTO_INCREMENT,
    job_posting_id      BIGINT        NOT NULL,
    source              VARCHAR(100),
    source_external_id  VARCHAR(500),
    source_url          VARCHAR(1000) NOT NULL,
    source_record_key   VARCHAR(64)   NOT NULL,
    source_content_hash VARCHAR(64),
    first_seen_at       DATETIME(6),
    last_seen_at        DATETIME(6),
    last_collected_at   DATE,
    is_active           TINYINT(1)    NOT NULL DEFAULT 1,
    created_at          DATETIME(6),
    updated_at          DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_jps_source_record_key UNIQUE (source_record_key),
    CONSTRAINT fk_jps_job_posting FOREIGN KEY (job_posting_id) REFERENCES job_postings (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE INDEX IF NOT EXISTS idx_jps_job_posting_id ON job_posting_sources (job_posting_id);
CREATE INDEX IF NOT EXISTS idx_jps_source ON job_posting_sources (source);
CREATE INDEX IF NOT EXISTS idx_jps_is_active ON job_posting_sources (is_active);
