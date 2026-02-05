package com.wiki.engine.post;

import com.wiki.engine.post.dto.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

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
@RestController
@RequestMapping("/posts")
@RequiredArgsConstructor
public class PostController {

    private final PostService postService;

    /** 게시글 목록 조회 (OFFSET 페이지네이션) */
    @GetMapping
    public Page<PostSummaryResponse> getPosts(
            @RequestParam(required = false) Long categoryId,
            @PageableDefault(size = 20) Pageable pageable) {

        Page<Post> posts = (categoryId != null)
                ? postService.getPostsByCategory(categoryId, pageable)
                : postService.getPosts(pageable);

        return posts.map(PostSummaryResponse::from);
    }

    /** 게시글 상세 조회 (조회수 동기 증가) */
    @GetMapping("/{id}")
    public PostDetailResponse getPost(@PathVariable Long id) {
        Post post = postService.getPostAndIncrementView(id);
        return PostDetailResponse.from(post);
    }

    /** 게시글 생성 (인증 필요) */
    @PostMapping
    public ResponseEntity<PostDetailResponse> createPost(
            @Valid @RequestBody CreatePostRequest request,
            @AuthenticationPrincipal Long userId) {

        Post post = postService.createPost(
                request.title(), request.content(), userId, request.categoryId());

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(PostDetailResponse.from(post));
    }

    /** 게시글 수정 (인증 필요, 작성자만) */
    @PutMapping("/{id}")
    public ResponseEntity<Void> updatePost(
            @PathVariable Long id,
            @Valid @RequestBody UpdatePostRequest request,
            @AuthenticationPrincipal Long userId) {

        postService.updatePost(id, request.title(), request.content(), userId);
        return ResponseEntity.ok().build();
    }

    /** 게시글 삭제 (인증 필요, 작성자만) */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletePost(
            @PathVariable Long id,
            @AuthenticationPrincipal Long userId) {

        postService.deletePost(id, userId);
        return ResponseEntity.noContent().build();
    }

    /** 좋아요 (인증 필요) */
    @PostMapping("/{id}/like")
    public ResponseEntity<Void> likePost(
            @PathVariable Long id,
            @AuthenticationPrincipal Long userId) {

        boolean liked = postService.likePost(id, userId);
        return liked
                ? ResponseEntity.ok().build()
                : ResponseEntity.status(HttpStatus.CONFLICT).build();
    }

    /** 좋아요 취소 (인증 필요) */
    @DeleteMapping("/{id}/like")
    public ResponseEntity<Void> unlikePost(
            @PathVariable Long id,
            @AuthenticationPrincipal Long userId) {

        boolean unliked = postService.unlikePost(id, userId);
        return unliked
                ? ResponseEntity.ok().build()
                : ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    /**
     * 검색 (LIKE '%keyword%' - 의도적 비효율).
     * Full Table Scan이 발생하며, Baseline 측정 대상이다.
     */
    @GetMapping("/search")
    public Page<PostSummaryResponse> search(
            @RequestParam String q,
            @PageableDefault(size = 20) Pageable pageable) {

        return postService.search(q, pageable).map(PostSummaryResponse::from);
    }

    /**
     * 자동완성 v1 (LIKE 'prefix%').
     * 제목 prefix 매칭으로 최대 10건을 조회수 내림차순으로 반환한다.
     */
    @GetMapping("/autocomplete")
    public List<String> autocomplete(@RequestParam String prefix) {
        return postService.autocomplete(prefix);
    }
}
