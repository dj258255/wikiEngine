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

-- 2. 배치 INSERT (1만 건씩, 총 571,364건)
-- Flyway는 DDL 이후 DML을 하나의 트랜잭션으로 묶는다.
-- 명시적 COMMIT으로 각 배치를 독립 트랜잭션으로 분리하여
-- innodb_buffer_pool의 lock table 초과를 방지한다.
-- content가 LONGTEXT(평균 6,586자, 최대 252만 자)라 1만 건도 상당한 lock을 잡는다.
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 10000 OFFSET 0;
COMMIT;
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 10000 OFFSET 10000;
COMMIT;
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 10000 OFFSET 20000;
COMMIT;
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 10000 OFFSET 30000;
COMMIT;
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 10000 OFFSET 40000;
COMMIT;
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 10000 OFFSET 50000;
COMMIT;
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 10000 OFFSET 60000;
COMMIT;
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 10000 OFFSET 70000;
COMMIT;
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 10000 OFFSET 80000;
COMMIT;
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 10000 OFFSET 90000;
COMMIT;
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 10000 OFFSET 100000;
COMMIT;
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 10000 OFFSET 110000;
COMMIT;
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 10000 OFFSET 120000;
COMMIT;
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 10000 OFFSET 130000;
COMMIT;
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 10000 OFFSET 140000;
COMMIT;
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 10000 OFFSET 150000;
COMMIT;
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 10000 OFFSET 160000;
COMMIT;
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 10000 OFFSET 170000;
COMMIT;
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 10000 OFFSET 180000;
COMMIT;
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 10000 OFFSET 190000;
COMMIT;
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 10000 OFFSET 200000;
COMMIT;
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 10000 OFFSET 210000;
COMMIT;
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 10000 OFFSET 220000;
COMMIT;
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 10000 OFFSET 230000;
COMMIT;
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 10000 OFFSET 240000;
COMMIT;
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 10000 OFFSET 250000;
COMMIT;
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 10000 OFFSET 260000;
COMMIT;
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 10000 OFFSET 270000;
COMMIT;
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 10000 OFFSET 280000;
COMMIT;
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 10000 OFFSET 290000;
COMMIT;
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 10000 OFFSET 300000;
COMMIT;
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 10000 OFFSET 310000;
COMMIT;
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 10000 OFFSET 320000;
COMMIT;
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 10000 OFFSET 330000;
COMMIT;
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 10000 OFFSET 340000;
COMMIT;
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 10000 OFFSET 350000;
COMMIT;
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 10000 OFFSET 360000;
COMMIT;
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 10000 OFFSET 370000;
COMMIT;
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 10000 OFFSET 380000;
COMMIT;
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 10000 OFFSET 390000;
COMMIT;
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 10000 OFFSET 400000;
COMMIT;
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 10000 OFFSET 410000;
COMMIT;
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 10000 OFFSET 420000;
COMMIT;
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 10000 OFFSET 430000;
COMMIT;
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 10000 OFFSET 440000;
COMMIT;
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 10000 OFFSET 450000;
COMMIT;
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 10000 OFFSET 460000;
COMMIT;
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 10000 OFFSET 470000;
COMMIT;
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 10000 OFFSET 480000;
COMMIT;
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 10000 OFFSET 490000;
COMMIT;
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 10000 OFFSET 500000;
COMMIT;
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 10000 OFFSET 510000;
COMMIT;
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 10000 OFFSET 520000;
COMMIT;
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 10000 OFFSET 530000;
COMMIT;
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 10000 OFFSET 540000;
COMMIT;
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 10000 OFFSET 550000;
COMMIT;
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 10000 OFFSET 560000;
COMMIT;
INSERT INTO tmp_namu_posts SELECT * FROM posts WHERE category_id = 1 ORDER BY id LIMIT 10000 OFFSET 570000;
COMMIT;

-- 3. 제목+본문 FULLTEXT ngram 인덱스 (한국어 57만 건 대상)
CREATE FULLTEXT INDEX ft_title_content ON tmp_namu_posts(title, content) WITH PARSER ngram;
