-- V7__add_company_source_verification.sql
-- Add verification_status / verification_message to company_sources.
-- Existing rows receive DEFAULT 'UNVERIFIED' automatically (MySQL fills them on ALTER).

ALTER TABLE company_sources
    ADD COLUMN verification_status  VARCHAR(30) NOT NULL DEFAULT 'UNVERIFIED'
        AFTER status;

ALTER TABLE company_sources
    ADD COLUMN verification_message TEXT NULL
        AFTER verification_status;

CREATE INDEX idx_company_sources_verification_status
    ON company_sources (verification_status);
