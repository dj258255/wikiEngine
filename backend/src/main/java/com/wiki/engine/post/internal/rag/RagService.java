package com.wiki.engine.post.internal.rag;

import com.wiki.engine.post.dto.PostSearchResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RAG(Retrieval-Augmented Generation) 서비스.
 *
 * BM25 검색 결과(Top-5)를 Gemini 컨텍스트에 주입하여 AI 요약을 생성한다.
 * SSE(Server-Sent Events)로 토큰 단위 스트리밍한다.
 *
 * 캐싱: 동일 쿼리의 AI 요약을 Redis에 TTL 30분으로 캐싱하여 LLM 호출 절감.
 */
@Slf4j
@Service
public class RagService {

    private final ChatClient chatClient;
    private final JsonMapper jsonMapper;
    private final StringRedisTemplate redisTemplate;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private static final String SYSTEM_PROMPT = """
            당신은 검색 결과를 요약하는 AI 어시스턴트입니다.
            아래 제공된 문서만을 참고하여 사용자의 질문에 답변하세요.

            규칙:
            1. 문서에 없는 내용은 답변하지 마세요. "제공된 문서에서 해당 정보를 찾을 수 없습니다"라고 답하세요.
            2. 답변에 사용한 문서의 번호를 [문서 N] 형태로 인용하세요.
            3. 한국어로 답변하세요.
            4. 300자 이내로 요약하세요.
            5. 마크다운 서식을 사용하지 마세요. 순수 텍스트로만 답변하세요.
            6. 위키 마크업([[]], {{}}, == == 등)을 절대 사용하지 마세요. 문서 원문에 마크업이 있더라도 제거하고 순수 텍스트로 답변하세요.
            """;

    private static final int MAX_CONTEXT_DOCS = 5;
    private static final int MAX_SNIPPET_LENGTH = 500;
    private static final Duration CACHE_TTL = Duration.ofMinutes(30);
    private static final String CACHE_PREFIX = "rag:";
    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[문서\\s*(\\d+)]");

    public RagService(ChatClient.Builder chatClientBuilder, JsonMapper jsonMapper,
                      StringRedisTemplate redisTemplate) {
        this.chatClient = chatClientBuilder
                .defaultSystem(SYSTEM_PROMPT)
                .build();
        this.jsonMapper = jsonMapper;
        this.redisTemplate = redisTemplate;
    }

    /**
     * SSE 스트리밍으로 AI 요약을 생성한다.
     *
     * 캐시 히트: 캐시된 응답을 즉시 SSE로 전송 (Gemini 호출 없음, 비용 0).
     * 캐시 미스: Gemini 스트리밍 → 완료 후 Redis 캐시 저장.
     */
    public void streamSummary(String query, List<PostSearchResponse> results, SseEmitter emitter) {
        List<PostSearchResponse> contextDocs = results.stream()
                .limit(MAX_CONTEXT_DOCS)
                .toList();

        executor.execute(() -> {
            try {
                // 1. Redis 캐시 확인
                RagSummaryResponse cached = getCached(query);
                if (cached != null) {
                    log.debug("RAG 캐시 히트: query='{}'", query);
                    sendCachedResponse(cached, emitter);
                    return;
                }

                // 2. 캐시 미스 — Gemini 스트리밍
                log.debug("RAG 캐시 미스: query='{}', Gemini 호출", query);
                streamFromGemini(query, contextDocs, emitter);

            } catch (Exception e) {
                log.warn("RAG 처리 실패: {}", e.getMessage());
                sendError(emitter);
            }
        });
    }

    /**
     * 캐시된 응답을 SSE로 즉시 전송한다.
     * 스트리밍처럼 보이도록 전체 텍스트를 한 번에 delta로 전송.
     */
    private void sendCachedResponse(RagSummaryResponse cached, SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().name("delta").data(cached.summary()));
            String citationsJson = jsonMapper.writeValueAsString(cached.citations());
            emitter.send(SseEmitter.event().name("citations").data(citationsJson));
            emitter.send(SseEmitter.event().name("done").data("[DONE]"));
            emitter.complete();
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
    }

    /**
     * Gemini API SSE 스트리밍 호출 + 완료 후 Redis 캐시 저장.
     */
    private void streamFromGemini(String query, List<PostSearchResponse> contextDocs, SseEmitter emitter) {
        String context = buildContext(contextDocs);
        String userPrompt = "검색 결과:\n" + context + "\n\n질문: " + query;
        StringBuilder fullAnswer = new StringBuilder();

        chatClient.prompt()
                .user(userPrompt)
                .stream()
                .content()
                .doOnNext(token -> {
                    fullAnswer.append(token);
                    try {
                        emitter.send(SseEmitter.event().name("delta").data(token));
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException("SSE 전송 실패", e);
                    }
                })
                .doOnComplete(() -> {
                    try {
                        List<RagCitation> citations = extractCitations(fullAnswer.toString(), contextDocs);
                        String citationsJson = jsonMapper.writeValueAsString(citations);
                        emitter.send(SseEmitter.event().name("citations").data(citationsJson));
                        emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                        emitter.complete();

                        // Redis 캐시 저장
                        saveCache(query, new RagSummaryResponse(fullAnswer.toString(), citations));
                    } catch (Exception e) {
                        emitter.completeWithError(e);
                    }
                })
                .doOnError(error -> {
                    log.warn("Gemini 스트리밍 실패: {}", error.getMessage());
                    sendError(emitter);
                })
                .subscribe();
    }

    // ── Redis 캐시 ──

    private RagSummaryResponse getCached(String query) {
        try {
            String json = redisTemplate.opsForValue().get(CACHE_PREFIX + query.toLowerCase().trim());
            if (json != null) {
                return jsonMapper.readValue(json, RagSummaryResponse.class);
            }
        } catch (Exception e) {
            log.debug("RAG 캐시 조회 실패 (무시): {}", e.getMessage());
        }
        return null;
    }

    private void saveCache(String query, RagSummaryResponse response) {
        try {
            String json = jsonMapper.writeValueAsString(response);
            redisTemplate.opsForValue().set(CACHE_PREFIX + query.toLowerCase().trim(), json, CACHE_TTL);
            log.debug("RAG 캐시 저장: query='{}'", query);
        } catch (Exception e) {
            log.debug("RAG 캐시 저장 실패 (무시): {}", e.getMessage());
        }
    }

    // ── 유틸리티 ──

    private void sendError(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().name("error").data("AI 요약 생성 중 오류가 발생했습니다."));
            emitter.complete();
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }

    private String buildContext(List<PostSearchResponse> docs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < docs.size(); i++) {
            PostSearchResponse doc = docs.get(i);
            String rawSnippet = doc.snippet() != null
                    ? doc.snippet().substring(0, Math.min(doc.snippet().length(), MAX_SNIPPET_LENGTH))
                    : "";
            // 위키 마크업 잔재 제거 — [[링크]], {{틀}} 등이 남아있을 수 있음
            String snippet = PostSearchResponse.stripMarkup(rawSnippet);
            sb.append("[문서 ").append(i + 1).append("] ")
                    .append("제목: ").append(doc.title())
                    .append("\nID: ").append(doc.id())
                    .append("\n내용: ").append(snippet)
                    .append("\n\n");
        }
        return sb.toString();
    }

    private List<RagCitation> extractCitations(String answer, List<PostSearchResponse> docs) {
        Set<Integer> seen = new LinkedHashSet<>();
        List<RagCitation> citations = new ArrayList<>();

        Matcher matcher = CITATION_PATTERN.matcher(answer);
        while (matcher.find()) {
            int docNum = Integer.parseInt(matcher.group(1));
            if (docNum >= 1 && docNum <= docs.size() && seen.add(docNum)) {
                PostSearchResponse doc = docs.get(docNum - 1);
                citations.add(new RagCitation(docNum, doc.id(), doc.title()));
            }
        }
        return citations;
    }
}
