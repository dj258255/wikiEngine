package com.wiki.engine.wiki;

import com.wiki.engine.wiki.internal.NamuWikiJsonParser;
import com.wiki.engine.wiki.internal.WikiXmlParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 위키 덤프 데이터 임포트 서비스.
 * 위키피디아 XML과 나무위키 JSON 모두 지원한다.
 *
 * 매핑 전략:
 * - 카테고리: import 시점에 키워드 기반 자동 분류 (category_keywords 테이블 활용)
 * - [[분류:XXX]] / [[Category:XXX]] → tags + post_tags 테이블 (해시태그)
 * - 리다이렉트 제외, 모든 namespace 임포트
 * - author_id는 1~100,000 유저에게 랜덤 균등 배정
 * - created_at이 없으면 2020~2025 범위 내 랜덤 생성
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WikiImportService {

    private final JdbcTemplate jdbcTemplate;

    private static final int BATCH_SIZE = 1000;
    private static final long USER_COUNT = 100_000L;

    /** created_at 랜덤 생성 범위: 2020-01-01 ~ 2025-12-31 */
    private static final long RANDOM_DATE_START = Instant.parse("2020-01-01T00:00:00Z").getEpochSecond();
    private static final long RANDOM_DATE_END = Instant.parse("2025-12-31T23:59:59Z").getEpochSecond();

    /** 한국어 태그 패턴: [[분류:태그명]] 또는 [[분류:태그명|정렬키]] */
    private static final Pattern KO_TAG_PATTERN =
            Pattern.compile("\\[\\[분류:([^|\\]]+)(?:\\|[^\\]]*)?\\]\\]");

    /** 영어 태그 패턴: [[Category:TagName]] 또는 [[Category:TagName|SortKey]] */
    private static final Pattern EN_TAG_PATTERN =
            Pattern.compile("\\[\\[Category:([^|\\]]+)(?:\\|[^\\]]*)?\\]\\]", Pattern.CASE_INSENSITIVE);

    /**
     * 여러 덤프 파일(XML/JSON)을 순서대로 임포트한다.
     */
    public void importFromFiles(List<String> filePaths) {
        Integer existingCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM posts", Integer.class);
        if (existingCount != null && existingCount > 0) {
            log.info("posts 테이블에 이미 {}건의 데이터가 존재합니다. 위키 임포트를 건너뜁니다.", existingCount);
            return;
        }

        // 카테고리 키워드 기반 분류 준비
        Map<String, Long> categoryNameToId = loadCategories();
        Map<String, List<KeywordMapping>> keywordMap = loadCategoryKeywords();
        Long defaultCategoryId = categoryNameToId.getOrDefault("기타", null);

        if (defaultCategoryId == null) {
            log.error("'기타' 카테고리가 존재하지 않습니다. Flyway 시드 데이터를 확인하세요.");
            return;
        }

        log.info("카테고리 {}개, 키워드 {}개 로드 완료 (기본 카테고리: 기타={})",
                categoryNameToId.size(), keywordMap.size(), defaultCategoryId);

        // 태그명 → ID 메모리 캐시
        Map<String, Long> tagCache = new HashMap<>();
        loadExistingTags(tagCache);

        // Import 전 인덱스 비활성화 (INSERT 속도 대폭 향상)
        disableIndexesForImport();

        long totalStart = System.currentTimeMillis();
        for (String filePath : filePaths) {
            importSingleFile(filePath.trim(), categoryNameToId, keywordMap, defaultCategoryId, tagCache);
        }

        // Import 후 인덱스 재활성화 + 재구축
        enableIndexesAfterImport();

        long totalElapsed = (System.currentTimeMillis() - totalStart) / 1000;
        log.info("=== 전체 임포트 완료: {}초 ===", totalElapsed);
    }

    /**
     * 고정 카테고리로 파일을 임포트한다.
     * 뉴스, 웹 콘텐츠 등 키워드 분류가 아닌 소스 단위로 카테고리를 지정할 때 사용.
     * 기존 데이터가 있어도 추가 import 가능 (해당 카테고리에 데이터 없을 때만).
     */
    public void importWithFixedCategory(List<String> filePaths, String categoryName) {
        if (filePaths == null || filePaths.isEmpty()) return;

        Map<String, Long> categoryNameToId = loadCategories();
        Long categoryId = categoryNameToId.get(categoryName);

        if (categoryId == null) {
            // 카테고리가 없으면 생성
            jdbcTemplate.update("INSERT IGNORE INTO categories (name) VALUES (?)", categoryName);
            categoryId = jdbcTemplate.queryForObject("SELECT id FROM categories WHERE name = ?", Long.class, categoryName);
            log.info("카테고리 '{}' 생성 (id={})", categoryName, categoryId);
        }

        // 해당 카테고리에 이미 데이터가 있으면 스킵
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM posts WHERE category_id = ?", Integer.class, categoryId);
        if (count != null && count > 0) {
            log.info("카테고리 '{}'에 이미 {}건의 데이터가 존재합니다. 추가 임포트를 건너뜁니다.", categoryName, count);
            return;
        }

        Map<String, Long> tagCache = new HashMap<>();
        loadExistingTags(tagCache);

        disableIndexesForImport();

        long totalStart = System.currentTimeMillis();
        Long finalCategoryId = categoryId;

        for (String filePath : filePaths) {
            importSingleFileWithFixedCategory(filePath.trim(), finalCategoryId, categoryName, tagCache);
        }

        enableIndexesAfterImport();

        long totalElapsed = (System.currentTimeMillis() - totalStart) / 1000;
        log.info("=== '{}' 임포트 완료: {}초 ===", categoryName, totalElapsed);
    }

    /**
     * 단일 파일을 고정 카테고리로 임포트한다. 키워드 분류 없이 모든 문서를 지정된 카테고리로 저장.
     */
    private void importSingleFileWithFixedCategory(String filePath, Long categoryId, String categoryName,
                                                     Map<String, Long> tagCache) {
        log.info("[{}] 임포트 시작: {}", categoryName, filePath);

        List<WikiPage> batch = new ArrayList<>(BATCH_SIZE);
        List<Long> batchCategoryIds = new ArrayList<>(BATCH_SIZE);
        List<List<Long>> batchTagIds = new ArrayList<>(BATCH_SIZE);
        AtomicLong totalCount = new AtomicLong(0);
        AtomicLong tagCount = new AtomicLong(0);
        long startTime = System.currentTimeMillis();

        Consumer<WikiPage> pageConsumer = page -> {
            if (page.getRedirectTo() != null) return;

            List<String> tagNames = extractTagNames(page.getContent());
            List<Long> tagIds = resolveTagIds(tagNames, tagCache, tagCount);

            batch.add(page);
            batchCategoryIds.add(categoryId);
            batchTagIds.add(tagIds);

            if (batch.size() >= BATCH_SIZE) {
                long beforeMaxId = getMaxPostId();
                savePostsBatch(batch, batchCategoryIds);
                savePostTagsBatch(beforeMaxId, batchTagIds);

                long count = totalCount.addAndGet(batch.size());
                batch.clear();
                batchCategoryIds.clear();
                batchTagIds.clear();

                if (count % 10000 == 0) {
                    long elapsed = System.currentTimeMillis() - startTime;
                    long rate = count / Math.max(elapsed / 1000, 1);
                    log.info("[{}] 임포트 진행: {}건 ({}건/초)", categoryName, count, rate);
                }
            }
        };

        String lowerPath = filePath.toLowerCase();
        if (lowerPath.endsWith(".json")) {
            new NamuWikiJsonParser().parse(filePath, pageConsumer);
        } else if (lowerPath.endsWith(".xml")) {
            new WikiXmlParser().parse(filePath, pageConsumer);
        } else {
            log.error("지원하지 않는 파일 형식: {}", filePath);
            return;
        }

        if (!batch.isEmpty()) {
            long beforeMaxId = getMaxPostId();
            savePostsBatch(batch, batchCategoryIds);
            savePostTagsBatch(beforeMaxId, batchTagIds);
            totalCount.addAndGet(batch.size());
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("[{}] 임포트 완료: {} - {}건, 소요 시간 {}초", categoryName, filePath, totalCount.get(), elapsed / 1000);
    }

    /**
     * Import 전 인덱스/제약조건 비활성화.
     * 매 INSERT마다 B-Tree 재조정 비용을 제거하여 속도를 대폭 향상시킨다.
     * 200만 건 기준 2~5배 빠름.
     */
    private void disableIndexesForImport() {
        log.info("=== Import 최적화: 인덱스/제약 비활성화 ===");
        jdbcTemplate.execute("SET unique_checks = 0");
        jdbcTemplate.execute("SET foreign_key_checks = 0");
        log.info("unique_checks=0, foreign_key_checks=0");
    }

    private void enableIndexesAfterImport() {
        log.info("=== Import 최적화: 인덱스/제약 재활성화 ===");
        jdbcTemplate.execute("SET unique_checks = 1");
        jdbcTemplate.execute("SET foreign_key_checks = 1");
        log.info("인덱스 재활성화 완료");
    }

    /**
     * 단일 덤프 파일을 임포트한다.
     * 파일 확장자로 파서를 자동 선택한다 (.json → NamuWiki, .xml → Wikipedia).
     */
    private void importSingleFile(String filePath,
                                   Map<String, Long> categoryNameToId,
                                   Map<String, List<KeywordMapping>> keywordMap,
                                   Long defaultCategoryId,
                                   Map<String, Long> tagCache) {
        log.info("위키 데이터 임포트 시작: {}", filePath);

        List<WikiPage> batch = new ArrayList<>(BATCH_SIZE);
        List<Long> batchCategoryIds = new ArrayList<>(BATCH_SIZE);
        List<List<Long>> batchTagIds = new ArrayList<>(BATCH_SIZE);
        AtomicLong totalCount = new AtomicLong(0);
        AtomicLong tagCount = new AtomicLong(0);
        Map<String, AtomicLong> categoryStats = new HashMap<>();
        long startTime = System.currentTimeMillis();

        // 카테고리 ID → 이름 역매핑 (통계 출력용)
        Map<Long, String> categoryIdToName = new HashMap<>();
        categoryNameToId.forEach((name, id) -> categoryIdToName.put(id, name));

        Consumer<WikiPage> pageConsumer = page -> {
            // 리다이렉트 문서 제외
            if (page.getRedirectTo() != null) {
                return;
            }

            // ns=0(일반 문서)만 import — 나머지(틀, 분류, 모듈, 파일 등)는 실제 콘텐츠가 아님
            // 나무위키는 namespace가 전부 0이므로 영향 없음
            if (page.getNamespace() != null && page.getNamespace() != 0) {
                return;
            }

            // 1. 본문에서 태그 이름 먼저 추출
            List<String> tagNames = extractTagNames(page.getContent());

            // 2. 제목 + 태그 이름을 합쳐서 키워드 분류
            Long categoryId = classifyByKeywords(page.getTitle(), tagNames, keywordMap, categoryNameToId, defaultCategoryId);

            // 분류 통계 수집
            String categoryName = categoryIdToName.getOrDefault(categoryId, "기타");
            categoryStats.computeIfAbsent(categoryName, k -> new AtomicLong(0)).incrementAndGet();

            // 3. 태그 이름 → 태그 ID 변환 (DB 저장용)
            List<Long> tagIds = resolveTagIds(tagNames, tagCache, tagCount);

            batch.add(page);
            batchCategoryIds.add(categoryId);
            batchTagIds.add(tagIds);

            if (batch.size() >= BATCH_SIZE) {
                long beforeMaxId = getMaxPostId();
                savePostsBatch(batch, batchCategoryIds);
                savePostTagsBatch(beforeMaxId, batchTagIds);

                long count = totalCount.addAndGet(batch.size());
                batch.clear();
                batchCategoryIds.clear();
                batchTagIds.clear();

                if (count % 10000 == 0) {
                    long elapsed = System.currentTimeMillis() - startTime;
                    long rate = count / Math.max(elapsed / 1000, 1);
                    log.info("임포트 진행: {}건 (태그: {}건, {}건/초)",
                            count, tagCount.get(), rate);
                }
            }
        };

        // 파일 확장자에 따라 적절한 파서 선택
        String lowerPath = filePath.toLowerCase();
        if (lowerPath.endsWith(".json")) {
            new NamuWikiJsonParser().parse(filePath, pageConsumer);
        } else if (lowerPath.endsWith(".xml")) {
            new WikiXmlParser().parse(filePath, pageConsumer);
        } else {
            log.error("지원하지 않는 파일 형식: {} (.json 또는 .xml만 지원)", filePath);
            return;
        }

        if (!batch.isEmpty()) {
            long beforeMaxId = getMaxPostId();
            savePostsBatch(batch, batchCategoryIds);
            savePostTagsBatch(beforeMaxId, batchTagIds);
            totalCount.addAndGet(batch.size());
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("위키 데이터 임포트 완료: {} - {}건 (태그: {}건), 소요 시간 {}초",
                filePath, totalCount.get(), tagCount.get(), elapsed / 1000);

        // 카테고리 분류 통계 출력
        log.info("=== 카테고리 분류 통계 ===");
        categoryStats.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
                .forEach(e -> log.info("  {}: {}건", e.getKey(), e.getValue().get()));
    }

    // ── 카테고리 키워드 분류 ──────────────────────────────────────────

    /**
     * categories 테이블에서 카테고리 이름 → ID 매핑을 로드한다.
     */
    private Map<String, Long> loadCategories() {
        Map<String, Long> map = new HashMap<>();
        jdbcTemplate.query("SELECT id, name FROM categories", rs -> {
            map.put(rs.getString("name"), rs.getLong("id"));
        });
        return map;
    }

    /**
     * category_keywords 테이블에서 키워드 → 카테고리 매핑을 로드한다.
     * weight >= 0.8인 키워드만 사용한다.
     */
    private Map<String, List<KeywordMapping>> loadCategoryKeywords() {
        Map<String, List<KeywordMapping>> map = new HashMap<>();
        jdbcTemplate.query(
                "SELECT category_name, keyword, weight FROM category_keywords WHERE weight >= 0.8",
                rs -> {
                    String keyword = rs.getString("keyword").toLowerCase();
                    map.computeIfAbsent(keyword, k -> new ArrayList<>())
                            .add(new KeywordMapping(rs.getString("category_name"), rs.getDouble("weight")));
                }
        );
        return map;
    }

    /**
     * 제목 + 태그 이름에 포함된 키워드를 기반으로 최적 카테고리를 결정한다.
     *
     * 매칭 소스:
     * 1. 제목 — "자바 프로그래밍 입문" → "프로그래밍" 키워드 매칭
     * 2. 태그(분류) — [[분류:대한민국의 축구선수]] → "축구" 키워드 매칭
     *
     * 태그는 위키 편집자가 직접 분류한 것이므로, 제목보다 정확한 분류 신호다.
     * 제목만으로 분류 안 되는 문서도 태그로 분류 가능:
     *   제목 "김연아" → 키워드 매칭 없음
     *   태그 "대한민국의 피겨스케이팅 선수" → "선수" 매칭 → 스포츠
     */
    private Long classifyByKeywords(String title,
                                     List<String> tagNames,
                                     Map<String, List<KeywordMapping>> keywordMap,
                                     Map<String, Long> categoryNameToId,
                                     Long defaultCategoryId) {
        Map<String, Double> scores = new HashMap<>();

        // 1. 제목 매칭
        if (title != null && !title.isEmpty()) {
            String lowerTitle = title.toLowerCase();
            for (Map.Entry<String, List<KeywordMapping>> entry : keywordMap.entrySet()) {
                if (lowerTitle.contains(entry.getKey())) {
                    for (KeywordMapping mapping : entry.getValue()) {
                        scores.merge(mapping.categoryName, mapping.weight, Double::sum);
                    }
                }
            }
        }

        // 2. 태그 이름 매칭 (태그 하나당 매칭되면 가중치 적용)
        for (String tagName : tagNames) {
            String lowerTag = tagName.toLowerCase();
            for (Map.Entry<String, List<KeywordMapping>> entry : keywordMap.entrySet()) {
                if (lowerTag.contains(entry.getKey())) {
                    for (KeywordMapping mapping : entry.getValue()) {
                        scores.merge(mapping.categoryName, mapping.weight * 0.5, Double::sum);
                    }
                }
            }
        }

        if (scores.isEmpty()) return defaultCategoryId;

        String bestCategory = scores.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);

        return categoryNameToId.getOrDefault(bestCategory, defaultCategoryId);
    }

    private record KeywordMapping(String categoryName, double weight) {}

    // ── 태그 추출 ──────────────────────────────────────────────────

    /**
     * 기존 태그를 DB에서 로드하여 캐시에 저장한다.
     */
    private void loadExistingTags(Map<String, Long> tagCache) {
        jdbcTemplate.query("SELECT id, name FROM tags", rs -> {
            tagCache.put(rs.getString("name"), rs.getLong("id"));
        });
        log.info("기존 태그 {}건 로드 완료", tagCache.size());
    }

    /**
     * 본문에서 [[분류:XXX]] / [[Category:XXX]] 패턴의 태그 이름을 추출한다.
     * 카테고리 분류(키워드 매칭)와 태그 저장 모두에 사용된다.
     */
    private List<String> extractTagNames(String content) {
        List<String> names = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            return names;
        }

        Matcher koMatcher = KO_TAG_PATTERN.matcher(content);
        while (koMatcher.find()) {
            String name = koMatcher.group(1).trim();
            if (!name.isEmpty()) {
                names.add(name);
            }
        }

        Matcher enMatcher = EN_TAG_PATTERN.matcher(content);
        while (enMatcher.find()) {
            String name = enMatcher.group(1).trim();
            if (!name.isEmpty()) {
                names.add(name);
            }
        }

        return names;
    }

    /**
     * 태그 이름 목록을 DB 태그 ID 목록으로 변환한다.
     * 존재하지 않는 태그는 INSERT하고 캐시에 저장한다.
     */
    private List<Long> resolveTagIds(List<String> tagNames, Map<String, Long> tagCache, AtomicLong tagCount) {
        List<Long> tagIds = new ArrayList<>(tagNames.size());
        for (String name : tagNames) {
            tagIds.add(getOrCreateTag(name, tagCache, tagCount));
        }
        return tagIds;
    }

    private Long getOrCreateTag(String name, Map<String, Long> tagCache, AtomicLong tagCount) {
        String truncatedName = truncate(name, 255);

        Long cached = tagCache.get(truncatedName);
        if (cached != null) {
            return cached;
        }

        jdbcTemplate.update("INSERT IGNORE INTO tags (name) VALUES (?)", truncatedName);
        Long id = jdbcTemplate.queryForObject("SELECT id FROM tags WHERE name = ?", Long.class, truncatedName);

        tagCache.put(truncatedName, id);
        tagCount.incrementAndGet();
        return id;
    }

    // ── 배치 저장 ──────────────────────────────────────────────────

    private long getMaxPostId() {
        Long maxId = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id), 0) FROM posts", Long.class);
        return maxId != null ? maxId : 0;
    }

    private void savePostsBatch(List<WikiPage> pages, List<Long> categoryIds) {
        String sql = """
            INSERT INTO posts (title, content, author_id, category_id, view_count, like_count, created_at)
            VALUES (?, ?, ?, ?, 0, 0, ?)
            """;

        ThreadLocalRandom random = ThreadLocalRandom.current();
        Object[][] params = new Object[pages.size()][];
        for (int i = 0; i < pages.size(); i++) {
            WikiPage page = pages.get(i);
            long authorId = random.nextLong(1, USER_COUNT + 1);
            Instant createdAt = page.getCreatedAt() != null
                    ? page.getCreatedAt()
                    : randomInstant(random);
            params[i] = new Object[]{
                    truncate(page.getTitle(), 512),
                    page.getContent(),
                    authorId,
                    categoryIds.get(i),
                    createdAt
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

    private Instant randomInstant(ThreadLocalRandom random) {
        long epochSecond = random.nextLong(RANDOM_DATE_START, RANDOM_DATE_END);
        return Instant.ofEpochSecond(epochSecond);
    }

    private void savePostTagsBatch(long beforeMaxId, List<List<Long>> batchTagIds) {
        List<Object[]> postTagParams = new ArrayList<>();

        for (int i = 0; i < batchTagIds.size(); i++) {
            long postId = beforeMaxId + 1 + i;
            List<Long> tagIds = batchTagIds.get(i);
            for (Long tagId : tagIds) {
                postTagParams.add(new Object[]{postId, tagId});
            }
        }

        if (postTagParams.isEmpty()) {
            return;
        }

        String sql = "INSERT IGNORE INTO post_tags (post_id, tag_id) VALUES (?, ?)";
        jdbcTemplate.batchUpdate(sql, postTagParams, BATCH_SIZE, (ps, param) -> {
            ps.setLong(1, (Long) param[0]);
            ps.setLong(2, (Long) param[1]);
        });
    }

    private String truncate(String str, int maxLength) {
        if (str == null || str.length() <= maxLength) return str;
        return str.substring(0, maxLength);
    }
}
