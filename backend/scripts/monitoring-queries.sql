-- ============================================================
-- Wiki Engine 성능 모니터링 SQL 쿼리 모음
-- MySQL performance_schema 및 slow query log 기반
--
-- 사용법:
--   docker exec -i wiki-mysql mysql -u root -proot wikidb < scripts/monitoring-queries.sql
--   또는 MySQL 클라이언트에서 개별 실행
-- ============================================================


-- ============================================================
-- 1. 느린 쿼리 TOP 10
-- 총 실행 시간이 가장 긴 쿼리 패턴을 식별한다.
-- avg_time_ms가 높으면 → 쿼리 자체가 느림 (인덱스 부재, Full Scan 등)
-- exec_count가 높으면 → 자주 호출되는 쿼리 (캐싱 후보)
-- ============================================================
SELECT
  SUBSTRING(DIGEST_TEXT, 1, 120) AS query_pattern,
  COUNT_STAR AS exec_count,
  ROUND(SUM_TIMER_WAIT / 1000000000000, 2) AS total_time_sec,
  ROUND(AVG_TIMER_WAIT / 1000000000, 2) AS avg_time_ms,
  ROUND(MAX_TIMER_WAIT / 1000000000, 2) AS max_time_ms,
  SUM_ROWS_EXAMINED AS rows_scanned,
  SUM_ROWS_SENT AS rows_returned
FROM performance_schema.events_statements_summary_by_digest
WHERE SCHEMA_NAME = 'wikidb'
ORDER BY SUM_TIMER_WAIT DESC
LIMIT 10;


-- ============================================================
-- 2. Full Table Scan 발생 쿼리
-- SUM_NO_INDEX_USED > 0인 쿼리는 인덱스를 타지 않고 전체 테이블을 스캔한다.
-- LIKE '%keyword%' 검색이 여기에 잡혀야 정상이다.
-- ============================================================
SELECT
  SUBSTRING(DIGEST_TEXT, 1, 120) AS query_pattern,
  COUNT_STAR AS exec_count,
  ROUND(AVG_TIMER_WAIT / 1000000000, 2) AS avg_time_ms,
  SUM_NO_INDEX_USED AS no_index_count,
  SUM_NO_GOOD_INDEX_USED AS bad_index_count,
  SUM_ROWS_EXAMINED AS rows_scanned
FROM performance_schema.events_statements_summary_by_digest
WHERE SCHEMA_NAME = 'wikidb'
  AND SUM_NO_INDEX_USED > 0
ORDER BY SUM_NO_INDEX_USED DESC
LIMIT 10;


-- ============================================================
-- 3. 테이블별 I/O 통계
-- 어떤 테이블에 읽기/쓰기가 집중되는지 확인한다.
-- posts 테이블의 read_time_ms가 높으면 검색 최적화 필요.
-- ============================================================
SELECT
  OBJECT_NAME AS table_name,
  COUNT_READ,
  COUNT_WRITE,
  ROUND(SUM_TIMER_READ / 1000000000, 2) AS read_time_ms,
  ROUND(SUM_TIMER_WRITE / 1000000000, 2) AS write_time_ms
FROM performance_schema.table_io_waits_summary_by_table
WHERE OBJECT_SCHEMA = 'wikidb'
ORDER BY SUM_TIMER_READ DESC;


-- ============================================================
-- 4. 행 수준 잠금(Lock) 대기 통계
-- 조회수 동기 증가(UPDATE view_count)에서 Row Lock 경합이 발생하면 여기에 잡힌다.
-- SUM_TIMER_WAIT가 높으면 비동기 처리 또는 배치 업데이트 필요.
-- ============================================================
SELECT
  OBJECT_NAME AS table_name,
  COUNT_READ AS lock_read_count,
  COUNT_WRITE AS lock_write_count,
  ROUND(SUM_TIMER_READ / 1000000000, 2) AS lock_read_wait_ms,
  ROUND(SUM_TIMER_WRITE / 1000000000, 2) AS lock_write_wait_ms
FROM performance_schema.table_lock_waits_summary_by_table
WHERE OBJECT_SCHEMA = 'wikidb'
ORDER BY SUM_TIMER_WRITE DESC;


-- ============================================================
-- 5. 커넥션 및 스레드 상태
-- Threads_connected가 hikari pool size(10)에 근접하면 커넥션 풀 부족.
-- Threads_running이 높으면 동시 쿼리 처리 중 병목.
-- ============================================================
SHOW STATUS LIKE 'Threads_connected';
SHOW STATUS LIKE 'Threads_running';
SHOW STATUS LIKE 'Max_used_connections';
SHOW STATUS LIKE 'Aborted_connects';


-- ============================================================
-- 6. InnoDB 버퍼 풀 상태
-- hit_rate가 낮으면 (< 99%) → 버퍼 풀 크기 증가 필요.
-- pages_dirty가 높으면 → 쓰기 부하 높음.
-- ============================================================
SELECT
  ROUND(
    (1 - (
      (SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME = 'Innodb_buffer_pool_reads')
      /
      NULLIF((SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME = 'Innodb_buffer_pool_read_requests'), 0)
    )) * 100, 2
  ) AS buffer_pool_hit_rate_pct;

SHOW STATUS LIKE 'Innodb_buffer_pool_pages_dirty';
SHOW STATUS LIKE 'Innodb_buffer_pool_pages_total';
SHOW STATUS LIKE 'Innodb_buffer_pool_pages_free';


-- ============================================================
-- 7. slow query log 확인 (컨테이너 외부에서)
-- docker exec wiki-mysql cat /var/log/mysql/slow.log
-- docker exec wiki-mysql tail -100 /var/log/mysql/slow.log
-- ============================================================
SHOW VARIABLES LIKE 'slow_query_log';
SHOW VARIABLES LIKE 'long_query_time';
SHOW STATUS LIKE 'Slow_queries';


-- ============================================================
-- 8. 테이블 크기 확인
-- 데이터 임포트 후 테이블별 행 수와 디스크 사용량을 확인한다.
-- ============================================================
SELECT
  TABLE_NAME,
  TABLE_ROWS AS row_count,
  ROUND(DATA_LENGTH / 1024 / 1024, 2) AS data_size_mb,
  ROUND(INDEX_LENGTH / 1024 / 1024, 2) AS index_size_mb,
  ROUND((DATA_LENGTH + INDEX_LENGTH) / 1024 / 1024, 2) AS total_size_mb
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = 'wikidb'
ORDER BY DATA_LENGTH DESC;


-- ============================================================
-- 9. 쿼리 통계 초기화
-- 부하 테스트 전에 실행하여 깨끗한 상태에서 측정을 시작한다.
-- ============================================================
-- TRUNCATE TABLE performance_schema.events_statements_summary_by_digest;
-- FLUSH STATUS;
