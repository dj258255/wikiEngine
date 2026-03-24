package com.wiki.engine.post.internal;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.queries.mlt.MoreLikeThis;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TopDocs;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.StringReader;
import java.util.*;

/**
 * MoreLikeThis 기반 카테고리 추천 서비스.
 *
 * 새 게시글 작성 시, 기존 분류된 문서와의 TF-IDF 유사도를 비교하여
 * 가장 적합한 카테고리를 추천한다.
 *
 * 초기 1,425만 건 분류: 키워드 매칭 배치 (CategoryClassificationService)
 * 이후 새 게시글:       MoreLikeThis 실시간 추천 (이 서비스)
 *
 * MoreLikeThis는 ML이 아니다 — Lucene 내장 TF-IDF 유사 문서 검색.
 * 별도 모델 학습 없음, GPU 불필요, 일반 검색과 비슷한 CPU 사용.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CategoryRecommendService {

    private final SearcherManager searcherManager;
    private final Analyzer analyzer;
    private final PostRepository postRepository;

    private static final int TOP_N = 10;       // 유사 문서 상위 10개
    private static final int MIN_TERM_FREQ = 2; // 최소 term 빈도
    private static final int MIN_DOC_FREQ = 5;  // 최소 문서 빈도

    /**
     * 제목 + 본문을 기반으로 가장 적합한 카테고리 ID를 추천한다.
     *
     * @param title   게시글 제목
     * @param content 게시글 본문
     * @return 추천 카테고리 ID (추천 불가 시 null)
     */
    public Long recommendCategory(String title, String content) {
        if (title == null || title.isBlank()) {
            return null;
        }

        IndexSearcher searcher;
        try {
            searcher = searcherManager.acquire();
        } catch (IOException e) {
            log.warn("SearcherManager acquire 실패", e);
            return null;
        }

        try {
            MoreLikeThis mlt = new MoreLikeThis(searcher.getIndexReader());
            mlt.setAnalyzer(analyzer);
            mlt.setFieldNames(new String[]{"title", "content"});
            mlt.setMinTermFreq(MIN_TERM_FREQ);
            mlt.setMinDocFreq(MIN_DOC_FREQ);
            mlt.setMinWordLen(2);  // 한국어: 최소 2글자

            // 제목 + 본문 앞 500자로 유사 문서 검색
            String text = title;
            if (content != null && !content.isBlank()) {
                text += " " + content.substring(0, Math.min(content.length(), 500));
            }

            Query query = mlt.like("content", new StringReader(text));
            TopDocs topDocs = searcher.search(query, TOP_N);

            if (topDocs.scoreDocs.length == 0) {
                return null;
            }

            // 상위 N개 유사 문서의 카테고리를 집계하여 가장 빈도 높은 것 추천
            StoredFields storedFields = searcher.storedFields();
            Map<Long, Integer> categoryVotes = new HashMap<>();

            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                Document doc = storedFields.document(scoreDoc.doc);
                String categoryIdStr = doc.get("categoryId");
                if (categoryIdStr != null) {
                    try {
                        long catId = Long.parseLong(categoryIdStr);
                        categoryVotes.merge(catId, 1, Integer::sum);
                    } catch (NumberFormatException ignored) {}
                }
            }

            if (categoryVotes.isEmpty()) {
                return null;
            }

            // 가장 많이 투표된 카테고리 반환
            return categoryVotes.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse(null);

        } catch (IOException e) {
            log.warn("MoreLikeThis 카테고리 추천 실패: title={}", title, e);
            return null;
        } finally {
            try {
                searcherManager.release(searcher);
            } catch (IOException e) {
                log.warn("SearcherManager release 실패", e);
            }
        }
    }

    /**
     * 추천 카테고리 ID + 이름을 함께 반환.
     */
    public Optional<CategoryRecommendation> recommendWithName(String title, String content) {
        Long categoryId = recommendCategory(title, content);
        if (categoryId == null) {
            return Optional.empty();
        }

        // DB에서 카테고리 이름 조회는 PostService에서 처리
        return Optional.of(new CategoryRecommendation(categoryId));
    }

    public record CategoryRecommendation(Long categoryId) {}
}
