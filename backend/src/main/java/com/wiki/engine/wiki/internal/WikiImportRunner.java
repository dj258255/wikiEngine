package com.wiki.engine.wiki.internal;

import com.wiki.engine.wiki.WikiImportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * 위키 데이터 임포트 실행기.
 * .env 파일에서 WIKI_IMPORT_ENABLED=true 설정 시 앱 시작과 함께 자동 실행된다.
 * 임포트할 XML 파일 경로는 WIKI_IMPORT_PATH 환경변수로 콤마 구분하여 지정한다.
 *
 * 예시: WIKI_IMPORT_PATH=/data/kowiki.xml,/data/enwiki.xml
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.wiki-import.enabled", havingValue = "true")
@RequiredArgsConstructor
class WikiImportRunner implements CommandLineRunner {

    private final WikiImportService wikiImportService;

    @Value("${app.wiki-import.path}")
    private String importPaths;

    @Override
    public void run(String... args) {
        List<String> paths = Arrays.stream(importPaths.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        log.info("위키 임포트 실행: {} 파일", paths.size());
        paths.forEach(p -> log.info("  - {}", p));

        wikiImportService.importFromXmlFiles(paths);
    }
}
