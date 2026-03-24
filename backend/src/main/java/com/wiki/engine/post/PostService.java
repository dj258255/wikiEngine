package com.wiki.engine.post;

import com.github.benmanes.caffeine.cache.Cache;
import com.wiki.engine.config.TieredCacheService;
import com.wiki.engine.post.dto.CachedSearchResult;
import com.wiki.engine.post.dto.PostSearchResponse;
import com.wiki.engine.post.dto.SearchResponseWithSuggestion;
import com.wiki.engine.common.BusinessException;
import com.wiki.engine.common.ErrorCode;
import com.wiki.engine.post.internal.RedisAutocompleteService;
import com.wiki.engine.post.internal.SearchLogCollector;
import com.wiki.engine.post.internal.SpellCheckService;
import com.wiki.engine.post.internal.LuceneSearchService;
import com.wiki.engine.post.internal.PostLikeRepository;
import com.wiki.engine.post.internal.PostRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 게시글 비즈니스 로직 서비스.
 * 게시글 CRUD, 조회수 증가, 좋아요/좋아요취소 기능을 제공한다.
 * 기본적으로 읽기 전용 트랜잭션이며, 쓰기 작업은 별도 @Transactional로 관리한다.
 *
 * <p>Phase 11: @Cacheable/@CacheEvict 제거 → TieredCacheService(L1+L2) 직접 호출.
 * 자동완성: Trie → RedisAutocompleteService(Redis flat KV) 전환.
 *
 * <p>Phase 14-1: 쓰기 작업의 Read Model 직접 호출 제거.
 * Lucene 인덱싱, 캐시 무효화를 ApplicationEvent로 디커플링.
 * LuceneIndexEventHandler, CacheInvalidationEventHandler, SearchCacheEventHandler가 소비.
 */
@Slf4j
@Service
@Transactional(readOnly = true)
public class PostService {

    private static final int MAX_LIST_PAGE = 15;
    private static final int MAX_SEARCH_PAGE = 15;
    private static final Duration SEARCH_L2_TTL = Duration.ofMinutes(10);
    private static final Duration POST_DETAIL_L2_TTL = Duration.ofMinutes(30);

    private final PostRepository postRepository;
    private final PostLikeRepository postLikeRepository;
    private final LuceneSearchService luceneSearchService;
    private final SearchLogCollector searchLogCollector;
    private final RedisAutocompleteService redisAutocompleteService;
    private final SpellCheckService spellCheckService;
    private final TieredCacheService tieredCacheService;
    private final Cache<String, Object> searchResultsL1Cache;
    private final Cache<String, Object> postDetailL1Cache;
    private final ApplicationEventPublisher eventPublisher;

    public PostService(PostRepository postRepository,
                       PostLikeRepository postLikeRepository,
                       LuceneSearchService luceneSearchService,
                       SearchLogCollector searchLogCollector,
                       RedisAutocompleteService redisAutocompleteService,
                       SpellCheckService spellCheckService,
                       TieredCacheService tieredCacheService,
                       @Qualifier("searchResultsL1Cache") Cache<String, Object> searchResultsL1Cache,
                       @Qualifier("postDetailL1Cache") Cache<String, Object> postDetailL1Cache,
                       ApplicationEventPublisher eventPublisher) {
        this.postRepository = postRepository;
        this.postLikeRepository = postLikeRepository;
        this.luceneSearchService = luceneSearchService;
        this.searchLogCollector = searchLogCollector;
        this.redisAutocompleteService = redisAutocompleteService;
        this.spellCheckService = spellCheckService;
        this.tieredCacheService = tieredCacheService;
        this.searchResultsL1Cache = searchResultsL1Cache;
        this.postDetailL1Cache = postDetailL1Cache;
        this.eventPublisher = eventPublisher;
    }

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
        eventPublisher.publishEvent(new PostEvent.Created(saved.getId(), saved));
        return saved;
    }

    /** ID로 게시글을 조회한다 (L1+L2 2계층 캐시). */
    public Post findByIdCached(Long id) {
        String redisKey = "post:" + id;
        return tieredCacheService.get("postDetail", postDetailL1Cache, redisKey,
                Post.class, POST_DETAIL_L2_TTL,
                () -> postRepository.findById(id)
                        .orElseThrow(() -> new BusinessException(ErrorCode.POST_NOT_FOUND)));
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
    @Transactional
    public void updatePost(Long id, String title, String content, Long userId) {
        Post post = postRepository.findById(id)
                .orElseThrow(() -> new BusinessException(ErrorCode.POST_NOT_FOUND));

        if (!post.getAuthorId().equals(userId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }

        post.update(title, content);
        eventPublisher.publishEvent(new PostEvent.Updated(id, post));
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
                .orElseThrow(() -> new BusinessException(ErrorCode.POST_NOT_FOUND));

        if (!post.getAuthorId().equals(userId)) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }

        // 게시글에 달린 좋아요를 먼저 삭제한 뒤 게시글 삭제
        postLikeRepository.deleteByPostId(id);
        postRepository.delete(post);
        eventPublisher.publishEvent(new PostEvent.Deleted(id));
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
            eventPublisher.publishEvent(new PostEvent.LikeChanged(postId));
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
            eventPublisher.publishEvent(new PostEvent.LikeChanged(postId));
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
     * Lucene + Nori 검색 — 검색 결과 + 오타 교정 제안 반환.
     * L1(Caffeine) + L2(Redis) 2계층 캐시.
     * 검색 로그는 캐시 히트/미스와 무관하게 항상 기록한다.
     *
     * Phase 18: 오타 교정 — 결과가 적으면 DirectSpellChecker로 교정 제안.
     * @param categoryId null이면 전체 검색, 값이 있으면 해당 카테고리만 필터링 (Phase 17).
     */
    public SearchResponseWithSuggestion search(String keyword, Long categoryId, Pageable pageable) {
        validatePageLimit(pageable, MAX_SEARCH_PAGE);
        searchLogCollector.record(keyword);

        // 캐시 키에 categoryId 포함 — 같은 키워드라도 카테고리별로 다른 결과
        String categoryPart = categoryId != null ? categoryId.toString() : "all";
        String redisKey = "search:" + keyword + ":" + categoryPart + ":" + pageable.getPageNumber() + ":" + pageable.getPageSize();
        CachedSearchResult cached = tieredCacheService.get("searchResults", searchResultsL1Cache,
                redisKey, CachedSearchResult.class, SEARCH_L2_TTL,
                () -> {
                    try {
                        var searchResult = luceneSearchService.search(keyword, categoryId, pageable);
                        List<PostSearchResponse> responses = searchResult.posts().getContent().stream()
                                .map(post -> {
                                    // Highlighter snippet이 있으면 사용, 없으면 fallback (앞 150자)
                                    String highlightedSnippet = post.getId() != null
                                            ? searchResult.snippets().getOrDefault(post.getId(), null)
                                            : null;
                                    return highlightedSnippet != null
                                            ? PostSearchResponse.fromWithSnippet(post, highlightedSnippet)
                                            : PostSearchResponse.from(post);
                                })
                                .toList();
                        return new CachedSearchResult(responses, searchResult.posts().hasNext());
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
        Slice<PostSearchResponse> results = new SliceImpl<>(cached.content(), pageable, cached.hasNext());

        // Phase 18: 결과가 적으면 오타 교정 제안 (첫 페이지에서만)
        String suggestion = null;
        if (pageable.getPageNumber() == 0 && results.getContent().size() < 3) {
            suggestion = spellCheckService.suggestCorrection(keyword).orElse(null);
        }

        return new SearchResponseWithSuggestion(results, suggestion);
    }

    private void validatePageLimit(Pageable pageable, int maxPage) {
        if (pageable.getPageNumber() > maxPage) {
            throw new BusinessException(ErrorCode.PAGE_LIMIT_EXCEEDED);
        }
    }

    /**
     * 자동완성: Redis flat KV → Lucene PrefixQuery fallback.
     * Phase 11: Trie 퇴역, Redis prefix_topk O(1) GET으로 전환.
     */
    public List<String> autocomplete(String prefix) {
        return redisAutocompleteService.search(prefix, 10);
    }

}
