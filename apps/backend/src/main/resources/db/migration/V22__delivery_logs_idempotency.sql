-- V22: delivery_logs 중복 발송 방지 및 재시도 추적

-- Step 1: retry_count, last_attempt_at 컬럼 추가
ALTER TABLE delivery_logs
    ADD COLUMN retry_count INT NOT NULL DEFAULT 0,
    ADD COLUMN last_attempt_at DATETIME(6) NULL;

-- Step 2: briefing_report_id 중복 정리 — UNIQUE 제약 전 선행 조건
-- report당 SENT 레코드를 우선 유지, 없으면 MAX id(최신 행) 유지
DELETE FROM delivery_logs
WHERE id NOT IN (
    SELECT keep_id
    FROM (
        SELECT COALESCE(
            MAX(CASE WHEN status = 'SENT' THEN id END),
            MAX(id)
        ) AS keep_id
        FROM delivery_logs
        GROUP BY briefing_report_id
    ) t
);

-- Step 3: report당 성공 발송 1건 보장
ALTER TABLE delivery_logs
    ADD CONSTRAINT uq_delivery_logs_report UNIQUE (briefing_report_id);
