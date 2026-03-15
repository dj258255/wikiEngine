package com.wiki.engine.post.internal;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 애플리케이션 기동 완료 후 Trie를 초기화한다.
 *
 * @PostConstruct 대신 ApplicationReadyEvent를 사용하는 이유:
 * - @PostConstruct는 빈 초기화 단계에서 실행되어 앱 기동을 블로킹한다
 * - 1만 건 로드가 느리면 health check 타임아웃으로 배포 실패
 * - ApplicationReadyEvent는 앱이 트래픽을 받을 준비가 된 후 실행
 * - Trie가 비어 있는 짧은 시간 동안은 Lucene PrefixQuery fallback이 동작
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrieInitializer {

    private static final int TRIE_MAX_TITLES = 10_000;

    private final AutocompleteTrie trie;
    private final PostRepository postRepository;

    @EventListener(ApplicationReadyEvent.class)
    void initialize() {
        long start = System.nanoTime();

        List<String> titles = postRepository.findTopTitles(TRIE_MAX_TITLES);

        TrieNode newRoot = new TrieNode();
        int loaded = 0;
        for (String title : titles) {
            if (title != null && !title.isBlank()) {
                trie.insert(newRoot, title.toLowerCase(), 1.0);
                loaded++;
            }
        }
        trie.swapRoot(newRoot);

        long elapsed = (System.nanoTime() - start) / 1_000_000;
        log.info("Trie initialized: {} titles loaded in {}ms", loaded, elapsed);
    }
}
