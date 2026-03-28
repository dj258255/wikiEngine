-- Phase 20: 콘텐츠 필터링 — blinded 컬럼 추가
ALTER TABLE posts ADD COLUMN blinded BOOLEAN NOT NULL DEFAULT FALSE;
