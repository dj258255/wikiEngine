package com.wiki.engine.post.internal.rag;

import com.wiki.engine.post.dto.PostSearchResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
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
 * SSE(Server-Sent Events)로 토큰 단위 스트리밍 — Google AI Overviews, ChatGPT와 동일 패턴.
 *
 * 파이프라인: Lucene BM25 검색 → snippet 추출 → 프롬프트 조합 → Gemini SSE 스트리밍 → 출처 인용
 */
@Slf4j
@Service
public class RagService {

    private final ChatClient chatClient;
    private final JsonMapper jsonMapper;
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
            """;

    private static final int MAX_CONTEXT_DOCS = 5;
    private static final int MAX_SNIPPET_LENGTH = 500;
    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[문서\\s*(\\d+)]");

    public RagService(ChatClient.Builder chatClientBuilder, JsonMapper jsonMapper) {
        this.chatClient = chatClientBuilder
                .defaultSystem(SYSTEM_PROMPT)
                .build();
        this.jsonMapper = jsonMapper;
    }

    /**
     * SSE 스트리밍으로 AI 요약을 생성한다.
     * Virtual Thread에서 실행되어 톰캣 스레드를 반환한다.
     *
     * 이벤트 흐름:
     *   delta → delta → ... → delta → citations → done
     */
    public void streamSummary(String query, List<PostSearchResponse> results, SseEmitter emitter) {
        List<PostSearchResponse> contextDocs = results.stream()
                .limit(MAX_CONTEXT_DOCS)
                .toList();

        String context = buildContext(contextDocs);
        String userPrompt = "검색 결과:\n" + context + "\n\n질문: " + query;

        executor.execute(() -> {
            StringBuilder fullAnswer = new StringBuilder();
            try {
                chatClient.prompt()
                        .user(userPrompt)
                        .stream()
                        .content()
                        .doOnNext(token -> {
                            fullAnswer.append(token);
                            try {
                                emitter.send(SseEmitter.event()
                                        .name("delta")
                                        .data(token));
                            } catch (IOException e) {
                                throw new RuntimeException("SSE 전송 실패", e);
                            }
                        })
                        .doOnComplete(() -> {
                            try {
                                // 스트리밍 완료 후 출처 정보 전송
                                List<RagCitation> citations = extractCitations(
                                        fullAnswer.toString(), contextDocs);
                                String citationsJson = jsonMapper.writeValueAsString(citations);
                                emitter.send(SseEmitter.event()
                                        .name("citations")
                                        .data(citationsJson));
                                emitter.send(SseEmitter.event()
                                        .name("done")
                                        .data("[DONE]"));
                                emitter.complete();
                            } catch (Exception e) {
                                emitter.completeWithError(e);
                            }
                        })
                        .doOnError(error -> {
                            log.warn("Gemini 스트리밍 실패: {}", error.getMessage());
                            try {
                                emitter.send(SseEmitter.event()
                                        .name("error")
                                        .data("AI 요약 생성 중 오류가 발생했습니다."));
                                emitter.complete();
                            } catch (IOException e) {
                                emitter.completeWithError(e);
                            }
                        })
                        .subscribe();  // 구독 시작

            } catch (Exception e) {
                log.warn("RAG 스트리밍 초기화 실패: {}", e.getMessage());
                try {
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data("AI 요약 생성 중 오류가 발생했습니다."));
                    emitter.complete();
                } catch (IOException ex) {
                    emitter.completeWithError(ex);
                }
            }
        });
    }

    /**
     * 동기 호출 (비스트리밍). 테스트 및 캐싱용.
     */
    public RagSummaryResponse summarize(String query, List<PostSearchResponse> results) {
        if (results.isEmpty()) {
            return new RagSummaryResponse(
                    "검색 결과가 없어 AI 요약을 생성할 수 없습니다.", List.of());
        }

        List<PostSearchResponse> contextDocs = results.stream()
                .limit(MAX_CONTEXT_DOCS)
                .toList();

        String context = buildContext(contextDocs);
        String userPrompt = "검색 결과:\n" + context + "\n\n질문: " + query;

        try {
            String answer = chatClient.prompt()
                    .user(userPrompt)
                    .call()
                    .content();

            List<RagCitation> citations = extractCitations(answer, contextDocs);
            return new RagSummaryResponse(answer, citations);

        } catch (Exception e) {
            log.warn("Gemini API 호출 실패: {}", e.getMessage());
            return new RagSummaryResponse(
                    "AI 요약 생성 중 오류가 발생했습니다.", List.of());
        }
    }

    private String buildContext(List<PostSearchResponse> docs) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < docs.size(); i++) {
            PostSearchResponse doc = docs.get(i);
            String snippet = doc.snippet() != null
                    ? doc.snippet().substring(0, Math.min(doc.snippet().length(), MAX_SNIPPET_LENGTH))
                    : "";
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
