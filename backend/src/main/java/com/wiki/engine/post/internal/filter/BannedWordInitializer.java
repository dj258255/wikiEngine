package com.wiki.engine.post.internal.filter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 앱 시작 시 banned_words_ko.txt에서 금칙어를 DB로 로딩한다.
 * 이미 존재하는 단어는 무시 (INSERT IGNORE 효과).
 *
 * 출처: LDNOOBWV2/List-of-Dirty-Naughty-Obscene-and-Otherwise-Bad-Words_V2 (3,094개)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BannedWordInitializer {

    private final BannedWordRepository bannedWordRepository;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void loadBannedWords() {
        long existingCount = bannedWordRepository.count();
        if (existingCount > 0) {
            log.info("금칙어 이미 로딩됨: {}개 (스킵)", existingCount);
            return;
        }

        try (InputStream is = getClass().getResourceAsStream("/banned_words_ko.txt")) {
            if (is == null) {
                log.warn("banned_words_ko.txt 파일 없음 — 금칙어 로딩 스킵");
                return;
            }

            List<String> words = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                    .lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .distinct()
                    .toList();

            int inserted = 0;
            for (String word : words) {
                try {
                    BannedWord entity = new BannedWord();
                    entity.setWord(word);
                    entity.setCategory(BannedWord.Category.PROFANITY);
                    bannedWordRepository.save(entity);
                    inserted++;
                } catch (Exception e) {
                    // 중복 무시
                }
            }

            log.info("금칙어 초기 로딩 완료: {}개 삽입 (총 {}개 파일)", inserted, words.size());
        } catch (Exception e) {
            log.error("금칙어 로딩 실패", e);
        }
    }
}
