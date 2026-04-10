package com.wiki.engine.post;

import com.wiki.engine.auth.CurrentUser;
import com.wiki.engine.auth.UserPrincipal;
import com.wiki.engine.post.dto.*;
import com.wiki.engine.post.internal.ViewCountService;
import com.wiki.engine.post.internal.search.ClickLogService;
import com.wiki.engine.post.internal.rag.AiFeedbackRequest;
import com.wiki.engine.post.internal.rag.AiFeedbackService;
import com.wiki.engine.post.internal.rag.AiSummaryDecisionService;
import com.wiki.engine.post.internal.rag.RagService;
import com.wiki.engine.post.internal.rag.RagSummaryResponse;
import com.wiki.engine.user.UserService;
import org.springframework.security.core.context.SecurityContextHolder;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
    private final UserService userService;
    private final ViewCountService viewCountService;
    private final RagService ragService;
    private final AiSummaryDecisionService aiSummaryDecisionService;
    private final AiFeedbackService aiFeedbackService;
    private final ClickLogService clickLogService;

    /** 최신 게시글 목록 조회 (Deferred Join + Slice, COUNT(*) 제거) */
    @GetMapping
    public Slice<PostSummaryResponse> getPosts(
            @RequestParam(required = false) Long categoryId,
            @PageableDefault(size = 20) Pageable pageable) {

        Slice<Post> posts = (categoryId != null)
                ? postService.getPostsByCategory(categoryId, pageable)
                : postService.getPosts(pageable);

        Map<Long, String> nicknames = resolveNicknames(posts.getContent());
        return posts.map(p -> PostSummaryResponse.from(p, nicknames.get(p.getAuthorId())));
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
        String nickname = userService.getNicknamesByIds(Set.of(post.getAuthorId()))
                .get(post.getAuthorId());

        // 비로그인 사용자도 조회 가능 — @CurrentUser는 인증 필수라 직접 SecurityContext 확인
        boolean liked = false;
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof UserPrincipal principal) {
            liked = postService.hasUserLiked(id, principal.userId());
        }
        return PostDetailResponse.from(post, nickname, liked);
    }

    /** 게시글 생성 (인증 필요) */
    @PostMapping
    public ResponseEntity<PostDetailResponse> createPost(
            @Valid @RequestBody CreatePostRequest request,
            @CurrentUser UserPrincipal currentUser) {

        Post post = postService.createPost(
                request.title(), request.content(), currentUser.userId(), request.categoryId());

        String nickname = userService.getNicknamesByIds(Set.of(currentUser.userId()))
                .get(currentUser.userId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(PostDetailResponse.from(post, nickname, false));
    }

    /** 게시글 수정 (인증 필요, 작성자만) — 수정된 게시글을 반환한다. */
    @PutMapping("/{id}")
    public PostDetailResponse updatePost(
            @PathVariable Long id,
            @Valid @RequestBody UpdatePostRequest request,
            @CurrentUser UserPrincipal currentUser) {

        Post post = postService.updatePost(id, request.title(), request.content(), currentUser.userId());
        String nickname = userService.getNicknamesByIds(Set.of(currentUser.userId()))
                .get(currentUser.userId());
        boolean liked = postService.hasUserLiked(id, currentUser.userId());
        return PostDetailResponse.from(post, nickname, liked);
    }

    /** 게시글 삭제 (인증 필요, 작성자만) */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deletePost(
            @PathVariable Long id,
            @CurrentUser UserPrincipal currentUser) {

        postService.deletePost(id, currentUser.userId());
    }

    /** 좋아요 (인증 필요) — 현재 좋아요 수와 상태를 반환한다. */
    @PostMapping("/{id}/like")
    public LikeResponse likePost(
            @PathVariable Long id,
            @CurrentUser UserPrincipal currentUser) {

        return postService.likePost(id, currentUser.userId());
    }

    /** 좋아요 취소 (인증 필요) — 현재 좋아요 수와 상태를 반환한다. */
    @DeleteMapping("/{id}/like")
    public LikeResponse unlikePost(
            @PathVariable Long id,
            @CurrentUser UserPrincipal currentUser) {

        return postService.unlikePost(id, currentUser.userId());
    }

    /**
     * 검색 — 검색 결과 + 오타 교정 제안 반환.
     * categoryId 선택적 파라미터.
     * 결과 < 3건이면 오타 교정 제안 ("혹시 OO을 찾으셨나요?") 포함.
     */
    @GetMapping("/search")
    public SearchResponseWithSuggestion search(
            @RequestParam String q,
            @RequestParam(required = false) Long categoryId,
            @PageableDefault(size = 20) Pageable pageable) {

        SearchResponseWithSuggestion result = postService.search(q, categoryId, pageable);
        List<PostSearchResponse> content = result.results().getContent();

        Map<Long, String> nicknames = resolveSearchNicknames(content);
        List<PostSearchResponse> enriched = content.stream()
                .map(r -> r.withNickname(nicknames.get(r.authorId())))
                .toList();

        Slice<PostSearchResponse> enrichedSlice = new SliceImpl<>(
                enriched, result.results().getPageable(), result.results().hasNext());
        return new SearchResponseWithSuggestion(enrichedSlice, result.suggestion(), result.categoryFacets());
    }

    /**
     * AI 검색 요약 — SSE 스트리밍 RAG.
     *
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
    public SseEmitter aiSummaryStream(@RequestParam String q,
                                      jakarta.servlet.http.HttpServletResponse response) {
        // Nginx SSE 버퍼링 비활성화 — 토큰 단위 스트리밍이 즉시 전달되도록
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache");
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
     * AI 요약 피드백 — "이 답변이 도움이 되었나요?"
     * thumbs up/down + 카테고리 + 코멘트.
     * Grafana에서 ai_summary_feedback_total{rating=up/down} 메트릭으로 품질 추이 모니터링.
     */
    @PostMapping("/search/ai-summary/feedback")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void aiSummaryFeedback(
            @Valid @RequestBody AiFeedbackRequest request) {

        aiFeedbackService.saveFeedback(request, null);
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

    /**
     * 검색 결과 클릭 이벤트를 기록한다.
     * 프론트엔드에서 검색 결과 클릭 시 호출.
     * Kafka topic "search.clicks"에 produce + DB 저장.
     */
    @PostMapping("/{id}/click")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void recordClick(
            @PathVariable Long id,
            @RequestParam String q,
            @RequestParam short position,
            @RequestParam(required = false) String sessionId,
            @CurrentUser UserPrincipal user) {
        clickLogService.recordClick(
                q, id, position, sessionId,
                user != null ? user.userId() : null);
    }

    /**
     * Dwell time(체류시간)을 업데이트한다.
     * 프론트엔드에서 페이지 이탈 시 Beacon API(navigator.sendBeacon)로 호출.
     */
    @PostMapping("/{id}/dwell")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void recordDwell(
            @PathVariable Long id,
            @RequestParam String sessionId,
            @RequestParam long dwellTimeMs) {
        clickLogService.updateDwellTime(sessionId, id, dwellTimeMs);
    }

    /** Post 목록에서 authorId를 추출하여 닉네임을 배치 조회한다. */
    private Map<Long, String> resolveNicknames(List<Post> posts) {
        Set<Long> authorIds = posts.stream()
                .map(Post::getAuthorId)
                .collect(Collectors.toSet());
        return userService.getNicknamesByIds(authorIds);
    }

    /** 검색 결과에서 authorId를 추출하여 닉네임을 배치 조회한다. */
    private Map<Long, String> resolveSearchNicknames(List<PostSearchResponse> results) {
        Set<Long> authorIds = results.stream()
                .map(PostSearchResponse::authorId)
                .collect(Collectors.toSet());
        return userService.getNicknamesByIds(authorIds);
    }
}
