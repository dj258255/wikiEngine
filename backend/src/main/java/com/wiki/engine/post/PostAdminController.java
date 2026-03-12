package com.wiki.engine.post;

import com.wiki.engine.common.BusinessException;
import com.wiki.engine.common.ErrorCode;
import com.wiki.engine.post.dto.PostSummaryResponse;
import com.wiki.engine.post.internal.LuceneIndexService;
import com.wiki.engine.post.internal.LuceneSearchService;
import com.wiki.engine.post.internal.PostRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.analysis.tokenattributes.TypeAttribute;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Lucene 관리용 엔드포인트.
 * 배치 인덱싱 트리거 및 Lucene 검색 테스트용.
 */
@Slf4j
@RestController
@RequestMapping(path = "/admin/lucene", version = "1.0")
@RequiredArgsConstructor
public class PostAdminController {

    private final LuceneIndexService luceneIndexService;
    private final LuceneSearchService luceneSearchService;
    private final PostRepository postRepository;
    private final Analyzer analyzer;

    /**
     * 전체 배치 인덱싱 트리거.
     * posts 테이블 전체를 Lucene에 인덱싱한다.
     */
    @PostMapping("/index-all")
    public ResponseEntity<Map<String, String>> indexAll(
            @RequestParam(defaultValue = "0") long startId) throws IOException {
        long start = System.currentTimeMillis();
        luceneIndexService.indexAll(startId);
        long elapsed = System.currentTimeMillis() - start;

        return ResponseEntity.ok(Map.of(
                "status", "completed",
                "elapsed", elapsed + "ms"
        ));
    }

    /**
     * 특정 게시글만 재인덱싱.
     * DB에서 최신 데이터를 읽어 Lucene 인덱스를 갱신한다.
     * viewCount/likeCount 변경 후 전체 재인덱싱 없이 즉시 반영할 때 사용.
     */
    @PostMapping("/reindex")
    public ResponseEntity<Map<String, Object>> reindex(@RequestParam List<Long> ids) throws IOException {
        List<Long> indexed = new ArrayList<>();
        List<Long> notFound = new ArrayList<>();

        for (Long id : ids) {
            Post post = postRepository.findById(id).orElse(null);
            if (post == null) {
                notFound.add(id);
                continue;
            }
            luceneIndexService.indexPost(post);
            indexed.add(id);
        }

        return ResponseEntity.ok(Map.of(
                "indexed", indexed,
                "notFound", notFound
        ));
    }

    /**
     * Lucene 검색 테스트.
     * 기존 FULLTEXT 검색과 별도로 Lucene 검색 결과를 확인한다.
     */
    @GetMapping("/search")
    public Page<PostSummaryResponse> search(
            @RequestParam String q,
            @PageableDefault(size = 20) Pageable pageable) throws IOException {

        return luceneSearchService.search(q, pageable).map(PostSummaryResponse::from);
    }

    /**
     * Nori 형태소 분석 결과 확인.
     * 입력 텍스트를 KoreanAnalyzer가 어떤 토큰으로 분해하는지 반환한다.
     * ngram과의 비교 검증용.
     */
    @GetMapping("/analyze")
    public List<Map<String, String>> analyze(@RequestParam String text) throws IOException {
        List<Map<String, String>> tokens = new ArrayList<>();
        try (TokenStream stream = analyzer.tokenStream("content", text)) {
            CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
            TypeAttribute type = stream.addAttribute(TypeAttribute.class);
            stream.reset();
            while (stream.incrementToken()) {
                tokens.add(Map.of("token", term.toString(), "type", type.type()));
            }
            stream.end();
        }
        return tokens;
    }
}
