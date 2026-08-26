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

-- Step 4: NULL 검증 (이 쿼리가 행을 반환하면 migration이 실패해야 하므로 constraint로 처리)
-- NOT NULL 전환 — 위 UPDATE 후에도 NULL이 있으면 여기서 실패
ALTER TABLE briefing_jobs
    MODIFY COLUMN briefing_date DATE NOT NULL;

-- Step 5: UNIQUE 제약 추가
-- 배포 전 중복 진단 SQL:
--   SELECT user_id, briefing_date, COUNT(*) FROM briefing_jobs GROUP BY user_id, briefing_date HAVING COUNT(*) > 1;
ALTER TABLE briefing_jobs
    ADD CONSTRAINT uq_briefing_jobs_user_date UNIQUE (user_id, briefing_date);

-- Step 6: 조회 성능 인덱스
CREATE INDEX idx_briefing_jobs_user_date ON briefing_jobs (user_id, briefing_date);
