package com.wiki.engine.post;

import com.github.benmanes.caffeine.cache.Cache;
import com.wiki.engine.common.BusinessException;
import com.wiki.engine.common.ErrorCode;
import com.wiki.engine.config.TieredCacheService;
import com.wiki.engine.post.dto.CachedSearchResult;
import com.wiki.engine.post.dto.PostSearchResponse;
import com.wiki.engine.post.internal.LuceneSearchService;
import com.wiki.engine.post.internal.SpellCheckService;
import com.wiki.engine.post.internal.CategoryRecommendService;
// LuceneIndexService 제거됨 — Phase 14-1: Lucene 색인은 EventHandler가 담당
import com.wiki.engine.post.internal.PostLikeRepository;
import com.wiki.engine.post.internal.PostRepository;
import com.wiki.engine.post.internal.RedisAutocompleteService;
import com.wiki.engine.post.internal.SearchLogCollector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.SliceImpl;
import org.springframework.data.domain.Pageable;

import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PostServiceTest {

    private PostService postService;

    @Mock private PostRepository postRepository;
    @Mock private PostLikeRepository postLikeRepository;
    @Mock private LuceneSearchService luceneSearchService;
    @Mock private SearchLogCollector searchLogCollector;
    @Mock private RedisAutocompleteService redisAutocompleteService;
    @Mock private SpellCheckService spellCheckService;
    @Mock private CategoryRecommendService categoryRecommendService;
    @Mock private TieredCacheService tieredCacheService;
    @Mock private Cache<String, Object> searchResultsL1Cache;
    @Mock private Cache<String, Object> postDetailL1Cache;
    @Mock private ApplicationEventPublisher eventPublisher;

    @BeforeEach
    void setUp() {
        postService = new PostService(
                postRepository, postLikeRepository,
                luceneSearchService, searchLogCollector, redisAutocompleteService, spellCheckService,
                categoryRecommendService, tieredCacheService, searchResultsL1Cache, postDetailL1Cache,
                eventPublisher);

        // TieredCacheService: pass-through (항상 origin loader 호출)
        lenient().when(tieredCacheService.get(
                        any(String.class), any(Cache.class), any(String.class),
                        any(Class.class), any(Duration.class), any(Supplier.class)))
                .thenAnswer(invocation -> {
                    Supplier<?> loader = invocation.getArgument(5);
                    return loader.get();
                });
    }

    private Post createTestPost() {
        return Post.builder()
                .title("테스트 게시글")
                .content("본문 내용입니다")
                .authorId(1L)
                .categoryId(1L)
                .build();
    }

    // ========== createPost ==========

    @Nested
    @DisplayName("createPost")
    class CreatePost {

        @Test
        @DisplayName("[해피] 정상적으로 게시글을 생성한다 + PostEvent.Created 발행")
        void success() {
            given(postRepository.save(any(Post.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            Post result = postService.createPost("제목", "본문", 1L, 1L);

            assertThat(result.getTitle()).isEqualTo("제목");
            assertThat(result.getContent()).isEqualTo("본문");
            assertThat(result.getAuthorId()).isEqualTo(1L);
            assertThat(result.getCategoryId()).isEqualTo(1L);
            verify(postRepository).save(any(Post.class));

            var captor = ArgumentCaptor.forClass(PostEvent.Created.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().post()).isEqualTo(result);
        }

        @Test
        @DisplayName("[코너] categoryId가 null이면 MoreLikeThis 추천 시도 (추천 결과 없으면 null)")
        void nullCategoryId() {
            given(postRepository.save(any(Post.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));
            given(categoryRecommendService.recommendCategory("제목", "본문"))
                    .willReturn(null);

            Post result = postService.createPost("제목", "본문", 1L, null);

            assertThat(result.getCategoryId()).isNull();
        }
    }

    // ========== getPostAndIncrementView ==========

    @Nested
    @DisplayName("findByIdCached + incrementViewCount")
    class FindByIdCachedAndIncrementView {

        @Test
        @DisplayName("[해피] 게시글 조회 (캐시 대상)")
        void findByIdCached_success() {
            Post post = createTestPost();
            given(postRepository.findById(1L)).willReturn(Optional.of(post));

            Post result = postService.findByIdCached(1L);

            assertThat(result.getTitle()).isEqualTo("테스트 게시글");
        }

        @Test
        @DisplayName("[해피] 조회수 증가")
        void incrementViewCount_success() {
            postService.incrementViewCount(1L);

            verify(postRepository).incrementViewCount(1L);
        }

        @Test
        @DisplayName("[코너] 존재하지 않는 게시글 — POST_NOT_FOUND")
        void findByIdCached_notFound() {
            given(postRepository.findById(999L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> postService.findByIdCached(999L))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.POST_NOT_FOUND);
        }
    }

    // ========== updatePost ==========

    @Nested
    @DisplayName("updatePost")
    class UpdatePost {

        @Test
        @DisplayName("[해피] 작성자가 정상적으로 수정한다 + PostEvent.Updated 발행")
        void success() {
            Post post = createTestPost();
            given(postRepository.findById(1L)).willReturn(Optional.of(post));

            postService.updatePost(1L, "수정 제목", "수정 본문", 1L);

            assertThat(post.getTitle()).isEqualTo("수정 제목");
            assertThat(post.getContent()).isEqualTo("수정 본문");
            assertThat(post.getUpdatedAt()).isNotNull();

            var captor = ArgumentCaptor.forClass(PostEvent.Updated.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().postId()).isEqualTo(1L);
            assertThat(captor.getValue().post()).isEqualTo(post);
        }

        @Test
        @DisplayName("[코너] 존재하지 않는 게시글 — POST_NOT_FOUND")
        void notFound() {
            given(postRepository.findById(999L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> postService.updatePost(999L, "제목", "본문", 1L))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.POST_NOT_FOUND);
        }

        @Test
        @DisplayName("[코너] 작성자가 아닌 사용자 — ACCESS_DENIED")
        void accessDenied() {
            Post post = createTestPost(); // authorId = 1L
            given(postRepository.findById(1L)).willReturn(Optional.of(post));

            assertThatThrownBy(() -> postService.updatePost(1L, "제목", "본문", 999L))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ACCESS_DENIED);
        }
    }

    // ========== deletePost ==========

    @Nested
    @DisplayName("deletePost")
    class DeletePost {

        @Test
        @DisplayName("[해피] 작성자가 정상적으로 삭제한다 (좋아요 삭제 + PostEvent.Deleted 발행)")
        void success() {
            Post post = createTestPost();
            given(postRepository.findById(1L)).willReturn(Optional.of(post));

            postService.deletePost(1L, 1L);

            verify(postLikeRepository).deleteByPostId(1L);
            verify(postRepository).delete(post);

            var captor = ArgumentCaptor.forClass(PostEvent.Deleted.class);
            verify(eventPublisher).publishEvent(captor.capture());
            assertThat(captor.getValue().postId()).isEqualTo(1L);
        }

        @Test
        @DisplayName("[코너] 존재하지 않는 게시글 — POST_NOT_FOUND")
        void notFound() {
            given(postRepository.findById(999L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> postService.deletePost(999L, 1L))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.POST_NOT_FOUND);
        }

        @Test
        @DisplayName("[코너] 작성자가 아닌 사용자 — ACCESS_DENIED")
        void accessDenied() {
            Post post = createTestPost();
            given(postRepository.findById(1L)).willReturn(Optional.of(post));

            assertThatThrownBy(() -> postService.deletePost(1L, 999L))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.ACCESS_DENIED);

            verify(postRepository, never()).delete(any());
        }
    }

    // ========== likePost ==========

    @Nested
    @DisplayName("likePost")
    class LikePost {

        @Test
        @DisplayName("[해피] 새 좋아요 — true + likeCount 증가 + LikeChanged 이벤트")
        void success() {
            given(postLikeRepository.insertIgnore(1L, 1L)).willReturn(1);

            boolean result = postService.likePost(1L, 1L);

            assertThat(result).isTrue();
            verify(postRepository).incrementLikeCount(1L);
            verify(eventPublisher).publishEvent(any(PostEvent.LikeChanged.class));
        }

        @Test
        @DisplayName("[코너] 이미 좋아요 — false + likeCount 미증가")
        void alreadyLiked() {
            given(postLikeRepository.insertIgnore(1L, 1L)).willReturn(0);

            boolean result = postService.likePost(1L, 1L);

            assertThat(result).isFalse();
            verify(postRepository, never()).incrementLikeCount(any());
        }
    }

    // ========== unlikePost ==========

    @Nested
    @DisplayName("unlikePost")
    class UnlikePost {

        @Test
        @DisplayName("[해피] 좋아요 취소 — true + likeCount 감소 + LikeChanged 이벤트")
        void success() {
            given(postLikeRepository.deleteByPostIdAndUserId(1L, 1L)).willReturn(1);

            boolean result = postService.unlikePost(1L, 1L);

            assertThat(result).isTrue();
            verify(postRepository).decrementLikeCount(1L);
            verify(eventPublisher).publishEvent(any(PostEvent.LikeChanged.class));
        }

        @Test
        @DisplayName("[코너] 좋아요 기록 없음 — false + likeCount 미감소")
        void notLiked() {
            given(postLikeRepository.deleteByPostIdAndUserId(1L, 1L)).willReturn(0);

            boolean result = postService.unlikePost(1L, 1L);

            assertThat(result).isFalse();
            verify(postRepository, never()).decrementLikeCount(any());
        }
    }

    // ========== search ==========

    @Nested
    @DisplayName("search")
    class Search {

        @Test
        @DisplayName("[해피] 검색 결과 반환 + 검색 로그 기록")
        void success() throws IOException {
            Post post = createTestPost();
            Pageable pageable = PageRequest.of(0, 20);
            given(luceneSearchService.search("테스트", null, pageable))
                    .willReturn(new LuceneSearchService.SearchResult(
                            new SliceImpl<>(List.of(post), pageable, false), Map.of()));

            var result = postService.search("테스트", null, pageable);

            assertThat(result.results().getContent()).hasSize(1);
            assertThat(result.results().getContent().getFirst().title()).isEqualTo("테스트 게시글");
            verify(searchLogCollector).record("테스트");
        }

        @Test
        @DisplayName("[코너] 검색 결과 없음 — 빈 Slice")
        void empty() throws IOException {
            Pageable pageable = PageRequest.of(0, 20);
            given(luceneSearchService.search("없는키워드", null, pageable))
                    .willReturn(new LuceneSearchService.SearchResult(
                            new SliceImpl<>(Collections.emptyList(), pageable, false), Map.of()));

            var result = postService.search("없는키워드", null, pageable);

            assertThat(result.results().getContent()).isEmpty();
            assertThat(result.results().hasNext()).isFalse();
        }
    }

    // ========== autocomplete ==========

    @Nested
    @DisplayName("autocomplete")
    class Autocomplete {

        @Test
        @DisplayName("[해피] Redis flat KV에서 자동완성 결과 반환")
        void success() {
            given(redisAutocompleteService.search("삼성", 10))
                    .willReturn(List.of("삼성전자", "삼성물산"));

            List<String> result = postService.autocomplete("삼성");

            assertThat(result).containsExactly("삼성전자", "삼성물산");
        }

        @Test
        @DisplayName("[코너] 결과 없음 — 빈 리스트")
        void empty() {
            given(redisAutocompleteService.search("zzz", 10))
                    .willReturn(Collections.emptyList());

            List<String> result = postService.autocomplete("zzz");

            assertThat(result).isEmpty();
        }
    }

    // ========== Post 엔티티 ==========

    @Nested
    @DisplayName("Post 엔티티")
    class PostEntity {

        @Test
        @DisplayName("[해피] viewCount 초기값 0")
        void initialViewCount() {
            Post post = createTestPost();
            assertThat(post.getViewCount()).isZero();
        }

        @Test
        @DisplayName("[해피] likeCount 초기값 0")
        void initialLikeCount() {
            Post post = createTestPost();
            assertThat(post.getLikeCount()).isZero();
        }

        @Test
        @DisplayName("[해피] decrementLikeCount는 0 이하로 내려가지 않음")
        void decrementLikeCountFloor() {
            Post post = createTestPost();
            assertThat(post.getLikeCount()).isZero();

            post.decrementLikeCount();

            assertThat(post.getLikeCount()).isZero();
        }

        @Test
        @DisplayName("[해피] update 호출 시 updatedAt이 설정됨")
        void updateSetsUpdatedAt() {
            Post post = createTestPost();
            assertThat(post.getUpdatedAt()).isNull();

            post.update("새 제목", "새 본문");

            assertThat(post.getUpdatedAt()).isNotNull();
            assertThat(post.getTitle()).isEqualTo("새 제목");
        }
    }
}
