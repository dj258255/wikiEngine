-- Phase 19 Part 3-5: 클릭 로그 테이블 (LTR implicit feedback)
--
-- 현업 근거: Airbnb/Netflix는 search_events, impressions, clicks 3테이블 분리.
-- 본 프로젝트는 단일 테이블로 시작하되, 핵심 컬럼(query, position, dwell_time)은 모두 포함.
-- 트래픽 확보 후 impression 분리 + position bias 보정 적용 예정.
--
-- 클릭 → relevance 변환 규칙 (WSDM 2014, Kim et al.):
--   dwell > 120s → grade 3 (Highly Relevant, SAT click)
--   dwell 30~120s → grade 2 (Relevant, Medium click)
--   dwell 10~30s → grade 1 (Marginally Relevant, Short click)
--   dwell < 10s → grade 0 (Irrelevant, misclick)
--   미클릭 + position <= 3 → grade 0 (봤는데 안 클릭)
--   미클릭 + position > 3 → 제외 (안 봤을 수 있음)

CREATE TABLE IF NOT EXISTS click_logs (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    query           VARCHAR(500)    NOT NULL COMMENT '검색어 (원본)',
    post_id         BIGINT          NOT NULL COMMENT '클릭된 게시글 ID',
    click_position  SMALLINT        NOT NULL COMMENT '검색 결과에서의 순위 (0-based)',
    dwell_time_ms   BIGINT                   COMMENT '체류시간 (ms), Beacon API로 수집. NULL=아직 미수신',
    session_id      VARCHAR(36)              COMMENT '브라우저 세션 ID (UUID)',
    user_id         BIGINT                   COMMENT '로그인 사용자 ID (NULL=비로그인)',
    created_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_click_logs_query (query(100)),
    INDEX idx_click_logs_post (post_id),
    INDEX idx_click_logs_created (created_at),
    INDEX idx_click_logs_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
