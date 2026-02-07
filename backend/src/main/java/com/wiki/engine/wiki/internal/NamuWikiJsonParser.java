package com.wiki.engine.wiki.internal;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.wiki.engine.wiki.WikiPage;
import lombok.extern.slf4j.Slf4j;

import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 나무위키 덤프 JSON 파서.
 * Jackson Streaming API를 사용하여 대용량 JSON 파일을 메모리 효율적으로 파싱한다.
 *
 * 파싱 대상 JSON 구조:
 * <pre>
 * [
 *   {
 *     "namespace": 0,
 *     "title": "문서 제목",
 *     "text": "나무마크 본문...",
 *     "contributors": ["user1", "user2"]
 *   },
 *   ...
 * ]
 * </pre>
 */
@Slf4j
public class NamuWikiJsonParser {

    private static final Pattern REDIRECT_PATTERN =
            Pattern.compile("#(?:redirect|넘겨주기)\\s+(.+)", Pattern.CASE_INSENSITIVE);

    /**
     * 나무위키 덤프 JSON 파일을 파싱하여 페이지별로 콜백을 호출한다.
     *
     * @param filePath JSON 파일 경로
     * @param pageConsumer 파싱된 WikiPage를 전달받는 콜백 함수
     */
    public void parse(String filePath, Consumer<WikiPage> pageConsumer) {
        JsonFactory factory = new JsonFactory();

        try (var is = new FileInputStream(filePath);
             var reader = new InputStreamReader(is, StandardCharsets.UTF_8);
             JsonParser parser = factory.createParser(reader)) {

            // 최상위 배열 시작 '['
            if (parser.nextToken() != JsonToken.START_ARRAY) {
                throw new RuntimeException("JSON 파일이 배열([)로 시작하지 않습니다: " + filePath);
            }

            long sequenceId = 0;

            // 배열 내 각 오브젝트를 순회
            while (parser.nextToken() == JsonToken.START_OBJECT) {
                Integer namespace = null;
                String title = null;
                String text = null;

                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    String fieldName = parser.currentName();
                    parser.nextToken(); // 값으로 이동

                    switch (fieldName) {
                        case "namespace" -> namespace = parser.getIntValue();
                        case "title" -> title = parser.getText();
                        case "text" -> text = parser.getText();
                        case "contributors" -> {
                            // 배열 스킵 (사용하지 않음)
                            if (parser.currentToken() == JsonToken.START_ARRAY) {
                                while (parser.nextToken() != JsonToken.END_ARRAY) {
                                    // skip
                                }
                            }
                        }
                        default -> parser.skipChildren();
                    }
                }

                if (title != null) {
                    sequenceId++;

                    // 리다이렉트 감지
                    String redirectTo = null;
                    if (text != null) {
                        Matcher matcher = REDIRECT_PATTERN.matcher(text.trim());
                        if (matcher.find()) {
                            redirectTo = matcher.group(1).trim();
                        }
                    }

                    WikiPage page = WikiPage.builder()
                            .id(sequenceId)
                            .title(title)
                            .namespace(namespace != null ? namespace : 0)
                            .content(text)
                            .redirectTo(redirectTo)
                            .createdAt(null) // 나무위키 덤프에는 timestamp가 없음
                            .build();

                    pageConsumer.accept(page);
                }
            }

        } catch (Exception e) {
            log.error("나무위키 JSON 파싱 실패: {}", filePath, e);
            throw new RuntimeException("나무위키 JSON 파싱 실패: " + filePath, e);
        }
    }
}
