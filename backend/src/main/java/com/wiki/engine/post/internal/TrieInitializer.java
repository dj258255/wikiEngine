package com.wiki.engine.post.internal;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Trie 초기화 및 주기적 갱신.
 *
 * Trie에 넣을 제목을 "DB 인기도(viewCount)"가 아닌 "실제 검색 로그"에서 가져온다.
 * 위키 덤프 데이터는 viewCount가 전부 0이므로, 검색 로그가 유일한 인기도 신호다.
 *
 * 흐름:
 * 1. 앱 기동 → search_logs에서 검색된 쿼리 로드 → Trie에 삽입
 * 2. 검색 로그가 비어있으면 → 빈 Trie (모든 요청이 Lucene fallback)
 * 3. 매일 새벽 3시 → 최근 7일 검색 로그 기반으로 Trie rebuild
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrieInitializer {

    private static final int TRIE_MAX_ENTRIES = 10_000;

    private final AutocompleteTrie trie;
    private final SearchLogRepository searchLogRepository;

    @EventListener(ApplicationReadyEvent.class)
    void initialize() {
        rebuild();
    }

    /**
     * 매일 새벽 3시 Trie rebuild.
     * 최근 7일 검색 로그에서 인기 검색어를 가져와 Trie를 재구축한다.
     * Copy-on-Write: 새 root 빌드 → volatile swap.
     */
    @Scheduled(cron = "0 0 3 * * *")
    @Transactional(readOnly = true)
    public void rebuild() {
        long start = System.nanoTime();

        List<Object[]> topQueries = searchLogRepository.findTopQueriesSince(
                LocalDateTime.now().minusDays(7), TRIE_MAX_ENTRIES);

        TrieNode newRoot = new TrieNode();
        int loaded = 0;

        for (Object[] row : topQueries) {
            String query = (String) row[0];
            long totalCount = ((Number) row[1]).longValue();
            if (query != null && !query.isBlank()) {
                trie.insert(newRoot, query.toLowerCase(), totalCount);
                loaded++;
            }
        }

        trie.swapRoot(newRoot);

        long elapsed = (System.nanoTime() - start) / 1_000_000;
        if (loaded > 0) {
            log.info("Trie rebuilt: {} entries from search logs in {}ms", loaded, elapsed);
        } else {
            log.info("Trie initialized empty (no search logs yet), {}ms. All autocomplete requests will use Lucene fallback.", elapsed);
        }
    }
}
