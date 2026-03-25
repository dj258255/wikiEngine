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
 *
 * 지원하는 import 유형:
 * 1. WIKI_IMPORT_PATH — 위키 데이터 (키워드 기반 카테고리 분류)
 * 2. NEWS_IMPORT_PATH — 뉴스 데이터 (고정 '뉴스' 카테고리)
 * 3. WEB_IMPORT_PATH  — 웹 텍스트 (고정 '웹 콘텐츠' 카테고리)
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.wiki-import.enabled", havingValue = "true")
@RequiredArgsConstructor
class WikiImportRunner implements CommandLineRunner {

    private final WikiImportService wikiImportService;

    @Value("${app.wiki-import.path:}")
    private String wikiPaths;

    @Value("${app.wiki-import.news-path:}")
    private String newsPaths;

    @Value("${app.wiki-import.web-path:}")
    private String webPaths;

    @Override
    public void run(String... args) {
        // 1. 위키 데이터 (키워드 분류)
        List<String> wiki = parsePaths(wikiPaths);
        if (!wiki.isEmpty()) {
            log.info("위키 임포트: {} 파일", wiki.size());
            wiki.forEach(p -> log.info("  - {}", p));
            wikiImportService.importFromFiles(wiki);
        }

        // 2. 뉴스 데이터 (고정 카테고리)
        List<String> news = parsePaths(newsPaths);
        if (!news.isEmpty()) {
            log.info("뉴스 임포트: {} 파일", news.size());
            news.forEach(p -> log.info("  - {}", p));
            wikiImportService.importWithFixedCategory(news, "뉴스");
        }

        // 3. 웹 콘텐츠 (고정 카테고리)
        List<String> web = parsePaths(webPaths);
        if (!web.isEmpty()) {
            log.info("웹 콘텐츠 임포트: {} 파일", web.size());
            web.forEach(p -> log.info("  - {}", p));
            wikiImportService.importWithFixedCategory(web, "웹 콘텐츠");
        }
    }

    private List<String> parsePaths(String paths) {
        if (paths == null || paths.isBlank()) return List.of();
        return Arrays.stream(paths.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
