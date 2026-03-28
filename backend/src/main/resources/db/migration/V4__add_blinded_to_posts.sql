-- Phase 20: 콘텐츠 필터링 — blinded 컬럼 추가 (idempotent)
SET @col_exists = (SELECT COUNT(*) FROM information_schema.columns
                   WHERE table_schema = DATABASE() AND table_name = 'posts' AND column_name = 'blinded');
SET @sql = IF(@col_exists = 0,
              'ALTER TABLE posts ADD COLUMN blinded BOOLEAN NOT NULL DEFAULT FALSE',
              'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
