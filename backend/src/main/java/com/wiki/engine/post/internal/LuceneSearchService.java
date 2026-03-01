package com.wiki.engine.post.internal;

import com.wiki.engine.post.Post;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.search.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Lucene 검색 서비스.
 *
 * SearcherManager의 acquire/release 패턴으로 reader를 안전하게 관리한다.
 * 검색 중에 인덱스가 갱신되어도 진행 중인 검색은 이전 스냅샷으로 완료된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LuceneSearchService {

    private final SearcherManager searcherManager;
    private final Analyzer analyzer;
    private final PostRepository postRepository;

    /**
     * 키워드 검색.
     * title과 content 필드를 동시에 검색하며, title에 더 높은 가중치를 부여한다.
     *
     * @return DB에서 조회한 Post 엔티티 Page (Lucene은 ID만 반환, 상세는 DB 조회)
     */
    public Page<Post> search(String keyword, Pageable pageable) throws IOException {
        IndexSearcher searcher = searcherManager.acquire();
        try {
            Query query = buildQuery(keyword);
            int offset = (int) pageable.getOffset();
            int limit = pageable.getPageSize();

            // 전체 매칭 수 파악을 위해 offset + limit 만큼 검색
            TopDocs topDocs = searcher.search(query, offset + limit);
            long totalHits = topDocs.totalHits.value();

            // offset 이후의 결과에서 ID 추출
            StoredFields storedFields = searcher.storedFields();
            List<Long> postIds = new ArrayList<>();
            for (int i = offset; i < Math.min(topDocs.scoreDocs.length, offset + limit); i++) {
                Document doc = storedFields.document(topDocs.scoreDocs[i].doc);
                postIds.add(Long.parseLong(doc.get("id")));
            }

            if (postIds.isEmpty()) {
                return new PageImpl<>(List.of(), pageable, totalHits);
            }

            // Lucene 결과 순서를 유지하며 DB에서 엔티티 조회
            List<Post> posts = postRepository.findAllById(postIds);
            posts.sort((a, b) -> postIds.indexOf(a.getId()) - postIds.indexOf(b.getId()));

            return new PageImpl<>(posts, pageable, totalHits);

        } catch (ParseException e) {
            log.warn("검색어 파싱 실패: keyword={}, error={}", keyword, e.getMessage());
            return new PageImpl<>(List.of(), pageable, 0);
        } finally {
            searcherManager.release(searcher);
        }
    }

    /**
     * title(가중치 3) + content(가중치 1) 멀티필드 쿼리를 생성한다.
     */
    private Query buildQuery(String keyword) throws ParseException {
        var boosts = java.util.Map.of("title", 3.0f, "content", 1.0f);
        var parser = new MultiFieldQueryParser(new String[]{"title", "content"}, analyzer, boosts);
        return parser.parse(MultiFieldQueryParser.escape(keyword));
    }
}
