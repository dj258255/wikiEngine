package com.wiki.engine.post.internal.filter;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.ahocorasick.trie.Emit;
import org.ahocorasick.trie.Trie;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 금칙어 필터 서비스 — Aho-Corasick 기반.
 *
 * DB 금칙어(한국어) + 리소스 파일 금칙어(영어)로 Aho-Corasick automaton을 구축하여
 * 텍스트 길이 O(N)에 비례하는 다중 패턴 매칭을 수행한다. 금칙어 수(M)에 무관.
 *
 * 한국어: 부분 일치 — "매춘" → "매춘부" 차단 (Aho-Corasick 기본 동작)
 * 영어: 단어 경계 매칭 — "ass" → "assassination" 허용 (Scunthorpe 문제 방지)
 *       Trie.wholeWords()로 영어 전용 automaton을 별도 구축한다.
 */
@Slf4j
@Service
public class ContentFilterService {

    private final BannedWordRepository bannedWordRepository;

    private record BannedAutomata(Trie koreanTrie, Trie englishTrie) {}

    private final Cache<String, BannedAutomata> automataCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(10))
            .maximumSize(1)
            .build();

    private static final String CACHE_KEY = "banned_automata";

    public ContentFilterService(BannedWordRepository bannedWordRepository) {
        this.bannedWordRepository = bannedWordRepository;
    }

    /**
     * 자동완성 결과에서 금칙어 포함 제안을 필터링한다.
     */
    public List<String> filterSuggestions(List<String> suggestions) {
        BannedAutomata automata = getAutomata();
        if (automata == null) {
            return suggestions;
        }
        return suggestions.stream()
                .filter(s -> !isBanned(s, automata))
                .toList();
    }

    /**
     * Aho-Corasick automaton으로 텍스트 내 금칙어를 O(N) 시간에 탐지한다.
     * N = 텍스트 길이. 금칙어 수에 무관하게 선형 시간.
     */
    private boolean isBanned(String text, BannedAutomata automata) {
        String lower = text.toLowerCase();

        // 한국어: 부분 일치 (합성어 커버)
        Collection<Emit> koreanHits = automata.koreanTrie().parseText(lower);
        if (!koreanHits.isEmpty()) {
            return true;
        }

        // 영어: 단어 경계 매칭 (Scunthorpe 방지)
        Collection<Emit> englishHits = automata.englishTrie().parseText(lower);
        return !englishHits.isEmpty();
    }

    private BannedAutomata getAutomata() {
        return automataCache.get(CACHE_KEY, k -> buildAutomata());
    }

    private BannedAutomata buildAutomata() {
        List<String> dbWords = bannedWordRepository.findAllWords();
        Set<String> koreanWords = new HashSet<>();
        for (String word : dbWords) {
            String trimmed = word.trim().toLowerCase();
            if (!trimmed.isEmpty()) {
                koreanWords.add(trimmed);
            }
        }

        Set<String> englishWords = loadResourceFile("/banned_words_en.txt");

        log.info("금칙어 Aho-Corasick automaton 빌드: 한국어 {}개, 영어 {}개",
                koreanWords.size(), englishWords.size());

        // 한국어: 부분 일치 (기본 동작 — overlapping + case-insensitive)
        Trie koreanTrie = buildTrie(koreanWords, false);

        // 영어: 단어 경계 매칭 (wholeWords — Scunthorpe 방지)
        Trie englishTrie = buildTrie(englishWords, true);

        return new BannedAutomata(koreanTrie, englishTrie);
    }

    private Trie buildTrie(Set<String> words, boolean wholeWords) {
        if (words.isEmpty()) {
            return Trie.builder().build();
        }
        Trie.TrieBuilder builder = Trie.builder()
                .ignoreCase()
                .ignoreOverlaps();
        if (wholeWords) {
            builder.onlyWholeWords();
        }
        words.forEach(builder::addKeyword);
        return builder.build();
    }

    private Set<String> loadResourceFile(String path) {
        Set<String> words = new HashSet<>();
        try (InputStream is = getClass().getResourceAsStream(path)) {
            if (is == null) return words;
            new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                    .lines()
                    .map(String::trim)
                    .map(String::toLowerCase)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .forEach(words::add);
        } catch (Exception e) {
            log.warn("금칙어 파일 로딩 실패 ({}): {}", path, e.getMessage());
        }
        return words;
    }
}
