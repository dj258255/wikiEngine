package com.wiki.engine.parser;

import com.wiki.engine.entity.WikiPage;
import lombok.extern.slf4j.Slf4j;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.FileInputStream;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class WikiXmlParser {

    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ISO_DATE_TIME;
    private static final Pattern REDIRECT_PATTERN = Pattern.compile("#(?:넘겨주기|REDIRECT)\\s*\\[\\[(.+?)\\]\\]", Pattern.CASE_INSENSITIVE);

    public void parse(String filePath, Consumer<WikiPage> pageConsumer) {
        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.IS_COALESCING, true);

        try (InputStream is = new FileInputStream(filePath)) {
            XMLStreamReader reader = factory.createXMLStreamReader(is);

            Long id = null;
            String title = null;
            Integer namespace = null;
            String text = null;
            String timestamp = null;
            boolean inPage = false;
            boolean inRevision = false;
            String currentElement = null;

            while (reader.hasNext()) {
                int event = reader.next();

                switch (event) {
                    case XMLStreamConstants.START_ELEMENT:
                        currentElement = reader.getLocalName();
                        if ("page".equals(currentElement)) {
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
                                    if (!inRevision && id == null) {
                                        id = Long.parseLong(content.trim());
                                    }
                                }
                                case "ns" -> namespace = Integer.parseInt(content.trim());
                                case "text" -> text = content;
                                case "timestamp" -> {
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
                            if (id != null && title != null && namespace != null) {
                                String redirectTo = null;
                                if (text != null) {
                                    Matcher matcher = REDIRECT_PATTERN.matcher(text);
                                    if (matcher.find()) {
                                        redirectTo = matcher.group(1);
                                    }
                                }

                                LocalDateTime createdAt = null;
                                if (timestamp != null) {
                                    createdAt = LocalDateTime.parse(timestamp, TIMESTAMP_FORMAT);
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
            log.error("Failed to parse wiki XML", e);
            throw new RuntimeException("Failed to parse wiki XML", e);
        }
    }
}
