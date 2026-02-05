package com.wiki.engine.post;

import com.wiki.engine.post.internal.PostLikeRepository;
import com.wiki.engine.post.internal.PostRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 게시글 비즈니스 로직 서비스.
 * 게시글 CRUD, 조회수 증가, 좋아요/좋아요취소 기능을 제공한다.
 * 기본적으로 읽기 전용 트랜잭션이며, 쓰기 작업은 별도 @Transactional로 관리한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PostService {

    private final PostRepository postRepository;
    private final PostLikeRepository postLikeRepository;

    /**
     * 새 게시글을 생성한다.
     *
     * @param title 제목
     * @param content 본문
     * @param authorId 작성자 ID
     * @param categoryId 카테고리 ID (nullable)
     * @return 생성된 게시글 엔티티
     */
    @Transactional
    public Post createPost(String title, String content, Long authorId, Long categoryId) {
        Post post = Post.builder()
                .title(title)
                .content(content)
                .authorId(authorId)
                .categoryId(categoryId)
                .build();

        return postRepository.save(post);
    }

    /** ID로 게시글을 조회한다. */
    public Optional<Post> findById(Long id) {
        return postRepository.findById(id);
    }

    /**
     * 게시글을 조회하고 조회수를 1 증가시킨다.
     * 조회수 증가는 DB 레벨에서 원자적(atomic) UPDATE로 처리하여 동시성 문제를 방지한다.
     * (UPDATE posts SET view_count = view_count + 1 WHERE id = ?)
     *
     * @param id 게시글 ID
     * @return 조회된 게시글
     */
    @Transactional
    public Post getPostAndIncrementView(Long id) {
        Post post = postRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Post not found: " + id));

        postRepository.incrementViewCount(id);

        return post;
    }

    /**
     * 게시글을 수정한다.
     * 작성자 본인만 수정할 수 있으며, 권한 검증을 포함한다.
     *
     * @param id 게시글 ID
     * @param title 수정할 제목
     * @param content 수정할 본문
     * @param userId 요청한 사용자 ID (작성자 검증용)
     */
    @Transactional
    public void updatePost(Long id, String title, String content, Long userId) {
        Post post = postRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Post not found: " + id));

        if (!post.getAuthorId().equals(userId)) {
            throw new IllegalArgumentException("Not authorized to update this post");
        }

        post.update(title, content);
    }

    /**
     * 게시글을 삭제한다.
     * 작성자 본인만 삭제할 수 있으며, 관련 좋아요 데이터도 함께 삭제한다.
     *
     * @param id 게시글 ID
     * @param userId 요청한 사용자 ID (작성자 검증용)
     */
    @Transactional
    public void deletePost(Long id, Long userId) {
        Post post = postRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Post not found: " + id));

        if (!post.getAuthorId().equals(userId)) {
            throw new IllegalArgumentException("Not authorized to delete this post");
        }

        // 게시글에 달린 좋아요를 먼저 삭제한 뒤 게시글 삭제
        postLikeRepository.deleteByPostId(id);
        postRepository.delete(post);
    }

    /**
     * 게시글에 좋아요를 누른다.
     * INSERT IGNORE를 사용하여 이미 좋아요를 누른 경우 무시한다.
     * UNIQUE 제약(post_id, user_id)으로 중복 방지, 동시 요청에도 안전하다.
     *
     * @param postId 게시글 ID
     * @param userId 사용자 ID
     * @return 새로 좋아요가 추가되면 true, 이미 눌렀으면 false
     */
    @Transactional
    public boolean likePost(Long postId, Long userId) {
        int inserted = postLikeRepository.insertIgnore(postId, userId);

        if (inserted > 0) {
            postRepository.incrementLikeCount(postId);
            return true;
        }
        return false;
    }

    /**
     * 게시글 좋아요를 취소한다.
     *
     * @param postId 게시글 ID
     * @param userId 사용자 ID
     * @return 좋아요가 취소되면 true, 좋아요 기록이 없으면 false
     */
    @Transactional
    public boolean unlikePost(Long postId, Long userId) {
        int deleted = postLikeRepository.deleteByPostIdAndUserId(postId, userId);

        if (deleted > 0) {
            postRepository.decrementLikeCount(postId);
            return true;
        }
        return false;
    }

    /**
     * 사용자가 특정 게시글에 좋아요를 눌렀는지 확인한다.
     *
     * @param postId 게시글 ID
     * @param userId 사용자 ID
     * @return 좋아요를 눌렀으면 true
     */
    public boolean hasUserLiked(Long postId, Long userId) {
        return postLikeRepository.existsByPostIdAndUserId(postId, userId);
    }

    /**
     * 게시글 목록을 페이지네이션으로 조회한다.
     * OFFSET 기반 페이지네이션 (1단계 - 의도적 비효율).
     */
    public Page<Post> getPosts(Pageable pageable) {
        return postRepository.findAllByOrderByCreatedAtDesc(pageable);
    }

    /**
     * 카테고리별 게시글 목록을 조회한다.
     */
    public Page<Post> getPostsByCategory(Long categoryId, Pageable pageable) {
        return postRepository.findByCategoryIdOrderByCreatedAtDesc(categoryId, pageable);
    }

    /**
     * 제목+본문 LIKE 검색 (1단계 - 의도적 비효율).
     * LIKE '%keyword%'이므로 Full Table Scan이 발생한다.
     */
    public Page<Post> search(String keyword, Pageable pageable) {
        return postRepository.searchByKeyword(keyword, pageable);
    }

    /**
     * 자동완성 v1: 제목 prefix 매칭.
     * LIKE 'prefix%'로 최대 10건을 조회수 내림차순으로 반환한다.
     */
    public List<String> autocomplete(String prefix) {
        return postRepository.findByTitleStartingWith(prefix, PageRequest.of(0, 10))
                .stream()
                .map(Post::getTitle)
                .toList();
    }
}
