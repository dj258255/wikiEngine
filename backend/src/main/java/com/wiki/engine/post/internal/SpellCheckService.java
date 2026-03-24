package com.wiki.engine.post.internal;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.spell.DirectSpellChecker;
import org.apache.lucene.search.spell.SuggestWord;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 오타 교정 서비스 — Lucene DirectSpellChecker 기반.
 *
 * 인덱스의 term dictionary를 사전으로 활용하여
 * 편집 거리(Damerau-Levenshtein) 기반 오타 교정을 수행한다.
 * 별도 사전 구축 없이, 인덱스가 곧 사전이다.
 *
 * 의존성: lucene-suggest (org.apache.lucene:lucene-suggest:10.3.2)
 *
 * 한국어 한계:
 * - 음절 단위 비교이므로 "컴퓨텨"→"컴퓨터"(편집 거리 1)는 잡히지만,
 *   Nori가 복합어를 분해하므로 인덱스 term이 원형과 다를 수 있다.
 * - 이 한계는 검색 로그 기반 "Did you mean?"으로 보강 가능.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SpellCheckService {

    private final SearcherManager searcherManager;

    /**
     * 검색어의 오타를 교정하여 제안을 반환한다.
     *
     * @param query 사용자 원본 검색어
     * @return 교정된 검색어 (교정이 없으면 empty)
     */
    public Optional<String> suggestCorrection(String query) {
        if (query == null || query.isBlank() || query.length() < 2) {
            return Optional.empty();
        }

        IndexSearcher searcher;
        try {
            searcher = searcherManager.acquire();
        } catch (IOException e) {
            log.warn("SearcherManager acquire 실패", e);
            return Optional.empty();
        }

        try {
            DirectSpellChecker spellChecker = new DirectSpellChecker();
            spellChecker.setMaxEdits(2);        // 최대 편집 거리 2
            spellChecker.setMinPrefix(1);        // 첫 글자는 일치해야 함
            spellChecker.setMinQueryLength(2);   // 2글자 미만은 교정 안 함

            String[] tokens = query.split("\\s+");
            List<String> corrected = new ArrayList<>();
            boolean hasCorrected = false;

            for (String token : tokens) {
                if (token.isBlank()) continue;

                // title 필드의 term dictionary에서 유사 단어 검색
                SuggestWord[] suggestions = spellChecker.suggestSimilar(
                        new Term("title", token.toLowerCase()),
                        1,  // 최대 1개 제안
                        searcher.getIndexReader()
                );

                if (suggestions.length > 0 && !suggestions[0].string.equalsIgnoreCase(token)) {
                    corrected.add(suggestions[0].string);
                    hasCorrected = true;
                } else {
                    corrected.add(token);
                }
            }

            return hasCorrected
                    ? Optional.of(String.join(" ", corrected))
                    : Optional.empty();

        } catch (IOException e) {
            log.warn("오타 교정 실패: query={}", query, e);
            return Optional.empty();
        } finally {
            try {
                searcherManager.release(searcher);
            } catch (IOException e) {
                log.warn("SearcherManager release 실패", e);
            }
        }
    }
}
