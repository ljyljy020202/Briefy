-- V2__company_registry.sql
-- Company Registry: companies, company_aliases, company_sources
--
-- companies         — canonical company records (normalizedName unique)
-- company_aliases   — alternate names / display variants pointing to a Company
-- company_sources   — job-board / website crawler config per Company
--
-- User preferences still store company names as free-text strings.
-- These tables are opt-in enrichment, not a constraint on preferences.

-- ---------------------------------------------------------------------------
-- companies
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS companies
(
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    canonical_name   VARCHAR(200) NOT NULL,
    normalized_name  VARCHAR(200) NOT NULL,
    company_size     VARCHAR(30),
    industry_codes   TEXT,
    is_active        TINYINT(1)   NOT NULL DEFAULT 1,
    created_at       DATETIME(6),
    updated_at       DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_companies_normalized_name UNIQUE (normalized_name)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE INDEX IF NOT EXISTS idx_companies_canonical_name ON companies (canonical_name);
CREATE INDEX IF NOT EXISTS idx_companies_is_active ON companies (is_active);

-- ---------------------------------------------------------------------------
-- company_aliases
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS company_aliases
(
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    company_id       BIGINT       NOT NULL,
    alias            VARCHAR(200) NOT NULL,
    normalized_alias VARCHAR(200) NOT NULL,
    created_at       DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_company_aliases_normalized UNIQUE (normalized_alias),
    CONSTRAINT fk_company_aliases_company FOREIGN KEY (company_id) REFERENCES companies (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE INDEX IF NOT EXISTS idx_company_aliases_company_id ON company_aliases (company_id);
CREATE INDEX IF NOT EXISTS idx_company_aliases_alias ON company_aliases (alias);

-- ---------------------------------------------------------------------------
-- company_sources
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS company_sources
(
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    company_id         BIGINT       NOT NULL,
    source_type        VARCHAR(50)  NOT NULL,
    source_url         VARCHAR(500),
    adapter_type       VARCHAR(50),
    status             VARCHAR(30)  NOT NULL,
    config_json        TEXT,
    last_verified_at   DATETIME(6),
    last_collected_at  DATETIME(6),
    created_at         DATETIME(6),
    updated_at         DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_company_sources_company FOREIGN KEY (company_id) REFERENCES companies (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE INDEX IF NOT EXISTS idx_company_sources_company_id ON company_sources (company_id);
CREATE INDEX IF NOT EXISTS idx_company_sources_status ON company_sources (status);
