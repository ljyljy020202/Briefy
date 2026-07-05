-- V1__baseline.sql
-- Briefy initial schema baseline.
-- Source of truth: Java entity files in apps/backend/src/main/java/com/briefy/
-- NOT docs/database.md (which has known field-name divergences from entities).
--
-- Strategy: CREATE TABLE IF NOT EXISTS throughout.
--   Existing DB (local/dev built by ddl-auto: update):
--     Flyway marks this version as applied via baseline-on-migrate=true.
--     These statements are NOT executed.
--   Fresh empty DB:
--     Flyway executes these statements to build the full schema from scratch.
--
-- Table creation order respects FK dependencies:
--   users, briefing_categories, briefing_jobs (no inbound FKs)
--   → user_briefing_preferences (FK → briefing_categories)
--   → briefing_reports           (FK → briefing_jobs)
--   → briefing_articles           (FK → briefing_reports)
--   collection_jobs, job_postings, company_issues, industry_issues, delivery_logs (no structural FKs)

-- ---------------------------------------------------------------------------
-- users
-- Entity: com.briefy.domain.user.entity.User
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS users
(
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    email                VARCHAR(255) NOT NULL,
    nickname             VARCHAR(100),
    profile_image_url    VARCHAR(500),
    provider             VARCHAR(30)  NOT NULL,
    provider_id          VARCHAR(255) NOT NULL,
    role                 VARCHAR(30)  NOT NULL,
    status               VARCHAR(30)  NOT NULL,
    onboarding_completed TINYINT(1)   NOT NULL,
    report_email         VARCHAR(255),
    created_at           DATETIME(6),
    updated_at           DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_users_email    UNIQUE (email),
    CONSTRAINT uq_users_provider UNIQUE (provider, provider_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE INDEX IF NOT EXISTS idx_users_status ON users (status);

-- ---------------------------------------------------------------------------
-- briefing_categories
-- Entity: com.briefy.domain.briefingpreference.entity.BriefingCategory
-- Note: column is display_name (not "name" as documented in database.md).
--       description and display_order columns do NOT exist in the entity.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS briefing_categories
(
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    code         VARCHAR(50) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    phase        VARCHAR(10) NOT NULL,
    is_active    TINYINT(1)  NOT NULL,
    created_at   DATETIME(6),
    updated_at   DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_briefing_categories_code UNIQUE (code)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE INDEX IF NOT EXISTS idx_briefing_categories_active ON briefing_categories (is_active);

-- ---------------------------------------------------------------------------
-- collection_jobs
-- Entity: com.briefy.domain.collection.entity.CollectionJob
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS collection_jobs
(
    id                 BIGINT      NOT NULL AUTO_INCREMENT,
    collection_date    DATE        NOT NULL,
    status             VARCHAR(30) NOT NULL,
    trigger_type       VARCHAR(30) NOT NULL,
    categories         TEXT,
    started_at         DATETIME(6),
    completed_at       DATETIME(6),
    collected_count    INT         NOT NULL,
    saved_count        INT         NOT NULL,
    deduplicated_count INT         NOT NULL,
    error_message      TEXT,
    retry_count        INT         NOT NULL,
    created_at         DATETIME(6),
    updated_at         DATETIME(6),
    PRIMARY KEY (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE INDEX IF NOT EXISTS idx_collection_jobs_date        ON collection_jobs (collection_date);
CREATE INDEX IF NOT EXISTS idx_collection_jobs_status      ON collection_jobs (status);
CREATE INDEX IF NOT EXISTS idx_collection_jobs_date_status ON collection_jobs (collection_date, status);

-- ---------------------------------------------------------------------------
-- job_postings
-- Entity: com.briefy.domain.candidatepool.entity.JobPosting
-- Note: column is description (not description_summary as in database.md).
--       title is VARCHAR(255), company is VARCHAR(100), skills is VARCHAR(1000).
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS job_postings
(
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    title            VARCHAR(255)  NOT NULL,
    company          VARCHAR(100)  NOT NULL,
    source           VARCHAR(255),
    url              VARCHAR(1000) NOT NULL,
    location         VARCHAR(100),
    deadline         DATE,
    description      TEXT,
    roles            TEXT,
    skills           VARCHAR(1000),
    employment_type  VARCHAR(50),
    experience_level VARCHAR(50),
    content_hash     VARCHAR(64),
    collected_date   DATE          NOT NULL,
    published_at     DATETIME(6),
    created_at       DATETIME(6),
    updated_at       DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_job_postings_url UNIQUE (url)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE INDEX IF NOT EXISTS idx_job_postings_collected_date ON job_postings (collected_date);
CREATE INDEX IF NOT EXISTS idx_job_postings_content_hash   ON job_postings (content_hash);

-- ---------------------------------------------------------------------------
-- company_issues
-- Entity: com.briefy.domain.candidatepool.entity.CompanyIssue
-- Note: no source column, no issue_type column (contrary to database.md).
--       company is VARCHAR(100).
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS company_issues
(
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    company        VARCHAR(100)  NOT NULL,
    title          VARCHAR(255)  NOT NULL,
    url            VARCHAR(1000) NOT NULL,
    summary        TEXT,
    published_at   DATETIME(6),
    content_hash   VARCHAR(64),
    collected_date DATE          NOT NULL,
    created_at     DATETIME(6),
    updated_at     DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_company_issues_url UNIQUE (url)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE INDEX IF NOT EXISTS idx_company_issues_collected_date ON company_issues (collected_date);
CREATE INDEX IF NOT EXISTS idx_company_issues_company        ON company_issues (company);

-- ---------------------------------------------------------------------------
-- industry_issues
-- Entity: com.briefy.domain.candidatepool.entity.IndustryIssue
-- Note: column is named "category" (not "industry" as in database.md).
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS industry_issues
(
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    category       VARCHAR(100)  NOT NULL,
    title          VARCHAR(255)  NOT NULL,
    url            VARCHAR(1000) NOT NULL,
    summary        TEXT,
    published_at   DATETIME(6),
    content_hash   VARCHAR(64),
    collected_date DATE          NOT NULL,
    created_at     DATETIME(6),
    updated_at     DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_industry_issues_url UNIQUE (url)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE INDEX IF NOT EXISTS idx_industry_issues_collected_date ON industry_issues (collected_date);
CREATE INDEX IF NOT EXISTS idx_industry_issues_category       ON industry_issues (category);

-- ---------------------------------------------------------------------------
-- briefing_jobs
-- Entity: com.briefy.domain.briefing.entity.BriefingJob
-- user_id is a logical FK only (no DB-level FOREIGN KEY constraint).
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS briefing_jobs
(
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    user_id       BIGINT      NOT NULL,
    status        VARCHAR(30) NOT NULL,
    trigger_type  VARCHAR(30) NOT NULL,
    scheduled_at  DATETIME(6),
    started_at    DATETIME(6),
    completed_at  DATETIME(6),
    error_message TEXT,
    retry_count   INT         NOT NULL,
    created_at    DATETIME(6),
    updated_at    DATETIME(6),
    PRIMARY KEY (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE INDEX IF NOT EXISTS idx_briefing_jobs_user   ON briefing_jobs (user_id);
CREATE INDEX IF NOT EXISTS idx_briefing_jobs_status ON briefing_jobs (status);

-- ---------------------------------------------------------------------------
-- user_briefing_preferences
-- Entity: com.briefy.domain.briefingpreference.entity.UserBriefingPreference
-- category_id has a real DB-level FK to briefing_categories.
-- user_id is a logical FK only.
-- preference_json is TEXT (not JSON type) per @Column(columnDefinition = "TEXT").
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS user_briefing_preferences
(
    id              BIGINT     NOT NULL AUTO_INCREMENT,
    user_id         BIGINT     NOT NULL,
    category_id     BIGINT     NOT NULL,
    preference_json TEXT,
    is_active       TINYINT(1) NOT NULL,
    created_at      DATETIME(6),
    updated_at      DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_ubp_user_category UNIQUE (user_id, category_id),
    CONSTRAINT fk_ubp_category FOREIGN KEY (category_id) REFERENCES briefing_categories (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE INDEX IF NOT EXISTS idx_ubp_user     ON user_briefing_preferences (user_id);
CREATE INDEX IF NOT EXISTS idx_ubp_category ON user_briefing_preferences (category_id);

-- ---------------------------------------------------------------------------
-- briefing_reports
-- Entity: com.briefy.domain.briefing.entity.BriefingReport
-- briefing_job_id has a real DB-level FK to briefing_jobs (unique, 1:1).
-- user_id is a logical FK only (denormalized).
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS briefing_reports
(
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    user_id          BIGINT        NOT NULL,
    briefing_job_id  BIGINT        NOT NULL,
    title            VARCHAR(255)  NOT NULL,
    summary          VARCHAR(1000),
    content          MEDIUMTEXT    NOT NULL,
    report_date      DATE          NOT NULL,
    tone             VARCHAR(30),
    article_count    INT,
    token_input      INT,
    token_output     INT,
    created_at       DATETIME(6),
    updated_at       DATETIME(6),
    PRIMARY KEY (id),
    CONSTRAINT uq_briefing_reports_job UNIQUE (briefing_job_id),
    CONSTRAINT fk_reports_job FOREIGN KEY (briefing_job_id) REFERENCES briefing_jobs (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE INDEX IF NOT EXISTS idx_briefing_reports_user      ON briefing_reports (user_id);
CREATE INDEX IF NOT EXISTS idx_briefing_reports_user_date ON briefing_reports (user_id, report_date);

-- ---------------------------------------------------------------------------
-- briefing_articles
-- Entity: com.briefy.domain.briefing.entity.BriefingArticle
-- Does NOT extend BaseTimeEntity — has created_at only (no updated_at).
-- briefing_report_id has a real DB-level FK to briefing_reports.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS briefing_articles
(
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    briefing_report_id  BIGINT       NOT NULL,
    title               VARCHAR(500) NOT NULL,
    source              VARCHAR(255),
    url                 VARCHAR(1000),
    summary             TEXT,
    why_it_matters      TEXT,
    published_at        DATETIME(6),
    display_order       INT,
    created_at          DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_articles_report FOREIGN KEY (briefing_report_id) REFERENCES briefing_reports (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE INDEX IF NOT EXISTS idx_briefing_articles_report ON briefing_articles (briefing_report_id);

-- ---------------------------------------------------------------------------
-- delivery_logs
-- Entity: com.briefy.domain.delivery.entity.DeliveryLog
-- Note: column is to_email (not "recipient" as in database.md).
--       No channel column (contrary to database.md which marks this as planned).
-- user_id and briefing_report_id are logical FKs only.
-- ---------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS delivery_logs
(
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    user_id              BIGINT       NOT NULL,
    briefing_report_id   BIGINT       NOT NULL,
    status               VARCHAR(20)  NOT NULL,
    to_email             VARCHAR(255) NOT NULL,
    subject              VARCHAR(255),
    provider_message_id  VARCHAR(255),
    error_message        VARCHAR(1000),
    sent_at              DATETIME(6),
    created_at           DATETIME(6),
    updated_at           DATETIME(6),
    PRIMARY KEY (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

CREATE INDEX IF NOT EXISTS idx_delivery_logs_user   ON delivery_logs (user_id);
CREATE INDEX IF NOT EXISTS idx_delivery_logs_report ON delivery_logs (briefing_report_id);
