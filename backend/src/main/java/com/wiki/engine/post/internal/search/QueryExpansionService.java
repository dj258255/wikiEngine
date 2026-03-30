package com.wiki.engine.post.internal.search;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 쿼리 확장 서비스 — 동의어를 활용하여 검색 Recall을 개선한다.
 *
 * "AI" 검색 시 → ["AI", "인공지능"] 으로 확장하여 BooleanQuery(SHOULD)로 검색.
 * 동의어 사전은 DB에서 관리하며, Caffeine 캐시(30분 TTL)로 DB 조회를 최소화한다.
 *
 * DB 기반 쿼리 타임 확장.
 * 향후: SynonymGraphFilter(파일 기반)로 전환 가능.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueryExpansionService {

    private final SynonymRepository synonymRepository;

    private static final int MAX_SYNONYMS_PER_TERM = 3;

    // 동의어 캐시: term → List<ExpandedTerm> (30분 TTL)
    // DB 조회 비용 ~1-2ms, 캐시 히트 시 ~0.01ms
    private final Cache<String, List<ExpandedTerm>> synonymCache = Caffeine.newBuilder()
            .maximumSize(5_000)
            .expireAfterWrite(Duration.ofMinutes(30))
            .build();

    /**
     * 원래 검색어의 각 토큰에 대해 동의어를 찾아 확장한다.
     *
     * @param originalTerms Nori 형태소 분석 후의 토큰 목록
     * @return 원래 term + 동의어가 포함된 확장 목록
     */
    public List<ExpandedTerm> expand(List<String> originalTerms) {
        List<ExpandedTerm> expanded = new ArrayList<>();

        for (String term : originalTerms) {
            // 원래 term은 항상 boost=1.0
            expanded.add(new ExpandedTerm(term, 1.0, true));

            // 캐시에서 동의어 조회 (미스 시 DB 조회)
            List<ExpandedTerm> synonyms = synonymCache.get(term.toLowerCase(), this::loadSynonyms);
            if (synonyms != null) {
                expanded.addAll(synonyms);
            }
        }

        return expanded;
    }

    private List<ExpandedTerm> loadSynonyms(String term) {
        return synonymRepository.findByTermIgnoreCase(term).stream()
                .limit(MAX_SYNONYMS_PER_TERM)
                .map(s -> new ExpandedTerm(s.getSynonym(), s.getWeight(), false))
                .toList();
    }

    /**
     * 확장된 검색어 정보.
     *
     * @param term     검색어 (원래 or 동의어)
     * @param boost    가중치 (원래 term=1.0, 동의어=DB weight)
     * @param original 원래 term인지 여부
     */
    public record ExpandedTerm(String term, double boost, boolean original) {}
}
