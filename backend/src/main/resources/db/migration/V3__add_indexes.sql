-- ===========================================
-- Phase 3: B-Tree 인덱스
-- ===========================================

-- 자동완성: LIKE 'prefix%' -> B-Tree range scan
-- (title, view_count DESC) 복합 인덱스로 prefix 매칭 + 인기순 정렬
CREATE INDEX idx_title_viewcount ON posts(title, view_count DESC);

-- ===========================================
-- Phase 4: FULLTEXT ngram 인덱스
-- ===========================================
-- 본 테이블(posts)에 ngram 인덱스를 생성하면 content(LONGTEXT) 때문에
-- 인덱스가 수백 GB에 달해 디스크를 초과한다.
-- 한국어 데이터(category_id=1)만 별도 테이블에 복사하여 FULLTEXT 인덱스를 생성한다.
-- 본문 검색은 이후 Lucene + Nori로 전환 예정.

-- 1. 한국어 데이터 전용 임시 테이블 생성
CREATE TABLE tmp_namu_posts LIKE posts;

-- 2. 배치 INSERT (5만 건씩, 락 테이블 초과 방지)
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 50000 OFFSET 0;
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 50000 OFFSET 50000;
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 50000 OFFSET 100000;
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 50000 OFFSET 150000;
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 50000 OFFSET 200000;
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 50000 OFFSET 250000;
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 50000 OFFSET 300000;
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 50000 OFFSET 350000;
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 50000 OFFSET 400000;
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 50000 OFFSET 450000;
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 50000 OFFSET 500000;
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 50000 OFFSET 550000;

-- 3. 제목+본문 FULLTEXT ngram 인덱스 (한국어 57만 건 대상)
CREATE FULLTEXT INDEX ft_title_content ON tmp_namu_posts(title, content) WITH PARSER ngram;
