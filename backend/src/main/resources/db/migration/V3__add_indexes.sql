-- ===========================================
-- Phase 3: B-Tree 인덱스
-- ===========================================

-- 자동완성: LIKE 'prefix%' -> B-Tree range scan
-- (title, view_count DESC) 복합 인덱스로 prefix 매칭 + 인기순 정렬
CREATE INDEX idx_title_viewcount ON posts(title, view_count DESC);

-- 목록 조회: ORDER BY created_at DESC -> 최신순 정렬
CREATE INDEX idx_created_at ON posts(created_at DESC);

-- 카테고리별 목록: WHERE category_id = ? ORDER BY created_at DESC
CREATE INDEX idx_category_created ON posts(category_id, created_at DESC);

-- ===========================================
-- Phase 4: FULLTEXT ngram 인덱스
-- ===========================================

-- 제목+본문 검색: LIKE '%keyword%' -> MATCH(title, content) AGAINST(? IN BOOLEAN MODE)
-- ngram parser로 한국어/영어 모두 2-gram 토큰화
CREATE FULLTEXT INDEX ft_title_content ON posts(title, content) WITH PARSER ngram;
