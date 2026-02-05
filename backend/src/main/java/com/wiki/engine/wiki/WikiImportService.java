package com.wiki.engine.wiki;

import com.wiki.engine.wiki.internal.WikiXmlParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 위키피디아 덤프 XML 데이터 임포트 서비스.
 * StAX 파서(WikiXmlParser)로 XML을 스트리밍 파싱하고,
 * JdbcTemplate 배치 INSERT로 posts 테이블에 직접 저장한다.
 *
 * 카테고리 추출:
 * - 한국어: [[분류:카테고리명]] 패턴에서 추출
 * - 영어: [[Category:CategoryName]] 패턴에서 추출
 * - 문서의 첫 번째 카테고리를 대표 카테고리로 posts.category_id에 저장
 * - 카테고리는 INSERT IGNORE로 중복 방지하고, 메모리 캐시로 DB 조회 최소화
 *
 * 필터링 조건:
 * - namespace == 0 (일반 문서만, 토론/사용자/분류 페이지 제외)
 * - 리다이렉트 문서 제외
 *
 * author_id는 시스템 계정(1)으로 고정한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WikiImportService {

    private final JdbcTemplate jdbcTemplate;

    private static final int BATCH_SIZE = 1000;
    private static final long SYSTEM_AUTHOR_ID = 1L;

    /** 한국어 카테고리 패턴: [[분류:카테고리명]] 또는 [[분류:카테고리명|정렬키]] */
    private static final Pattern KO_CATEGORY_PATTERN =
            Pattern.compile("\\[\\[분류:([^|\\]]+)(?:\\|[^\\]]*)?\\]\\]");

    /** 영어 카테고리 패턴: [[Category:CategoryName]] 또는 [[Category:CategoryName|SortKey]] */
    private static final Pattern EN_CATEGORY_PATTERN =
            Pattern.compile("\\[\\[Category:([^|\\]]+)(?:\\|[^\\]]*)?\\]\\]", Pattern.CASE_INSENSITIVE);

    /**
     * XML 파일에서 위키 데이터를 파싱하여 posts 테이블에 임포트한다.
     * namespace=0인 일반 문서만 대상이며, 리다이렉트 문서는 제외한다.
     * 본문에서 카테고리를 추출하여 categories 테이블에 저장하고,
     * 첫 번째 카테고리를 대표 카테고리로 posts.category_id에 연결한다.
     *
     * @param filePath 위키피디아 덤프 XML 파일 경로
     */
    public void importFromXml(String filePath) {
        log.info("위키 데이터 임포트 시작: {}", filePath);

        // 카테고리명 → ID 메모리 캐시 (DB 조회 최소화)
        Map<String, Long> categoryCache = new HashMap<>();
        loadExistingCategories(categoryCache);

        WikiXmlParser parser = new WikiXmlParser();
        List<WikiPage> batch = new ArrayList<>(BATCH_SIZE);
        // 배치 내 각 페이지의 대표 카테고리 ID (null 가능)
        List<Long> batchCategoryIds = new ArrayList<>(BATCH_SIZE);
        AtomicLong totalCount = new AtomicLong(0);
        AtomicLong skippedCount = new AtomicLong(0);
        AtomicLong categoryCount = new AtomicLong(0);
        long startTime = System.currentTimeMillis();

        parser.parse(filePath, page -> {
            // 일반 문서(namespace=0)만 임포트, 리다이렉트 제외
            if (page.getNamespace() != 0 || page.getRedirectTo() != null) {
                skippedCount.incrementAndGet();
                return;
            }

            // 카테고리 추출 및 저장
            Long categoryId = extractAndSaveCategories(page.getContent(), categoryCache, categoryCount);

            batch.add(page);
            batchCategoryIds.add(categoryId);

            if (batch.size() >= BATCH_SIZE) {
                savePostsBatch(batch, batchCategoryIds);
                long count = totalCount.addAndGet(batch.size());
                batch.clear();
                batchCategoryIds.clear();

                if (count % 10000 == 0) {
                    long elapsed = System.currentTimeMillis() - startTime;
                    double rate = count / (elapsed / 1000.0);
                    log.info("임포트 진행: {}건 (스킵: {}건, 카테고리: {}건, {:.0f}건/초)",
                            count, skippedCount.get(), categoryCount.get(), rate);
                }
            }
        });

        if (!batch.isEmpty()) {
            savePostsBatch(batch, batchCategoryIds);
            totalCount.addAndGet(batch.size());
        }

        // 카테고리별 post_count 일괄 업데이트
        updateCategoryPostCounts();

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("위키 데이터 임포트 완료: {}건 (스킵: {}건, 카테고리: {}건), 소요 시간 {}초",
                totalCount.get(), skippedCount.get(), categoryCount.get(), elapsed / 1000);
    }

    /**
     * 기존 카테고리를 DB에서 로드하여 캐시에 저장한다.
     * 이전에 중단된 임포트를 재개할 때 카테고리 중복 생성을 방지한다.
     */
    private void loadExistingCategories(Map<String, Long> categoryCache) {
        jdbcTemplate.query("SELECT id, name FROM categories", rs -> {
            categoryCache.put(rs.getString("name"), rs.getLong("id"));
        });
        log.info("기존 카테고리 {}건 로드 완료", categoryCache.size());
    }

    /**
     * 본문에서 카테고리를 추출하여 categories 테이블에 저장한다.
     * 첫 번째 카테고리의 ID를 반환한다 (대표 카테고리).
     *
     * @param content 위키 문서 본문
     * @param categoryCache 카테고리명 → ID 캐시
     * @param categoryCount 새로 생성된 카테고리 수 카운터
     * @return 대표 카테고리 ID (카테고리 없으면 null)
     */
    private Long extractAndSaveCategories(String content, Map<String, Long> categoryCache,
                                          AtomicLong categoryCount) {
        if (content == null || content.isEmpty()) {
            return null;
        }

        Long firstCategoryId = null;

        // 한국어 카테고리 추출: [[분류:카테고리명]]
        Matcher koMatcher = KO_CATEGORY_PATTERN.matcher(content);
        while (koMatcher.find()) {
            String name = koMatcher.group(1).trim();
            if (!name.isEmpty()) {
                Long id = getOrCreateCategory(name, categoryCache, categoryCount);
                if (firstCategoryId == null) {
                    firstCategoryId = id;
                }
            }
        }

        // 영어 카테고리 추출: [[Category:CategoryName]]
        Matcher enMatcher = EN_CATEGORY_PATTERN.matcher(content);
        while (enMatcher.find()) {
            String name = enMatcher.group(1).trim();
            if (!name.isEmpty()) {
                Long id = getOrCreateCategory(name, categoryCache, categoryCount);
                if (firstCategoryId == null) {
                    firstCategoryId = id;
                }
            }
        }

        return firstCategoryId;
    }

    /**
     * 카테고리를 조회하거나 새로 생성한다.
     * 메모리 캐시를 먼저 확인하고, 없으면 INSERT IGNORE 후 SELECT로 ID를 가져온다.
     *
     * @param name 카테고리 이름
     * @param categoryCache 카테고리명 → ID 캐시
     * @param categoryCount 새로 생성된 카테고리 수 카운터
     * @return 카테고리 ID
     */
    private Long getOrCreateCategory(String name, Map<String, Long> categoryCache,
                                     AtomicLong categoryCount) {
        String truncatedName = truncate(name, 255);

        // 캐시에 있으면 바로 반환
        Long cached = categoryCache.get(truncatedName);
        if (cached != null) {
            return cached;
        }

        // INSERT IGNORE: UNIQUE(name) 제약에 걸리면 무시
        jdbcTemplate.update(
                "INSERT IGNORE INTO categories (name, post_count) VALUES (?, 0)",
                truncatedName
        );

        // INSERT 성공 여부와 관계없이 SELECT로 ID 조회
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM categories WHERE name = ?",
                Long.class, truncatedName
        );

        categoryCache.put(truncatedName, id);
        categoryCount.incrementAndGet();
        return id;
    }

    /**
     * WikiPage 목록을 posts 테이블에 배치 INSERT한다.
     * 카테고리 ID를 함께 저장한다.
     */
    private void savePostsBatch(List<WikiPage> pages, List<Long> categoryIds) {
        String sql = """
            INSERT INTO posts (title, content, author_id, category_id, view_count, like_count, created_at)
            VALUES (?, ?, ?, ?, 0, 0, ?)
            """;

        // 인덱스 기반 접근을 위해 배열로 변환
        Object[][] params = new Object[pages.size()][];
        for (int i = 0; i < pages.size(); i++) {
            WikiPage page = pages.get(i);
            params[i] = new Object[]{
                    truncate(page.getTitle(), 512),
                    page.getContent(),
                    SYSTEM_AUTHOR_ID,
                    categoryIds.get(i),
                    page.getCreatedAt()
            };
        }

        jdbcTemplate.batchUpdate(sql, List.of(params), BATCH_SIZE, (ps, param) -> {
            ps.setString(1, (String) param[0]);
            ps.setString(2, (String) param[1]);
            ps.setLong(3, (Long) param[2]);
            ps.setObject(4, param[3]);
            ps.setObject(5, param[4]);
        });
    }

    /**
     * 모든 카테고리의 post_count를 실제 posts 테이블 기준으로 일괄 업데이트한다.
     * 임포트 완료 후 한 번만 실행하여 성능을 최적화한다.
     */
    private void updateCategoryPostCounts() {
        log.info("카테고리 post_count 일괄 업데이트 시작");
        jdbcTemplate.update("""
            UPDATE categories c
            SET c.post_count = (
                SELECT COUNT(*) FROM posts p WHERE p.category_id = c.id
            )
            """);
        log.info("카테고리 post_count 일괄 업데이트 완료");
    }

    private String truncate(String str, int maxLength) {
        if (str == null || str.length() <= maxLength) return str;
        return str.substring(0, maxLength);
    }
}
