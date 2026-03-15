package com.wiki.engine.post;

import com.wiki.engine.common.BusinessException;
import com.wiki.engine.common.ErrorCode;
import com.wiki.engine.post.internal.LuceneIndexService;
import com.wiki.engine.post.internal.AutocompleteTrie;
import com.wiki.engine.post.internal.SearchLogCollector;
import com.wiki.engine.post.internal.LuceneSearchService;
import com.wiki.engine.post.internal.PostLikeRepository;
import com.wiki.engine.post.internal.PostRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;

/**
 * 게시글 비즈니스 로직 서비스.
 * 게시글 CRUD, 조회수 증가, 좋아요/좋아요취소 기능을 제공한다.
 * 기본적으로 읽기 전용 트랜잭션이며, 쓰기 작업은 별도 @Transactional로 관리한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PostService {

    private static final int MAX_LIST_PAGE = 15;
    private static final int MAX_SEARCH_PAGE = 15;

    private final PostRepository postRepository;
    private final PostLikeRepository postLikeRepository;
    private final LuceneIndexService luceneIndexService;
    private final LuceneSearchService luceneSearchService;
    private final SearchLogCollector searchLogCollector;
    private final AutocompleteTrie autocompleteTrie;

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

        Post saved = postRepository.save(post);
        indexSafely(saved);
        return saved;
    }

    /** ID로 게시글을 조회한다 (캐시 적용). */
    @Cacheable(value = "postDetail", key = "#id")
    public Post findByIdCached(Long id) {
        return postRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.POST_NOT_FOUND));
    }

    /** ID로 게시글을 조회한다 (캐시 미적용, 내부용). */
    public Optional<Post> findById(Long id) {
        return postRepository.findById(id);
    }

    /** 조회수를 1 증가시킨다. 캐시와 무관하게 항상 DB 업데이트. */
    @Transactional
    public void incrementViewCount(Long id) {
        postRepository.incrementViewCount(id);
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
    @CacheEvict(value = "postDetail", key = "#id")
    @Transactional
    public void updatePost(Long id, String title, String content, Long userId) {
        Post post = postRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.POST_NOT_FOUND));

        if (!post.getAuthorId().equals(userId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }

        post.update(title, content);
        indexSafely(post);
    }

    /**
     * 게시글을 삭제한다.
     * 작성자 본인만 삭제할 수 있으며, 관련 좋아요 데이터도 함께 삭제한다.
     *
     * @param id 게시글 ID
     * @param userId 요청한 사용자 ID (작성자 검증용)
     */
    @CacheEvict(value = "postDetail", key = "#id")
    @Transactional
    public void deletePost(Long id, Long userId) {
        Post post = postRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.POST_NOT_FOUND));

        if (!post.getAuthorId().equals(userId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }

        // 게시글에 달린 좋아요를 먼저 삭제한 뒤 게시글 삭제
        postLikeRepository.deleteByPostId(id);
        postRepository.delete(post);
        deleteFromIndexSafely(id);
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
     * 최신 게시글 목록을 페이지네이션으로 조회한다.
     * Deferred Join + 수동 SliceImpl으로 COUNT(*) 완전 제거.
     * LIMIT+1 패턴으로 다음 페이지 존재 여부를 판별한다.
     */
    public Slice<Post> getPosts(Pageable pageable) {
        validatePageLimit(pageable, MAX_LIST_PAGE);

        int pageSize = pageable.getPageSize();
        long offset = pageable.getOffset();

        List<Post> results = postRepository.findAllWithDeferredJoin(pageSize + 1, offset);

        boolean hasNext = results.size() > pageSize;
        if (hasNext) {
            results = results.subList(0, pageSize);
        }

        return new SliceImpl<>(results, pageable, hasNext);
    }

    /**
     * 카테고리별 게시글 목록을 조회한다.
     * 파생 쿼리이므로 Slice 반환으로 COUNT(*) 자동 제거.
     */
    public Slice<Post> getPostsByCategory(Long categoryId, Pageable pageable) {
        validatePageLimit(pageable, MAX_LIST_PAGE);
        return postRepository.findByCategoryIdOrderByCreatedAtDesc(categoryId, pageable);
    }

    /**
     * Lucene + Nori 검색.
     * Lucene의 totalHits는 역색인에서 즉시 반환되므로 COUNT 문제 없음. Page 유지.
     * totalHits를 MAX_SEARCH_PAGE 분량으로 cap하여, 접근 불가한 페이지가 표시되지 않게 한다.
     * (Google도 "약 X건"을 보여주지만 실제 접근 가능한 페이지는 30~50페이지로 제한)
     */
    @Cacheable(value = "searchResults",
            key = "#keyword + ':' + #pageable.pageNumber + ':' + #pageable.pageSize")
    public Page<Post> search(String keyword, Pageable pageable) {
        validatePageLimit(pageable, MAX_SEARCH_PAGE);
        searchLogCollector.record(keyword);
        try {
            Page<Post> result = luceneSearchService.search(keyword, pageable);
            long maxAccessible = (long) MAX_SEARCH_PAGE * pageable.getPageSize();
            if (result.getTotalElements() > maxAccessible) {
                return new PageImpl<>(result.getContent(), pageable, maxAccessible);
            }
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void validatePageLimit(Pageable pageable, int maxPage) {
        if (pageable.getPageNumber() > maxPage) {
            throw new BusinessException(ErrorCode.PAGE_LIMIT_EXCEEDED);
        }
    }

    /**
     * 자동완성: Trie 우선 → Lucene PrefixQuery fallback.
     * Trie에 인기 제목 1만 건이 적재되어 있으므로 대부분 Trie에서 반환된다.
     * Trie에 결과가 없으면 (희귀 prefix) Lucene PrefixQuery로 fallback.
     */
    @Cacheable(value = "autocomplete", key = "#prefix")
    public List<String> autocomplete(String prefix) {
        List<String> results = autocompleteTrie.search(prefix.toLowerCase(), 10);
        if (!results.isEmpty()) {
            return results;
        }
        try {
            return luceneSearchService.autocomplete(prefix, 10);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void indexSafely(Post post) {
        try {
            luceneIndexService.indexPost(post);
        } catch (IOException e) {
            log.error("Lucene 색인 실패: postId={}", post.getId(), e);
        }
    }

    private void deleteFromIndexSafely(Long postId) {
        try {
            luceneIndexService.deleteFromIndex(postId);
        } catch (IOException e) {
            log.error("Lucene 삭제 실패: postId={}", postId, e);
        }
    }
}
