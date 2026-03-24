-- Phase 18: 동의어 테이블
-- 쿼리 타임 확장용: "AI" 검색 시 "인공지능"도 함께 검색
CREATE TABLE synonyms (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    term VARCHAR(100) NOT NULL,
    synonym VARCHAR(100) NOT NULL,
    weight DOUBLE NOT NULL DEFAULT 1.0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_synonyms_term (term),
    UNIQUE KEY uk_term_synonym (term, synonym)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- 초기 동의어 데이터: 핵심 약어/한영 매핑
INSERT INTO synonyms (term, synonym, weight) VALUES
    ('AI', '인공지능', 1.0),
    ('인공지능', 'AI', 1.0),
    ('ML', '머신러닝', 1.0),
    ('머신러닝', 'ML', 1.0),
    ('머신러닝', '기계학습', 0.8),
    ('기계학습', '머신러닝', 0.8),
    ('DB', '데이터베이스', 1.0),
    ('데이터베이스', 'DB', 1.0),
    ('OS', '운영체제', 1.0),
    ('운영체제', 'OS', 1.0),
    ('CPU', '중앙처리장치', 0.8),
    ('GPU', '그래픽처리장치', 0.8),
    ('RAM', '메모리', 0.7),
    ('IoT', '사물인터넷', 1.0),
    ('사물인터넷', 'IoT', 1.0),
    ('API', '응용프로그래밍인터페이스', 0.6),
    ('VR', '가상현실', 1.0),
    ('가상현실', 'VR', 1.0),
    ('AR', '증강현실', 1.0),
    ('증강현실', 'AR', 1.0),
    ('UI', '사용자인터페이스', 0.8),
    ('UX', '사용자경험', 0.8),
    ('OOP', '객체지향프로그래밍', 0.8),
    ('DL', '딥러닝', 1.0),
    ('딥러닝', 'DL', 1.0),
    ('딥러닝', '심층학습', 0.8),
    ('NLP', '자연어처리', 1.0),
    ('자연어처리', 'NLP', 1.0),
    ('USA', '미국', 1.0),
    ('미국', 'United States', 0.9),
    ('UK', '영국', 1.0),
    ('영국', 'United Kingdom', 0.9);
