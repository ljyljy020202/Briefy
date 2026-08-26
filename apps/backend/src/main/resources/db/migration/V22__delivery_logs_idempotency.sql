-- V22: delivery_logs 중복 발송 방지 및 재시도 추적
-- 배포 전 중복 진단 SQL:
--   SELECT briefing_report_id, COUNT(*) FROM delivery_logs WHERE status = 'SENT'
--   GROUP BY briefing_report_id HAVING COUNT(*) > 1;

ALTER TABLE delivery_logs
    ADD COLUMN retry_count INT NOT NULL DEFAULT 0,
    ADD COLUMN last_attempt_at DATETIME(6) NULL;

-- report당 성공 발송 1건 보장
ALTER TABLE delivery_logs
    ADD CONSTRAINT uq_delivery_logs_report UNIQUE (briefing_report_id);
