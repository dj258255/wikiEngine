package com.wiki.engine.post;

import com.wiki.engine.post.dto.PostSummaryResponse;
import com.wiki.engine.post.internal.LuceneIndexService;
import com.wiki.engine.post.internal.LuceneSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
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
     * Lucene 검색 테스트.
     * 기존 FULLTEXT 검색과 별도로 Lucene 검색 결과를 확인한다.
     */
    @GetMapping("/search")
    public Page<PostSummaryResponse> search(
            @RequestParam String q,
            @PageableDefault(size = 20) Pageable pageable) throws IOException {

        return luceneSearchService.search(q, pageable).map(PostSummaryResponse::from);
    }
}
