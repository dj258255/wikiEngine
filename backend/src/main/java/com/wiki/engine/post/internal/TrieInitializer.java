package com.wiki.engine.post.internal;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 애플리케이션 시작 시 Trie를 초기화한다.
 * MySQL에서 인기도(viewCount + likeCount) 상위 1만 건의 제목을 로드.
 *
 * Lucene 전체 순회(1,425만 건)가 아닌 MySQL을 사용하는 이유:
 * - Lucene Stored field 접근은 디스크 I/O 발생 → 기동 시간 수십 초 증가
 * - MySQL ORDER BY (view_count + like_count) DESC LIMIT 10000이 더 빠르고 직관적
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrieInitializer {

    private static final int TRIE_MAX_TITLES = 10_000;

    private final AutocompleteTrie trie;
    private final PostRepository postRepository;

    @PostConstruct
    void initialize() {
        long start = System.nanoTime();

        List<String> titles = postRepository.findTopTitlesByPopularity(TRIE_MAX_TITLES);

        TrieNode newRoot = new TrieNode();
        for (String title : titles) {
            if (title != null && !title.isBlank()) {
                trie.insert(newRoot, title.toLowerCase(), 1.0);
            }
        }
        trie.swapRoot(newRoot);

        long elapsed = (System.nanoTime() - start) / 1_000_000;
        log.info("Trie initialized: {} titles loaded in {}ms", titles.size(), elapsed);
    }
}
