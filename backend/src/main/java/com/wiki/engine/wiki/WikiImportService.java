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
 * - namespace → categories 테이블 (게시판 개념: 일반 문서, 토론, 사용자 등)
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

    /** namespace 번호 → 카테고리 이름 매핑 */
    private static final Map<Integer, String> NAMESPACE_NAMES = Map.ofEntries(
            Map.entry(0, "일반 문서"),
            Map.entry(1, "토론"),
            Map.entry(2, "사용자"),
            Map.entry(3, "사용자토론"),
            Map.entry(4, "프로젝트"),
            Map.entry(5, "프로젝트토론"),
            Map.entry(6, "파일"),
            Map.entry(7, "파일토론"),
            Map.entry(8, "미디어위키"),
            Map.entry(9, "미디어위키토론"),
            Map.entry(10, "틀"),
            Map.entry(11, "틀토론"),
            Map.entry(12, "도움말"),
            Map.entry(13, "도움말토론"),
            Map.entry(14, "분류"),
            Map.entry(15, "분류토론")
    );

    /**
     * 여러 덤프 파일(XML/JSON)을 순서대로 임포트한다.
     */
    public void importFromXmlFiles(List<String> filePaths) {
        Integer existingCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM posts", Integer.class);
        if (existingCount != null && existingCount > 0) {
            log.info("posts 테이블에 이미 {}건의 데이터가 존재합니다. 위키 임포트를 건너뜁니다.", existingCount);
            return;
        }

        // 카테고리 미리 생성 (나무위키 → 위키피디아 namespace 순서)
        Map<Integer, Long> namespaceCategoryMap = new HashMap<>();
        boolean hasJson = filePaths.stream().anyMatch(p -> p.trim().toLowerCase().endsWith(".json"));
        if (hasJson) {
            getOrCreateNamespaceCategory(-1, "나무위키", namespaceCategoryMap);
        }
        createNamespaceCategories(namespaceCategoryMap);

        // 태그명 → ID 메모리 캐시
        Map<String, Long> tagCache = new HashMap<>();
        loadExistingTags(tagCache);

        for (String filePath : filePaths) {
            importSingleFile(filePath.trim(), namespaceCategoryMap, tagCache);
        }

    }

    /**
     * 단일 덤프 파일을 임포트한다.
     * 파일 확장자로 파서를 자동 선택한다 (.json → NamuWiki, .xml → Wikipedia).
     */
    private void importSingleFile(String filePath, Map<Integer, Long> namespaceCategoryMap,
                                   Map<String, Long> tagCache) {
        log.info("위키 데이터 임포트 시작: {}", filePath);

        boolean isJson = filePath.toLowerCase().endsWith(".json");
        List<WikiPage> batch = new ArrayList<>(BATCH_SIZE);
        List<Long> batchCategoryIds = new ArrayList<>(BATCH_SIZE);
        List<List<Long>> batchTagIds = new ArrayList<>(BATCH_SIZE);
        AtomicLong totalCount = new AtomicLong(0);
        AtomicLong tagCount = new AtomicLong(0);
        long startTime = System.currentTimeMillis();

        Consumer<WikiPage> pageConsumer = page -> {
            // 리다이렉트 문서 제외 (본문이 "#넘겨주기" 한 줄뿐이라 의미 없음)
            if (page.getRedirectTo() != null) {
                return;
            }

            // namespace → 카테고리 ID
            Long categoryId;
            if (isJson) {
                // 나무위키: 전부 "나무위키" 카테고리로 매핑
                categoryId = namespaceCategoryMap.get(-1);
            } else {
                categoryId = namespaceCategoryMap.getOrDefault(page.getNamespace(), null);
                if (categoryId == null) {
                    categoryId = getOrCreateNamespaceCategory(page.getNamespace(), namespaceCategoryMap);
                }
            }

            // 본문에서 태그 추출
            List<Long> tagIds = extractTags(page.getContent(), tagCache, tagCount);

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
        if (isJson) {
            new NamuWikiJsonParser().parse(filePath, pageConsumer);
        } else {
            new WikiXmlParser().parse(filePath, pageConsumer);
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
    }

    /**
     * namespace 기반 카테고리를 미리 생성한다.
     */
    private void createNamespaceCategories(Map<Integer, Long> map) {
        for (var entry : NAMESPACE_NAMES.entrySet()) {
            jdbcTemplate.update(
                    "INSERT IGNORE INTO categories (name) VALUES (?)",
                    entry.getValue()
            );
            Long id = jdbcTemplate.queryForObject(
                    "SELECT id FROM categories WHERE name = ?",
                    Long.class, entry.getValue()
            );
            map.put(entry.getKey(), id);
        }
        log.info("namespace 카테고리 {}건 생성/로드 완료", map.size());
    }

    /**
     * 매핑에 없는 namespace를 동적으로 카테고리로 생성한다.
     */
    private Long getOrCreateNamespaceCategory(int namespace, Map<Integer, Long> namespaceCategoryMap) {
        return getOrCreateNamespaceCategory(namespace, "기타 (ns=" + namespace + ")", namespaceCategoryMap);
    }

    /**
     * 지정된 이름으로 카테고리를 생성하거나 조회한다.
     */
    private Long getOrCreateNamespaceCategory(int namespace, String name,
                                               Map<Integer, Long> namespaceCategoryMap) {
        jdbcTemplate.update(
                "INSERT IGNORE INTO categories (name) VALUES (?)", name
        );
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM categories WHERE name = ?",
                Long.class, name
        );
        namespaceCategoryMap.put(namespace, id);
        return id;
    }

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
     * 본문에서 [[분류:XXX]] / [[Category:XXX]] 패턴을 추출하여 태그로 저장한다.
     *
     * @return 추출된 태그 ID 목록
     */
    private List<Long> extractTags(String content, Map<String, Long> tagCache, AtomicLong tagCount) {
        List<Long> tagIds = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            return tagIds;
        }

        // 한국어 태그 추출: [[분류:태그명]]
        Matcher koMatcher = KO_TAG_PATTERN.matcher(content);
        while (koMatcher.find()) {
            String name = koMatcher.group(1).trim();
            if (!name.isEmpty()) {
                Long id = getOrCreateTag(name, tagCache, tagCount);
                tagIds.add(id);
            }
        }

        // 영어 태그 추출: [[Category:TagName]]
        Matcher enMatcher = EN_TAG_PATTERN.matcher(content);
        while (enMatcher.find()) {
            String name = enMatcher.group(1).trim();
            if (!name.isEmpty()) {
                Long id = getOrCreateTag(name, tagCache, tagCount);
                tagIds.add(id);
            }
        }

        return tagIds;
    }

    /**
     * 태그를 조회하거나 새로 생성한다.
     */
    private Long getOrCreateTag(String name, Map<String, Long> tagCache, AtomicLong tagCount) {
        String truncatedName = truncate(name, 255);

        Long cached = tagCache.get(truncatedName);
        if (cached != null) {
            return cached;
        }

        jdbcTemplate.update(
                "INSERT IGNORE INTO tags (name) VALUES (?)",
                truncatedName
        );

        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM tags WHERE name = ?",
                Long.class, truncatedName
        );

        tagCache.put(truncatedName, id);
        tagCount.incrementAndGet();
        return id;
    }

    /**
     * 현재 posts 테이블의 최대 ID를 반환한다.
     * 배치 INSERT 후 post_tags 매핑에 사용한다.
     */
    private long getMaxPostId() {
        Long maxId = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id), 0) FROM posts", Long.class);
        return maxId != null ? maxId : 0;
    }

    /**
     * WikiPage 목록을 posts 테이블에 배치 INSERT한다.
     * author_id는 1~100,000 범위에서 랜덤 배정, created_at이 null이면 랜덤 생성.
     */
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

    /**
     * 배치 INSERT된 posts에 대해 post_tags를 배치 INSERT한다.
     * beforeMaxId 이후에 삽입된 posts의 ID가 순차적임을 이용한다.
     */
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
