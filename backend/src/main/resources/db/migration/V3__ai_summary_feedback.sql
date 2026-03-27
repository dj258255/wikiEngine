-- Phase 21: AI 요약 피드백 테이블
-- Google/ChatGPT/Perplexity 동일 패턴 — thumbs up/down + 카테고리 + 코멘트
CREATE TABLE IF NOT EXISTS ai_summary_feedback (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    query           VARCHAR(500) NOT NULL,
    user_id         BIGINT,
    rating          TINYINT NOT NULL,                    -- 1=thumbs_up, -1=thumbs_down
    category        VARCHAR(32),                         -- 'inaccurate','incomplete','irrelevant','offensive'
    comment         TEXT,
    model_name      VARCHAR(64) DEFAULT 'gemini-2.0-flash',
    prompt_version  VARCHAR(32) DEFAULT 'v1',
    input_tokens    INT,
    output_tokens   INT,
    latency_ms      INT,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_query (query(100)),
    INDEX idx_created_at (created_at),
    INDEX idx_rating_created (rating, created_at)
);
