package com.wiki.engine.wiki.internal;

import com.wiki.engine.wiki.WikiPage;
import lombok.extern.slf4j.Slf4j;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.FileInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 위키피디아 덤프 XML 파서.
 * StAX(Streaming API for XML)를 사용하여 대용량 XML 파일을 메모리 효율적으로 파싱한다.
 * DOM 방식과 달리 전체 문서를 메모리에 올리지 않아 수 GB 크기의 덤프 파일도 처리할 수 있다.
 *
 * 파싱 대상 XML 구조:
 * <pre>
 * &lt;mediawiki&gt;
 *   &lt;page&gt;
 *     &lt;title&gt;문서 제목&lt;/title&gt;
 *     &lt;ns&gt;0&lt;/ns&gt;
 *     &lt;id&gt;12345&lt;/id&gt;
 *     &lt;revision&gt;
 *       &lt;id&gt;67890&lt;/id&gt;
 *       &lt;timestamp&gt;2024-01-01T00:00:00Z&lt;/timestamp&gt;
 *       &lt;text&gt;문서 본문...&lt;/text&gt;
 *     &lt;/revision&gt;
 *   &lt;/page&gt;
 * &lt;/mediawiki&gt;
 * </pre>
 */
@Slf4j
public class WikiXmlParser {

    // Instant.parse()가 ISO 8601 (2024-01-01T00:00:00Z) 형식을 직접 파싱

    /** 리다이렉트 패턴: 한국어(#넘겨주기) 및 영어(#REDIRECT) 지원 */
    private static final Pattern REDIRECT_PATTERN = Pattern.compile("#(?:넘겨주기|REDIRECT)\\s*\\[\\[(.+?)\\]\\]", Pattern.CASE_INSENSITIVE);

    /**
     * 위키피디아 덤프 XML 파일을 파싱하여 페이지별로 콜백을 호출한다.
     * IS_COALESCING 옵션으로 분할된 텍스트 노드를 하나로 합쳐 처리한다.
     *
     * @param filePath XML 파일 경로
     * @param pageConsumer 파싱된 WikiPage를 전달받는 콜백 함수
     */
    public void parse(String filePath, Consumer<WikiPage> pageConsumer) {
        // 위키피디아 대형 문서(10만 자 이상)를 위해 JDK XML 엔티티 크기 제한 해제
        System.setProperty("jdk.xml.maxGeneralEntitySizeLimit", "0");
        System.setProperty("jdk.xml.totalEntitySizeLimit", "0");

        XMLInputFactory factory = XMLInputFactory.newInstance();
        // 분할된 텍스트 노드를 하나로 합침 (대용량 텍스트 처리에 필요)
        factory.setProperty(XMLInputFactory.IS_COALESCING, true);

        try (InputStream is = new FileInputStream(filePath)) {
            XMLStreamReader reader = factory.createXMLStreamReader(is);

            // 현재 파싱 중인 페이지의 필드값들
            Long id = null;
            String title = null;
            Integer namespace = null;
            String text = null;
            String timestamp = null;
            boolean inPage = false;       // <page> 태그 안에 있는지 여부
            boolean inRevision = false;   // <revision> 태그 안에 있는지 여부
            String currentElement = null; // 현재 처리 중인 XML 요소 이름

            while (reader.hasNext()) {
                int event = reader.next();

                switch (event) {
                    case XMLStreamConstants.START_ELEMENT:
                        currentElement = reader.getLocalName();
                        if ("page".equals(currentElement)) {
                            // 새 페이지 시작: 필드값 초기화
                            inPage = true;
                            id = null;
                            title = null;
                            namespace = null;
                            text = null;
                            timestamp = null;
                        } else if ("revision".equals(currentElement)) {
                            inRevision = true;
                        }
                        break;

                    case XMLStreamConstants.CHARACTERS:
                        if (inPage && currentElement != null) {
                            String content = reader.getText();
                            switch (currentElement) {
                                case "title" -> title = content;
                                case "id" -> {
                                    // page id만 저장 (revision id는 무시)
                                    if (!inRevision && id == null) {
                                        id = Long.parseLong(content.trim());
                                    }
                                }
                                case "ns" -> namespace = Integer.parseInt(content.trim());
                                case "text" -> text = content;
                                case "timestamp" -> {
                                    // 첫 번째 revision의 timestamp만 사용
                                    if (inRevision && timestamp == null) {
                                        timestamp = content;
                                    }
                                }
                            }
                        }
                        break;

                    case XMLStreamConstants.END_ELEMENT:
                        String endElement = reader.getLocalName();
                        if ("page".equals(endElement)) {
                            // 페이지 종료: 필수 필드가 모두 있으면 WikiPage 생성
                            if (id != null && title != null && namespace != null) {
                                // 리다이렉트 문서 감지: #넘겨주기 또는 #REDIRECT 패턴 확인
                                String redirectTo = null;
                                if (text != null) {
                                    Matcher matcher = REDIRECT_PATTERN.matcher(text);
                                    if (matcher.find()) {
                                        redirectTo = matcher.group(1);
                                    }
                                }

                                // timestamp 파싱 (ISO 8601 → Instant)
                                Instant createdAt = null;
                                if (timestamp != null) {
                                    createdAt = Instant.parse(timestamp);
                                }

                                WikiPage page = WikiPage.builder()
                                        .id(id)
                                        .title(title)
                                        .namespace(namespace)
                                        .content(text)
                                        .redirectTo(redirectTo)
                                        .createdAt(createdAt)
                                        .build();

                                pageConsumer.accept(page);
                            }
                            inPage = false;
                        } else if ("revision".equals(endElement)) {
                            inRevision = false;
                        }
                        currentElement = null;
                        break;
                }
            }

            reader.close();
        } catch (Exception e) {
            log.error("위키 XML 파싱 실패", e);
            throw new RuntimeException("위키 XML 파싱 실패", e);
        }
    }
}
