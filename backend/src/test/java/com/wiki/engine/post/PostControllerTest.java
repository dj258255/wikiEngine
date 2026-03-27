package com.wiki.engine.post;

import com.wiki.engine.auth.JwtTokenProvider;
import com.wiki.engine.auth.TokenBlacklist;
import com.wiki.engine.post.internal.ViewCountService;
import com.wiki.engine.post.internal.rag.AiFeedbackService;
import com.wiki.engine.post.internal.rag.AiSummaryDecisionService;
import com.wiki.engine.post.internal.rag.RagService;
import com.wiki.engine.auth.UserPrincipal;
import com.wiki.engine.common.BusinessException;
import com.wiki.engine.common.ErrorCode;
import com.wiki.engine.post.dto.CreatePostRequest;
import com.wiki.engine.post.dto.UpdatePostRequest;
import com.wiki.engine.post.internal.rag.AiFeedbackRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import com.wiki.engine.post.dto.PostSearchResponse;
import com.wiki.engine.post.dto.SearchResponseWithSuggestion;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.SliceImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PostController.class)
class PostControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper jsonMapper;

    @MockitoBean
    private PostService postService;

    @MockitoBean
    private ViewCountService viewCountService;

    // Security 필터 체인 의존성
    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private TokenBlacklist tokenBlacklist;

    @MockitoBean
    private RagService ragService;

    @MockitoBean
    private AiSummaryDecisionService aiSummaryDecisionService;

    @MockitoBean
    private AiFeedbackService aiFeedbackService;

    private static final String BASE = "/api/v1.0/posts";

    private void authenticate(long userId, String username) {
        UserPrincipal principal = new UserPrincipal(userId, username);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    private Post createTestPost() {
        return Post.builder()
                .title("테스트 게시글")
                .content("본문 내용입니다")
                .authorId(1L)
                .categoryId(1L)
                .build();
    }

    // ========== GET /posts (목록 조회) ==========

    @Nested
    @DisplayName("GET /posts")
    class GetPosts {

        @Test
        @DisplayName("[해피] 정상 목록 조회 — 200 + Page 반환")
        void success() throws Exception {
            Post post = createTestPost();
            given(postService.getPosts(any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of(post)));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content[0].title").value("테스트 게시글"));
        }

        @Test
        @DisplayName("[해피] categoryId 필터 조회 — 200")
        void withCategoryId() throws Exception {
            Post post = createTestPost();
            given(postService.getPostsByCategory(eq(1L), any(Pageable.class)))
                    .willReturn(new PageImpl<>(List.of(post)));

            mockMvc.perform(get(BASE).param("categoryId", "1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content[0].categoryId").value(1));
        }

        @Test
        @DisplayName("[코너] 결과 없음 — 200 + 빈 페이지")
        void empty() throws Exception {
            given(postService.getPosts(any(Pageable.class)))
                    .willReturn(new PageImpl<>(Collections.emptyList()));

            mockMvc.perform(get(BASE))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content").isEmpty());
        }
    }

    // ========== GET /posts/{id} (상세 조회) ==========

    @Nested
    @DisplayName("GET /posts/{id}")
    class GetPost {

        @Test
        @DisplayName("[해피] 정상 상세 조회 — 200 + content 포함")
        void success() throws Exception {
            Post post = createTestPost();
            given(postService.findByIdCached(1L)).willReturn(post);

            mockMvc.perform(get(BASE + "/1"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.title").value("테스트 게시글"))
                    .andExpect(jsonPath("$.data.content").value("본문 내용입니다"));

            verify(viewCountService).increment(1L);
        }

        @Test
        @DisplayName("[코너] 존재하지 않는 id — 404 POST_NOT_FOUND")
        void notFound() throws Exception {
            given(postService.findByIdCached(999L))
                    .willThrow(new BusinessException(ErrorCode.POST_NOT_FOUND));

            mockMvc.perform(get(BASE + "/999"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("POST_NOT_FOUND"));
        }
    }

    // ========== POST /posts (생성) ==========

    @Nested
    @DisplayName("POST /posts")
    class CreatePost {

        @Test
        @DisplayName("[해피] 정상 생성 — 201 Created")
        void success() throws Exception {
            authenticate(1L, "john1");
            Post post = createTestPost();
            given(postService.createPost("제목", "본문", 1L, 1L)).willReturn(post);

            mockMvc.perform(post(BASE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(jsonMapper.writeValueAsString(
                                    new CreatePostRequest("제목", "본문", 1L))))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.title").value("테스트 게시글"));
        }

        @Test
        @DisplayName("[임계] title 빈값 — 400 Validation 실패")
        void blankTitle() throws Exception {
            authenticate(1L, "john1");

            mockMvc.perform(post(BASE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"title":"","content":"본문","categoryId":1}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }

        @Test
        @DisplayName("[임계] content 빈값 — 400 Validation 실패")
        void blankContent() throws Exception {
            authenticate(1L, "john1");

            mockMvc.perform(post(BASE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"title":"제목","content":"","categoryId":1}
                                    """))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }

        @Test
        @DisplayName("[임계] title 513자 초과 — 400 Validation 실패")
        void titleTooLong() throws Exception {
            authenticate(1L, "john1");
            String longTitle = "가".repeat(513);

            mockMvc.perform(post(BASE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"title":"%s","content":"본문","categoryId":1}
                                    """.formatted(longTitle)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }

        @Test
        @DisplayName("[코너] 미인증 — 401")
        void unauthenticated() throws Exception {
            mockMvc.perform(post(BASE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"title":"제목","content":"본문","categoryId":1}
                                    """))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("[코너] categoryId null이어도 생성 — 201")
        void nullCategoryId() throws Exception {
            authenticate(1L, "john1");
            Post post = Post.builder().title("제목").content("본문").authorId(1L).build();
            given(postService.createPost("제목", "본문", 1L, null)).willReturn(post);

            mockMvc.perform(post(BASE)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"title":"제목","content":"본문"}
                                    """))
                    .andExpect(status().isCreated());
        }
    }

    // ========== PUT /posts/{id} (수정) ==========

    @Nested
    @DisplayName("PUT /posts/{id}")
    class UpdatePost {

        @Test
        @DisplayName("[해피] 정상 수정 — 200")
        void success() throws Exception {
            authenticate(1L, "john1");

            mockMvc.perform(put(BASE + "/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(jsonMapper.writeValueAsString(
                                    new UpdatePostRequest("수정 제목", "수정 본문"))))
                    .andExpect(status().isOk());

            verify(postService).updatePost(1L, "수정 제목", "수정 본문", 1L);
        }

        @Test
        @DisplayName("[코너] 작성자가 아닌 사용자 — 403 ACCESS_DENIED")
        void accessDenied() throws Exception {
            authenticate(999L, "other");
            doThrow(new BusinessException(ErrorCode.ACCESS_DENIED))
                    .when(postService).updatePost(1L, "수정 제목", "수정 본문", 999L);

            mockMvc.perform(put(BASE + "/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(jsonMapper.writeValueAsString(
                                    new UpdatePostRequest("수정 제목", "수정 본문"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }

        @Test
        @DisplayName("[코너] 존재하지 않는 게시글 — 404 POST_NOT_FOUND")
        void notFound() throws Exception {
            authenticate(1L, "john1");
            doThrow(new BusinessException(ErrorCode.POST_NOT_FOUND))
                    .when(postService).updatePost(eq(999L), any(), any(), eq(1L));

            mockMvc.perform(put(BASE + "/999")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(jsonMapper.writeValueAsString(
                                    new UpdatePostRequest("수정 제목", "수정 본문"))))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("POST_NOT_FOUND"));
        }

        @Test
        @DisplayName("[코너] 미인증 — 401")
        void unauthenticated() throws Exception {
            mockMvc.perform(put(BASE + "/1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"title":"수정","content":"수정"}
                                    """))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ========== DELETE /posts/{id} (삭제) ==========

    @Nested
    @DisplayName("DELETE /posts/{id}")
    class DeletePost {

        @Test
        @DisplayName("[해피] 정상 삭제 — 204 No Content")
        void success() throws Exception {
            authenticate(1L, "john1");

            mockMvc.perform(delete(BASE + "/1"))
                    .andExpect(status().isNoContent());

            verify(postService).deletePost(1L, 1L);
        }

        @Test
        @DisplayName("[코너] 작성자가 아닌 사용자 — 403 ACCESS_DENIED")
        void accessDenied() throws Exception {
            authenticate(999L, "other");
            doThrow(new BusinessException(ErrorCode.ACCESS_DENIED))
                    .when(postService).deletePost(1L, 999L);

            mockMvc.perform(delete(BASE + "/1"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        }

        @Test
        @DisplayName("[코너] 존재하지 않는 게시글 — 404 POST_NOT_FOUND")
        void notFound() throws Exception {
            authenticate(1L, "john1");
            doThrow(new BusinessException(ErrorCode.POST_NOT_FOUND))
                    .when(postService).deletePost(999L, 1L);

            mockMvc.perform(delete(BASE + "/999"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("POST_NOT_FOUND"));
        }

        @Test
        @DisplayName("[코너] 미인증 — 401")
        void unauthenticated() throws Exception {
            mockMvc.perform(delete(BASE + "/1"))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ========== POST /posts/{id}/like (좋아요) ==========

    @Nested
    @DisplayName("POST /posts/{id}/like")
    class LikePost {

        @Test
        @DisplayName("[해피] 새 좋아요 — 200 OK")
        void success() throws Exception {
            authenticate(1L, "john1");
            given(postService.likePost(1L, 1L)).willReturn(true);

            mockMvc.perform(post(BASE + "/1/like"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("[코너] 이미 좋아요 — 409 Conflict")
        void alreadyLiked() throws Exception {
            authenticate(1L, "john1");
            given(postService.likePost(1L, 1L)).willReturn(false);

            mockMvc.perform(post(BASE + "/1/like"))
                    .andExpect(status().isConflict());
        }

        @Test
        @DisplayName("[코너] 미인증 — 401")
        void unauthenticated() throws Exception {
            mockMvc.perform(post(BASE + "/1/like"))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ========== DELETE /posts/{id}/like (좋아요 취소) ==========

    @Nested
    @DisplayName("DELETE /posts/{id}/like")
    class UnlikePost {

        @Test
        @DisplayName("[해피] 좋아요 취소 — 200 OK")
        void success() throws Exception {
            authenticate(1L, "john1");
            given(postService.unlikePost(1L, 1L)).willReturn(true);

            mockMvc.perform(delete(BASE + "/1/like"))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("[코너] 좋아요 기록 없음 — 404 Not Found")
        void notLiked() throws Exception {
            authenticate(1L, "john1");
            given(postService.unlikePost(1L, 1L)).willReturn(false);

            mockMvc.perform(delete(BASE + "/1/like"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("[코너] 미인증 — 401")
        void unauthenticated() throws Exception {
            mockMvc.perform(delete(BASE + "/1/like"))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ========== GET /posts/search (검색) ==========

    @Nested
    @DisplayName("GET /posts/search")
    class Search {

        @Test
        @DisplayName("[해피] 정상 검색 — 200 + 결과 반환")
        void success() throws Exception {
            PostSearchResponse response = new PostSearchResponse(1L, "테스트 게시글", "테스트 본문...", 0L, 0L, java.time.Instant.now());
            given(postService.search(eq("테스트"), isNull(), any(Pageable.class)))
                    .willReturn(new SearchResponseWithSuggestion(new SliceImpl<>(List.of(response)), null, Map.of()));

            mockMvc.perform(get(BASE + "/search").param("q", "테스트"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.results.content[0].title").value("테스트 게시글"));
        }

        @Test
        @DisplayName("[코너] 결과 없음 — 200 + 빈 Slice")
        void empty() throws Exception {
            given(postService.search(eq("없는키워드"), isNull(), any(Pageable.class)))
                    .willReturn(new SearchResponseWithSuggestion(new SliceImpl<>(Collections.emptyList()), null, Map.of()));

            mockMvc.perform(get(BASE + "/search").param("q", "없는키워드"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.results.content").isEmpty());
        }

        @Test
        @DisplayName("[임계] q 파라미터 없음 — 에러 반환")
        void missingParam() throws Exception {
            mockMvc.perform(get(BASE + "/search"))
                    .andExpect(result -> {
                        int status = result.getResponse().getStatus();
                        assert status >= 400 : "Expected error status but got " + status;
                    });
        }

        @Test
        @DisplayName("[임계] 페이지 사이즈 지정 — 200")
        void customPageSize() throws Exception {
            given(postService.search(eq("test"), isNull(), any(Pageable.class)))
                    .willReturn(new SearchResponseWithSuggestion(new SliceImpl<>(Collections.emptyList()), null, Map.of()));

            mockMvc.perform(get(BASE + "/search")
                            .param("q", "test")
                            .param("size", "5")
                            .param("page", "0"))
                    .andExpect(status().isOk());
        }
    }

    // ========== GET /posts/search/ai-summary (AI 요약 SSE) ==========

    @Nested
    @DisplayName("GET /posts/search/ai-summary")
    class AiSummaryStream {

        @Test
        @DisplayName("[해피] 트리거 충족 — 200 SSE 스트림 반환")
        void success() throws Exception {
            PostSearchResponse response = new PostSearchResponse(
                    1L, "테스트", "본문...", 0L, 0L, java.time.Instant.now());
            given(postService.search(eq("테스트"), isNull(), any(Pageable.class)))
                    .willReturn(new SearchResponseWithSuggestion(
                            new SliceImpl<>(List.of(response)), null, Map.of()));
            given(aiSummaryDecisionService.decide(eq("테스트"), anyList()))
                    .willReturn(new AiSummaryDecisionService.Decision(true, null));

            mockMvc.perform(get(BASE + "/search/ai-summary")
                            .param("q", "테스트")
                            .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
                    .andExpect(status().isOk());

            verify(ragService).streamSummary(eq("테스트"), anyList(), any());
        }

        @Test
        @DisplayName("[코너] 트리거 미충족 — skip 이벤트 반환")
        void skipWhenNotTriggered() throws Exception {
            given(postService.search(eq("ㅋ"), isNull(), any(Pageable.class)))
                    .willReturn(new SearchResponseWithSuggestion(
                            new SliceImpl<>(Collections.emptyList()), null, Map.of()));
            given(aiSummaryDecisionService.decide(eq("ㅋ"), anyList()))
                    .willReturn(new AiSummaryDecisionService.Decision(false, "query_too_short"));

            MvcResult result = mockMvc.perform(get(BASE + "/search/ai-summary")
                            .param("q", "ㅋ")
                            .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
                    .andExpect(status().isOk())
                    .andReturn();

            verify(ragService, never()).streamSummary(any(), anyList(), any());
        }

        @Test
        @DisplayName("[임계] q 파라미터 없음 — 에러 반환")
        void missingParam() throws Exception {
            mockMvc.perform(get(BASE + "/search/ai-summary")
                            .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
                    .andExpect(result -> {
                        int status = result.getResponse().getStatus();
                        assert status >= 400 : "Expected error status but got " + status;
                    });
        }

        @Test
        @DisplayName("[코너] 검색 결과 없음 — skip 이벤트 (결과 부족)")
        void skipWhenNoResults() throws Exception {
            given(postService.search(eq("없는키워드xyz"), isNull(), any(Pageable.class)))
                    .willReturn(new SearchResponseWithSuggestion(
                            new SliceImpl<>(Collections.emptyList()), null, Map.of()));
            given(aiSummaryDecisionService.decide(eq("없는키워드xyz"), eq(Collections.emptyList())))
                    .willReturn(new AiSummaryDecisionService.Decision(false, "no_results"));

            mockMvc.perform(get(BASE + "/search/ai-summary")
                            .param("q", "없는키워드xyz")
                            .accept(MediaType.TEXT_EVENT_STREAM_VALUE))
                    .andExpect(status().isOk());

            verify(ragService, never()).streamSummary(any(), anyList(), any());
        }
    }

    // ========== POST /posts/search/ai-summary/feedback (AI 피드백) ==========

    @Nested
    @DisplayName("POST /posts/search/ai-summary/feedback")
    class AiSummaryFeedback {

        @Test
        @DisplayName("[해피] thumbs up 피드백 — 200 OK")
        void thumbsUp() throws Exception {
            mockMvc.perform(post(BASE + "/search/ai-summary/feedback")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(jsonMapper.writeValueAsString(
                                    new AiFeedbackRequest("테스트", 1, null, null))))
                    .andExpect(status().isOk());

            verify(aiFeedbackService).saveFeedback(any(AiFeedbackRequest.class), isNull());
        }

        @Test
        @DisplayName("[해피] thumbs down + 카테고리 + 코멘트 — 200 OK")
        void thumbsDownWithDetails() throws Exception {
            mockMvc.perform(post(BASE + "/search/ai-summary/feedback")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(jsonMapper.writeValueAsString(
                                    new AiFeedbackRequest("테스트", -1, "inaccurate", "잘못된 정보"))))
                    .andExpect(status().isOk());

            verify(aiFeedbackService).saveFeedback(any(AiFeedbackRequest.class), isNull());
        }

        @Test
        @DisplayName("[임계] query 빈값 — 400 Validation 실패")
        void blankQuery() throws Exception {
            mockMvc.perform(post(BASE + "/search/ai-summary/feedback")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"query":"","rating":1}
                                    """))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("[임계] rating null — 400 Validation 실패")
        void nullRating() throws Exception {
            mockMvc.perform(post(BASE + "/search/ai-summary/feedback")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"query":"테스트"}
                                    """))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("[임계] body 없음 — 400")
        void emptyBody() throws Exception {
            mockMvc.perform(post(BASE + "/search/ai-summary/feedback")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(""))
                    .andExpect(result -> {
                        int status = result.getResponse().getStatus();
                        assert status >= 400 : "Expected error status but got " + status;
                    });
        }
    }

    // ========== GET /posts/autocomplete (자동완성) ==========

    @Nested
    @DisplayName("GET /posts/autocomplete")
    class Autocomplete {

        @Test
        @DisplayName("[해피] 자동완성 — 200 + 제목 리스트")
        void success() throws Exception {
            given(postService.autocomplete("삼성")).willReturn(List.of("삼성전자", "삼성물산"));

            mockMvc.perform(get(BASE + "/autocomplete").param("prefix", "삼성"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0]").value("삼성전자"))
                    .andExpect(jsonPath("$.data[1]").value("삼성물산"));
        }

        @Test
        @DisplayName("[코너] 결과 없음 — 200 + 빈 리스트")
        void empty() throws Exception {
            given(postService.autocomplete("zzz")).willReturn(Collections.emptyList());

            mockMvc.perform(get(BASE + "/autocomplete").param("prefix", "zzz"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data").isEmpty());
        }

        @Test
        @DisplayName("[임계] prefix 파라미터 없음 — 에러 반환")
        void missingParam() throws Exception {
            mockMvc.perform(get(BASE + "/autocomplete"))
                    .andExpect(result -> {
                        int status = result.getResponse().getStatus();
                        assert status >= 400 : "Expected error status but got " + status;
                    });
        }
    }
}
