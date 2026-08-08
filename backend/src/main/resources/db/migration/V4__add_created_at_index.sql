-- 게시글 목록 조회 최적화: ORDER BY created_at DESC + LIMIT
-- 인덱스 없이 14,250,000건 전체 정렬 → 타임아웃
-- 인덱스 추가 후 → 인덱스에서 바로 N건 추출 (filesort 제거)
CREATE INDEX idx_posts_created_at ON posts(created_at DESC);
