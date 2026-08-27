-- V21: briefing_jobs 멱등성 보장을 위한 스키마 변경

-- Step 1: NULL 허용으로 briefing_date 추가
ALTER TABLE briefing_jobs
    ADD COLUMN briefing_date DATE NULL;

-- Step 2: generation_mode, fallback_reason 추가
ALTER TABLE briefing_jobs
    ADD COLUMN generation_mode VARCHAR(30) NULL,
    ADD COLUMN fallback_reason VARCHAR(255) NULL;

-- Step 3: briefing_date backfill — briefing_reports.report_date 우선, 없으면 created_at 날짜
UPDATE briefing_jobs bj
    LEFT JOIN briefing_reports br ON br.briefing_job_id = bj.id
SET bj.briefing_date = COALESCE(br.report_date, DATE(CONVERT_TZ(bj.created_at, '+00:00', '+09:00')))
WHERE bj.briefing_date IS NULL;

-- Step 4: NOT NULL 전환 — 위 UPDATE 후에도 NULL이 있으면 여기서 실패
ALTER TABLE briefing_jobs
    MODIFY COLUMN briefing_date DATE NOT NULL;

-- Step 5: (user_id, briefing_date) 중복 제거 — UNIQUE 제약 추가 전 선행 조건
-- 날짜별 최신(MAX id)만 남기고, 중복 행을 FK 체인 역순으로 삭제한다.
-- briefing_articles → delivery_logs → briefing_reports → briefing_jobs 순서 필수.
DELETE ba FROM briefing_articles ba
    JOIN briefing_reports br ON br.id = ba.briefing_report_id
WHERE br.briefing_job_id NOT IN (
    SELECT max_id
    FROM (SELECT MAX(id) AS max_id FROM briefing_jobs GROUP BY user_id, briefing_date) t
);

DELETE dl FROM delivery_logs dl
    JOIN briefing_reports br ON br.id = dl.briefing_report_id
WHERE br.briefing_job_id NOT IN (
    SELECT max_id
    FROM (SELECT MAX(id) AS max_id FROM briefing_jobs GROUP BY user_id, briefing_date) t
);

DELETE FROM briefing_reports
WHERE briefing_job_id NOT IN (
    SELECT max_id
    FROM (SELECT MAX(id) AS max_id FROM briefing_jobs GROUP BY user_id, briefing_date) t
);

DELETE FROM briefing_jobs
WHERE id NOT IN (
    SELECT max_id
    FROM (SELECT MAX(id) AS max_id FROM briefing_jobs GROUP BY user_id, briefing_date) t
);

-- Step 6: UNIQUE 제약 추가
-- 배포 전 중복 진단 SQL:
--   SELECT user_id, briefing_date, COUNT(*) FROM briefing_jobs GROUP BY user_id, briefing_date HAVING COUNT(*) > 1;
ALTER TABLE briefing_jobs
    ADD CONSTRAINT uq_briefing_jobs_user_date UNIQUE (user_id, briefing_date);

-- Step 7: 조회 성능 인덱스
CREATE INDEX idx_briefing_jobs_user_date ON briefing_jobs (user_id, briefing_date);
