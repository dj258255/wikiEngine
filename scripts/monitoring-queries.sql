-- =============================================================================
-- Wiki Engine 모니터링 쿼리 모음
-- 사용법: MySQL 클라이언트에서 필요한 쿼리를 복사해서 실행
--   docker exec -it wiki-mysql-dev mysql -u wiki -pwiki1234 wikidb
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. 느린 쿼리 TOP 10 (총 실행시간 기준)
-- 어떤 쿼리가 DB 부하를 가장 많이 유발하는지 확인
-- -----------------------------------------------------------------------------
SELECT
  SUBSTRING(DIGEST_TEXT, 1, 120) AS query_pattern,
  COUNT_STAR AS exec_count,
  ROUND(SUM_TIMER_WAIT / 1000000000000, 2) AS total_sec,
  ROUND(AVG_TIMER_WAIT / 1000000000, 2) AS avg_ms,
  ROUND(MAX_TIMER_WAIT / 1000000000, 2) AS max_ms,
  SUM_ROWS_EXAMINED AS rows_scanned,
  SUM_ROWS_SENT AS rows_returned,
  ROUND(SUM_ROWS_EXAMINED / NULLIF(SUM_ROWS_SENT, 0), 2) AS scan_ratio,
  SUM_NO_INDEX_USED AS no_index_count
FROM performance_schema.events_statements_summary_by_digest
WHERE SCHEMA_NAME = 'wikidb'
  AND DIGEST_TEXT NOT LIKE '%performance_schema%'
ORDER BY SUM_TIMER_WAIT DESC
LIMIT 10;

-- -----------------------------------------------------------------------------
-- 2. Full Table Scan 발생 쿼리
-- 인덱스를 타지 않는 쿼리 식별 → 인덱스 추가 대상
-- -----------------------------------------------------------------------------
SELECT
  SUBSTRING(DIGEST_TEXT, 1, 120) AS query_pattern,
  COUNT_STAR AS exec_count,
  SUM_NO_INDEX_USED AS full_scan_count,
  ROUND(AVG_TIMER_WAIT / 1000000000, 2) AS avg_ms,
  SUM_ROWS_EXAMINED AS rows_scanned
FROM performance_schema.events_statements_summary_by_digest
WHERE SCHEMA_NAME = 'wikidb'
  AND SUM_NO_INDEX_USED > 0
ORDER BY SUM_NO_INDEX_USED DESC
LIMIT 10;

-- -----------------------------------------------------------------------------
-- 3. 테이블별 I/O 통계
-- 어떤 테이블에 읽기/쓰기가 집중되는지 확인
-- -----------------------------------------------------------------------------
SELECT
  OBJECT_NAME AS table_name,
  COUNT_READ,
  COUNT_WRITE,
  ROUND(SUM_TIMER_READ / 1000000000, 2) AS read_ms,
  ROUND(SUM_TIMER_WRITE / 1000000000, 2) AS write_ms
FROM performance_schema.table_io_waits_summary_by_table
WHERE OBJECT_SCHEMA = 'wikidb'
ORDER BY SUM_TIMER_READ + SUM_TIMER_WRITE DESC;

-- -----------------------------------------------------------------------------
-- 4. Row Lock 대기 통계
-- Lock 경합이 심한 테이블 식별 (조회수 증가 등 동시 UPDATE 병목)
-- -----------------------------------------------------------------------------
SELECT
  OBJECT_NAME AS table_name,
  COUNT_READ,
  COUNT_WRITE,
  COUNT_FETCH,
  COUNT_INSERT,
  COUNT_UPDATE,
  COUNT_DELETE,
  SUM_TIMER_WAIT AS total_wait_ns,
  ROUND(SUM_TIMER_WAIT / 1000000000, 2) AS total_wait_ms
FROM performance_schema.table_lock_waits_summary_by_table
WHERE OBJECT_SCHEMA = 'wikidb'
  AND SUM_TIMER_WAIT > 0
ORDER BY SUM_TIMER_WAIT DESC;

-- -----------------------------------------------------------------------------
-- 5. 커넥션 / 스레드 상태
-- 현재 연결 수, 활성 스레드, 최대 커넥션 대비 사용률 확인
-- -----------------------------------------------------------------------------
SELECT
  VARIABLE_NAME,
  VARIABLE_VALUE
FROM performance_schema.global_status
WHERE VARIABLE_NAME IN (
  'Threads_connected',
  'Threads_running',
  'Threads_created',
  'Max_used_connections',
  'Connections',
  'Aborted_connects'
)
ORDER BY VARIABLE_NAME;

-- -----------------------------------------------------------------------------
-- 6. InnoDB 버퍼 풀 히트율
-- 히트율이 99% 이하면 버퍼 풀 크기 증가 검토
-- -----------------------------------------------------------------------------
SELECT
  ROUND(
    (1 - (
      (SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME = 'Innodb_buffer_pool_reads')
      /
      NULLIF((SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME = 'Innodb_buffer_pool_read_requests'), 0)
    )) * 100, 2
  ) AS buffer_pool_hit_rate_pct;

-- -----------------------------------------------------------------------------
-- 7. Slow query log 상태
-- slow query 누적 수 확인
-- -----------------------------------------------------------------------------
SHOW GLOBAL STATUS LIKE 'Slow_queries';

-- -----------------------------------------------------------------------------
-- 8. 테이블 크기 확인
-- 데이터량 및 인덱스 크기 파악
-- -----------------------------------------------------------------------------
SELECT
  TABLE_NAME,
  TABLE_ROWS,
  ROUND(DATA_LENGTH / 1024 / 1024, 2) AS data_mb,
  ROUND(INDEX_LENGTH / 1024 / 1024, 2) AS index_mb,
  ROUND((DATA_LENGTH + INDEX_LENGTH) / 1024 / 1024, 2) AS total_mb
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = 'wikidb'
ORDER BY DATA_LENGTH + INDEX_LENGTH DESC;

-- -----------------------------------------------------------------------------
-- 9. 통계 초기화 (부하 테스트 전 실행)
-- 이전 통계를 리셋해야 테스트 결과만 정확하게 측정 가능
-- -----------------------------------------------------------------------------
TRUNCATE performance_schema.events_statements_summary_by_digest;
TRUNCATE performance_schema.table_io_waits_summary_by_table;
TRUNCATE performance_schema.table_lock_waits_summary_by_table;
FLUSH STATUS;

-- =============================================================================
-- 부록: 추가 진단 쿼리
-- =============================================================================

-- A. 사용되지 않는 인덱스
-- 불필요한 인덱스 제거 대상 식별 (쓰기 성능 개선)
SELECT
  OBJECT_NAME AS table_name,
  INDEX_NAME
FROM performance_schema.table_io_waits_summary_by_index_usage
WHERE OBJECT_SCHEMA = 'wikidb'
  AND INDEX_NAME IS NOT NULL
  AND INDEX_NAME != 'PRIMARY'
  AND COUNT_READ = 0
  AND COUNT_WRITE = 0;

-- B. 현재 실행 중인 쿼리
-- 장시간 실행 쿼리나 Lock 대기 쿼리 확인
SELECT
  ID,
  USER,
  TIME,
  STATE,
  SUBSTRING(INFO, 1, 100) AS query
FROM information_schema.PROCESSLIST
WHERE COMMAND != 'Sleep'
ORDER BY TIME DESC;
