package com.wiki.engine.post.internal;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 카테고리 자동 분류 서비스 — 키워드 매칭 기반.
 *
 * 각 카테고리에 정의된 대표 키워드와 게시글 제목을 매칭하여 가장 적합한 카테고리를 할당한다.
 * MoreLikeThis 대비 장점: DB 쿼리 기반이라 1,425만 건을 수 분 내에 분류 가능.
 *
 * 분류 로직:
 * 1. category_keywords 테이블에서 (카테고리, 키워드, 가중치) 로드
 * 2. 각 게시글 제목에 포함된 키워드의 가중치를 카테고리별로 합산
 * 3. 가장 높은 점수의 카테고리를 할당
 * 4. 아무 키워드도 매칭 안 되면 "기타"
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CategoryClassificationService {

    private final JdbcTemplate jdbcTemplate;
    private final EntityManager entityManager;

    /**
     * 전체 게시글을 주제별 카테고리로 분류한다.
     * category_keywords 테이블의 키워드를 기반으로 제목 매칭.
     *
     * 배치 실행: 관리자 API로 수동 트리거.
     */
    @Transactional
    public void classifyAll() {
        log.info("=== 카테고리 자동 분류 시작 ===");
        long startTime = System.currentTimeMillis();

        // 1. 카테고리 이름 → ID 매핑 로드
        Map<String, Long> categoryNameToId = new HashMap<>();
        jdbcTemplate.query("SELECT id, name FROM categories", rs -> {
            categoryNameToId.put(rs.getString("name"), rs.getLong("id"));
        });
        log.info("카테고리 {}개 로드", categoryNameToId.size());

        Long etcCategoryId = categoryNameToId.get("기타");
        if (etcCategoryId == null) {
            log.error("'기타' 카테고리가 존재하지 않습니다");
            return;
        }

        // 2. 키워드 → (카테고리이름, 가중치) 매핑 로드
        Map<String, List<KeywordMapping>> keywordMap = new HashMap<>();
        jdbcTemplate.query(
                "SELECT category_name, keyword, weight FROM category_keywords",
                rs -> {
                    String keyword = rs.getString("keyword").toLowerCase();
                    keywordMap.computeIfAbsent(keyword, k -> new ArrayList<>())
                            .add(new KeywordMapping(rs.getString("category_name"), rs.getDouble("weight")));
                }
        );
        log.info("키워드 매핑 {}개 로드", keywordMap.size());

        // 3. 전체 게시글을 "기타"로 초기화 (깨끗한 상태에서 시작)
        int resetCount = jdbcTemplate.update("UPDATE posts SET category_id = ?", etcCategoryId);
        log.info("전체 {}건을 '기타'로 초기화", resetCount);

        // 4. 카테고리별 키워드로 SQL UPDATE (키워드 매칭된 것만 덮어쓰기)
        int totalUpdated = 0;

        for (Map.Entry<String, Long> entry : categoryNameToId.entrySet()) {
            String categoryName = entry.getKey();
            Long categoryId = entry.getValue();

            if ("기타".equals(categoryName)) continue;

            // 이 카테고리에 속한 키워드 수집 (weight >= 0.8만)
            List<String> keywords = new ArrayList<>();
            for (Map.Entry<String, List<KeywordMapping>> kwEntry : keywordMap.entrySet()) {
                for (KeywordMapping mapping : kwEntry.getValue()) {
                    if (mapping.categoryName.equals(categoryName) && mapping.weight >= 0.8) {
                        keywords.add(kwEntry.getKey());
                    }
                }
            }

            if (keywords.isEmpty()) continue;

            // 제목 기반 키워드 LIKE 매칭
            StringBuilder sql = new StringBuilder("UPDATE posts SET category_id = ? WHERE (");

            List<Object> params = new ArrayList<>();
            params.add(categoryId);

            for (int i = 0; i < keywords.size(); i++) {
                if (i > 0) sql.append(" OR ");
                sql.append("LOWER(title) LIKE ?");
                params.add("%" + keywords.get(i) + "%");
            }
            sql.append(")");

            int updated = jdbcTemplate.update(sql.toString(), params.toArray());
            if (updated > 0) {
                log.info("카테고리 '{}': {}건 분류 (키워드 {}개)", categoryName, updated, keywords.size());
                totalUpdated += updated;
            }
        }

        // "기타"에 남은 건수 = resetCount - totalUpdated
        int etcRemaining = resetCount - totalUpdated;
        log.info("'기타' 카테고리: {}건 (미분류)", etcRemaining);

        long elapsed = (System.currentTimeMillis() - startTime) / 1000;
        log.info("=== 카테고리 분류 완료: {}건, {}초 ===", totalUpdated, elapsed);
    }

    private record KeywordMapping(String categoryName, double weight) {}
}
