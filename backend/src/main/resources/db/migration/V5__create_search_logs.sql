-- V5__create_search_logs.sql
-- 9단계: 검색 로그 수집 인프라 (시간 버킷 집계)
--
-- 누적 카운트(query, total_count)가 아닌 시간 버킷 집계(query, time_bucket, count) 구조.
-- 누적 카운트는 "10000회가 언제 발생했는지" 알 수 없어 시간 감쇠 점수 계산이 불가능하다.
-- 시간 버킷(시간 단위)으로 분리하면 "지난 24시간", "지난 7일" 등 윈도우별 집계가 가능하다.
--
-- SearchLogCollector가 인메모리 집계 후 5분마다 현재 시간 버킷으로 upsert한다.
-- 같은 시간대의 여러 flush는 ON DUPLICATE KEY UPDATE로 누적된다.
-- 30일 이전 데이터는 주기적으로 삭제한다.

CREATE TABLE search_logs (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    query       VARCHAR(200) NOT NULL,
    time_bucket DATETIME     NOT NULL COMMENT '시간 단위 버킷 (예: 2026-03-15 14:00:00)',
    count       BIGINT       NOT NULL DEFAULT 1,
    UNIQUE INDEX uk_search_logs_query_bucket (query, time_bucket),
    INDEX idx_search_logs_time_bucket (time_bucket)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
