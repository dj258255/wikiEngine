package com.wiki.engine.post;

import com.wiki.engine.auth.CurrentUser;
import com.wiki.engine.auth.UserPrincipal;
import com.wiki.engine.post.dto.*;
import com.wiki.engine.post.internal.ViewCountService;
import com.wiki.engine.post.internal.rag.AiSummaryDecisionService;
import com.wiki.engine.post.internal.rag.RagService;
import com.wiki.engine.post.internal.rag.RagSummaryResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.List;

/**
 * 게시글 REST API 컨트롤러.
 * WebConfig에 의해 /api/v1.0/posts 경로로 매핑된다.
 *
 * 1단계에서는 의도적으로 비효율적인 방식을 사용한다:
 * - OFFSET 페이지네이션 (깊은 페이지에서 성능 저하)
 * - LIKE '%keyword%' 검색 (Full Table Scan)
 * - 동기 조회수 증가 (Row Lock 경합)
 */
@Slf4j
@RestController
@RequestMapping(path = "/posts", version = "1.0")
@RequiredArgsConstructor
public class PostController {

    private final PostService postService;
    private final ViewCountService viewCountService;
    private final RagService ragService;
    private final AiSummaryDecisionService aiSummaryDecisionService;

    /** 최신 게시글 목록 조회 (Deferred Join + Slice, COUNT(*) 제거) */
    @GetMapping
    public Slice<PostSummaryResponse> getPosts(
            @RequestParam(required = false) Long categoryId,
            @PageableDefault(size = 20) Pageable pageable) {

        Slice<Post> posts = (categoryId != null)
                ? postService.getPostsByCategory(categoryId, pageable)
                : postService.getPosts(pageable);

        return posts.map(PostSummaryResponse::from);
    }

    /**
     * 게시글 상세 조회 + 조회수 증가 (Redis INCR).
     * DB UPDATE 대신 Redis INCR → 30초 주기 배치 flush.
     * GET 요청에서 DB 쓰기를 제거하여 R/W 분리 라우팅 문제 해결.
     */
    @GetMapping("/{id}")
    public PostDetailResponse getPost(@PathVariable Long id) {
        Post post = postService.findByIdCached(id);
        viewCountService.increment(id);
        return PostDetailResponse.from(post);
    }

    /** 게시글 생성 (인증 필요) */
    @PostMapping
    public ResponseEntity<PostDetailResponse> createPost(
            @Valid @RequestBody CreatePostRequest request,
            @CurrentUser UserPrincipal currentUser) {

        Post post = postService.createPost(
                request.title(), request.content(), currentUser.userId(), request.categoryId());

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(PostDetailResponse.from(post));
    }

    /** 게시글 수정 (인증 필요, 작성자만) */
    @PutMapping("/{id}")
    public ResponseEntity<Void> updatePost(
            @PathVariable Long id,
            @Valid @RequestBody UpdatePostRequest request,
            @CurrentUser UserPrincipal currentUser) {

        postService.updatePost(id, request.title(), request.content(), currentUser.userId());
        return ResponseEntity.ok().build();
    }

    /** 게시글 삭제 (인증 필요, 작성자만) */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletePost(
            @PathVariable Long id,
            @CurrentUser UserPrincipal currentUser) {

        postService.deletePost(id, currentUser.userId());
        return ResponseEntity.noContent().build();
    }

    /** 좋아요 (인증 필요) */
    @PostMapping("/{id}/like")
    public ResponseEntity<Void> likePost(
            @PathVariable Long id,
            @CurrentUser UserPrincipal currentUser) {

        boolean liked = postService.likePost(id, currentUser.userId());
        return liked
                ? ResponseEntity.ok().build()
                : ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    /** 좋아요 취소 (인증 필요) */
    @DeleteMapping("/{id}/like")
    public ResponseEntity<Void> unlikePost(
            @PathVariable Long id,
            @CurrentUser UserPrincipal currentUser) {

        boolean unliked = postService.unlikePost(id, currentUser.userId());
        return unliked
                ? ResponseEntity.ok().build()
                : ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    /**
     * 검색 — 검색 결과 + 오타 교정 제안 반환.
     * Phase 17: categoryId 선택적 파라미터.
     * Phase 18: 결과 < 3건이면 오타 교정 제안 ("혹시 OO을 찾으셨나요?") 포함.
     */
    @GetMapping("/search")
    public SearchResponseWithSuggestion search(
            @RequestParam String q,
            @RequestParam(required = false) Long categoryId,
            @PageableDefault(size = 20) Pageable pageable) {

        return postService.search(q, categoryId, pageable);
    }

    /**
     * AI 검색 요약 — SSE 스트리밍 RAG.
     *
     * 현업 패턴(Google AI Overviews, ChatGPT, Perplexity)과 동일하게
     * SSE(Server-Sent Events)로 토큰 단위 스트리밍한다.
     * 검색 결과는 별도 /search API로 즉시 반환되고,
     * AI 요약은 이 엔드포인트에서 한 글자씩 스트리밍된다.
     *
     * 이벤트 타입:
     *   - "delta": 토큰 텍스트 (한 글자~단어 단위)
     *   - "citations": 출처 정보 JSON (스트리밍 완료 후)
     *   - "done": 스트리밍 종료 신호
     *   - "skip": 트리거 조건 미충족 시 (스킵 사유 포함)
     *   - "error": 오류 발생 시
     */
    @GetMapping(value = "/search/ai-summary", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter aiSummaryStream(@RequestParam String q) {
        SseEmitter emitter = new SseEmitter(60_000L);

        // 1. 검색 실행 (Top-5)
        SearchResponseWithSuggestion searchResult = postService.search(q, null, PageRequest.of(0, 5));
        List<PostSearchResponse> results = searchResult.results().getContent();

        // 2. AI 요약 트리거 조건 판단
        AiSummaryDecisionService.Decision decision = aiSummaryDecisionService.decide(q, results);
        if (!decision.shouldGenerate()) {
            try {
                emitter.send(SseEmitter.event().name("skip").data(decision.skipReason()));
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
            return emitter;
        }

        // 3. 별도 스레드에서 SSE 스트리밍 (톰캣 스레드 반환)
        ragService.streamSummary(q, results, emitter);

        return emitter;
    }

    /**
     * 자동완성 v1 (LIKE 'prefix%').
     * 제목 prefix 매칭으로 최대 10건을 조회수 내림차순으로 반환한다.
     */
    @GetMapping("/autocomplete")
    public ResponseEntity<List<String>> autocomplete(@RequestParam String prefix) {
        List<String> suggestions = postService.autocomplete(prefix);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5))
                        .staleWhileRevalidate(Duration.ofSeconds(60)))
                .body(suggestions);
    }
}
