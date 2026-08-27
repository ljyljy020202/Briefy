-- V20: collection_jobs에 날짜 유니크 제약 추가
-- 멱등적 수집 작업 생성을 위한 DB 레벨 보장
-- 주의: 기존 중복 데이터가 있으면 실패합니다.
-- 배포 전 진단 SQL: SELECT collection_date, COUNT(*) FROM collection_jobs GROUP BY collection_date HAVING COUNT(*) > 1;

ALTER TABLE collection_jobs
    ADD CONSTRAINT uq_collection_jobs_date UNIQUE (collection_date);
