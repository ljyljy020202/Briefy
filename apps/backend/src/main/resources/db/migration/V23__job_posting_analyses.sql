-- V23__job_posting_analyses.sql
--
-- job_postings에 대한 optional 1:1 LLM 분류 분석 테이블.
--
-- 설계 결정:
--   - 기존 공고(V23 이전 수집분)에는 행이 없으며, 이는 정상이다.
--     분류 행이 없으면 Spring 추천 로직은 기존 키워드 기반으로 폴백한다.
--   - DEFAULT 'PENDING'은 INSERT 시 기본값이며,
--     기존 job_postings 행에 자동으로 적용되지 않는다.
--   - FK 삭제 정책: ON DELETE RESTRICT (MySQL 기본값, 불필요한 CASCADE 제외).
--     job_postings 행을 삭제하려면 분석 행을 먼저 삭제해야 한다.
--   - accepts_new_grad: TINYINT(1) NULL — null은 판별 불가를 의미한다.
--     애플리케이션에서 false로 강제 변환하지 않는다.

CREATE TABLE IF NOT EXISTS job_posting_analyses
(
    id                          BIGINT         NOT NULL AUTO_INCREMENT,

    -- FK (UNIQUE = 1:1)
    job_posting_id              BIGINT         NOT NULL,

    -- 분류 결과
    job_domain                  VARCHAR(20),
    posting_scope               VARCHAR(30),
    role_groups                 TEXT,                      -- JSON 배열: ["BACKEND","FULLSTACK"]
    recruitment_type            VARCHAR(30),
    tracks                      TEXT,                      -- JSON 배열: [{trackLabel, jobDomain, ...}]
    accepts_new_grad            TINYINT(1)     NULL,       -- nullable boolean: null=판별불가
    min_required_years          INT,
    max_required_years          INT,
    experience_requirement_type VARCHAR(20),
    preferred_experience        TEXT,                      -- 우대 경력 원문 (필수와 구분)

    -- 분석 메타데이터
    analysis_input_hash         VARCHAR(64),               -- 입력 SHA-256 hex
    classifier_version          VARCHAR(20),
    model_name                  VARCHAR(50),
    classification_method       VARCHAR(20),
    classification_status       VARCHAR(20)    NOT NULL DEFAULT 'PENDING',
    evidence                    TEXT,
    uncertainty_reasons         TEXT,                      -- JSON 배열: ["이유1", "이유2"]
    confidence                  DOUBLE,                    -- 진단용 전용
    input_completeness          DOUBLE,
    description_truncated       TINYINT(1)     NULL,
    classified_at               DATETIME(6),

    -- 운영 필드
    attempt_count               INT            NOT NULL DEFAULT 0,
    next_retry_at               DATETIME(6),
    claim_token                 VARCHAR(36),               -- UUID
    lease_until                 DATETIME(6),
    last_error_code             VARCHAR(50),

    -- 감사 필드
    created_at                  DATETIME(6),
    updated_at                  DATETIME(6),

    PRIMARY KEY (id),

    CONSTRAINT uq_jpa_job_posting_id
        UNIQUE (job_posting_id),

    CONSTRAINT fk_jpa_job_posting
        FOREIGN KEY (job_posting_id) REFERENCES job_postings (id)

) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- 인덱스
CREATE INDEX idx_jpa_status
    ON job_posting_analyses (classification_status);

CREATE INDEX idx_jpa_next_retry_at
    ON job_posting_analyses (next_retry_at);

CREATE INDEX idx_jpa_classifier_version
    ON job_posting_analyses (classifier_version);
