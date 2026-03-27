package com.wiki.engine.post.internal.filter;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 금칙어 필터 서비스.
 *
 * DB 금칙어(한국어) + 리소스 파일 금칙어(영어)를 Caffeine 캐시에 보관 (TTL 10분).
 * 자동완성 결과에서 금칙어 포함 제안을 필터링한다.
 *
 * 한국어: contains() 부분 일치 — 합성어 필터링 ("매춘" → "매춘부" 차단)
 * 영어: split + HashSet 단어 경계 매칭 — Scunthorpe 문제 방지 ("ass" → "assassination" 허용)
 */
@Slf4j
@Service
public class ContentFilterService {

    private final BannedWordRepository bannedWordRepository;

    private record BannedWords(Set<String> korean, Set<String> english) {}

    private final Cache<String, BannedWords> bannedWordsCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(10))
            .maximumSize(1)
            .build();

    private static final String CACHE_KEY = "banned_words";

    public ContentFilterService(BannedWordRepository bannedWordRepository) {
        this.bannedWordRepository = bannedWordRepository;
    }

    /**
     * 자동완성 결과에서 금칙어 포함 제안을 필터링한다.
     */
    public List<String> filterSuggestions(List<String> suggestions) {
        BannedWords banned = getBannedWords();
        if (banned.korean().isEmpty() && banned.english().isEmpty()) {
            return suggestions;
        }
        return suggestions.stream()
                .filter(s -> !isBanned(s, banned))
                .toList();
    }

    /**
     * 한국어: contains() 부분 일치 (합성어 커버)
     * 영어: split() + HashSet 단어 경계 매칭 (Scunthorpe 방지)
     */
    private boolean isBanned(String text, BannedWords banned) {
        String lower = text.toLowerCase();

        // 한국어 부분 일치
        for (String word : banned.korean()) {
            if (lower.contains(word)) {
                return true;
            }
        }

        // 영어 단어 경계 매칭
        String[] tokens = lower.split("[^a-z0-9]+");
        for (String token : tokens) {
            if (!token.isEmpty() && banned.english().contains(token)) {
                return true;
            }
        }

        return false;
    }

    private BannedWords getBannedWords() {
        return bannedWordsCache.get(CACHE_KEY, k -> {
            // DB에서 한국어 금칙어
            List<String> dbWords = bannedWordRepository.findAllWords();
            Set<String> korean = new HashSet<>(dbWords.stream().map(String::toLowerCase).toList());

            // 리소스 파일에서 영어 금칙어
            Set<String> english = loadResourceFile("/banned_words_en.txt");

            log.info("금칙어 로딩: 한국어 {}개, 영어 {}개", korean.size(), english.size());
            return new BannedWords(korean, english);
        });
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
