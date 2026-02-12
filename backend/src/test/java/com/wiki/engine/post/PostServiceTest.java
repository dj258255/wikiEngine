package com.wiki.engine.post;

import com.wiki.engine.common.BusinessException;
import com.wiki.engine.common.ErrorCode;
import com.wiki.engine.post.internal.PostLikeRepository;
import com.wiki.engine.post.internal.PostRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class PostServiceTest {

    @InjectMocks
    private PostService postService;

    @Mock
    private PostRepository postRepository;

    @Mock
    private PostLikeRepository postLikeRepository;

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
        @DisplayName("[해피] 정상적으로 게시글을 생성한다")
        void success() {
            given(postRepository.save(any(Post.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            Post result = postService.createPost("제목", "본문", 1L, 1L);

            assertThat(result.getTitle()).isEqualTo("제목");
            assertThat(result.getContent()).isEqualTo("본문");
            assertThat(result.getAuthorId()).isEqualTo(1L);
            assertThat(result.getCategoryId()).isEqualTo(1L);
            verify(postRepository).save(any(Post.class));
        }

        @Test
        @DisplayName("[코너] categoryId가 null이어도 생성된다")
        void nullCategoryId() {
            given(postRepository.save(any(Post.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));

            Post result = postService.createPost("제목", "본문", 1L, null);

            assertThat(result.getCategoryId()).isNull();
        }
    }

    // ========== getPostAndIncrementView ==========

    @Nested
    @DisplayName("getPostAndIncrementView")
    class GetPostAndIncrementView {

        @Test
        @DisplayName("[해피] 게시글 조회 + 조회수 증가")
        void success() {
            Post post = createTestPost();
            given(postRepository.findById(1L)).willReturn(Optional.of(post));

            Post result = postService.getPostAndIncrementView(1L);

            assertThat(result.getTitle()).isEqualTo("테스트 게시글");
            verify(postRepository).incrementViewCount(1L);
        }

        @Test
        @DisplayName("[코너] 존재하지 않는 게시글 — POST_NOT_FOUND")
        void notFound() {
            given(postRepository.findById(999L)).willReturn(Optional.empty());

            assertThatThrownBy(() -> postService.getPostAndIncrementView(999L))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("errorCode", ErrorCode.POST_NOT_FOUND);
        }
    }

    // ========== updatePost ==========

    @Nested
    @DisplayName("updatePost")
    class UpdatePost {

        @Test
        @DisplayName("[해피] 작성자가 정상적으로 수정한다")
        void success() {
            Post post = createTestPost();
            given(postRepository.findById(1L)).willReturn(Optional.of(post));

            postService.updatePost(1L, "수정 제목", "수정 본문", 1L);

            assertThat(post.getTitle()).isEqualTo("수정 제목");
            assertThat(post.getContent()).isEqualTo("수정 본문");
            assertThat(post.getUpdatedAt()).isNotNull();
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
        @DisplayName("[해피] 작성자가 정상적으로 삭제한다 (좋아요도 함께 삭제)")
        void success() {
            Post post = createTestPost();
            given(postRepository.findById(1L)).willReturn(Optional.of(post));

            postService.deletePost(1L, 1L);

            verify(postLikeRepository).deleteByPostId(1L);
            verify(postRepository).delete(post);
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
        @DisplayName("[해피] 새 좋아요 — true + likeCount 증가")
        void success() {
            given(postLikeRepository.insertIgnore(1L, 1L)).willReturn(1);

            boolean result = postService.likePost(1L, 1L);

            assertThat(result).isTrue();
            verify(postRepository).incrementLikeCount(1L);
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
        @DisplayName("[해피] 좋아요 취소 — true + likeCount 감소")
        void success() {
            given(postLikeRepository.deleteByPostIdAndUserId(1L, 1L)).willReturn(1);

            boolean result = postService.unlikePost(1L, 1L);

            assertThat(result).isTrue();
            verify(postRepository).decrementLikeCount(1L);
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
        @DisplayName("[해피] 검색 결과 반환")
        void success() {
            Post post = createTestPost();
            Pageable pageable = PageRequest.of(0, 20);
            given(postRepository.searchByKeyword("테스트", pageable))
                    .willReturn(new PageImpl<>(List.of(post)));

            Page<Post> result = postService.search("테스트", pageable);

            assertThat(result.getContent()).hasSize(1);
            assertThat(result.getContent().getFirst().getTitle()).isEqualTo("테스트 게시글");
        }

        @Test
        @DisplayName("[코너] 검색 결과 없음 — 빈 페이지")
        void empty() {
            Pageable pageable = PageRequest.of(0, 20);
            given(postRepository.searchByKeyword("없는키워드", pageable))
                    .willReturn(new PageImpl<>(Collections.emptyList()));

            Page<Post> result = postService.search("없는키워드", pageable);

            assertThat(result.getContent()).isEmpty();
            assertThat(result.getTotalElements()).isZero();
        }
    }

    // ========== autocomplete ==========

    @Nested
    @DisplayName("autocomplete")
    class Autocomplete {

        @Test
        @DisplayName("[해피] 자동완성 결과 반환 (제목만)")
        void success() {
            Post p1 = Post.builder().title("삼성전자").content("").authorId(1L).build();
            Post p2 = Post.builder().title("삼성물산").content("").authorId(1L).build();
            given(postRepository.findByTitleStartingWith(eq("삼성"), any(Pageable.class)))
                    .willReturn(List.of(p1, p2));

            List<String> result = postService.autocomplete("삼성");

            assertThat(result).containsExactly("삼성전자", "삼성물산");
        }

        @Test
        @DisplayName("[코너] 결과 없음 — 빈 리스트")
        void empty() {
            given(postRepository.findByTitleStartingWith(eq("zzz"), any(Pageable.class)))
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
