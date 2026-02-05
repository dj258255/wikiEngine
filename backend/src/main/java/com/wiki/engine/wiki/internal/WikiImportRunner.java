package com.wiki.engine.wiki.internal;

import com.wiki.engine.wiki.WikiImportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 위키 데이터 임포트 실행기.
 * "import" 프로필이 활성화된 경우에만 동작하는 CommandLineRunner이다.
 * 애플리케이션 시작 시 커맨드라인 인자로 전달된 XML 파일 경로를 받아 임포트를 실행한다.
 *
 * 실행 예시:
 * java -jar wiki-engine.jar --spring.profiles.active=import /path/to/wiki-dump.xml
 */
@Slf4j
@Component
@Profile("import")
@RequiredArgsConstructor
class WikiImportRunner implements CommandLineRunner {

    private final WikiImportService wikiImportService;

    @Override
    public void run(String... args) {
        if (args.length == 0) {
            log.error("사용법: --spring.profiles.active=import <xml-파일-경로>");
            return;
        }

        String filePath = args[0];
        log.info("위키 임포트 실행: {}", filePath);
        wikiImportService.importFromXml(filePath);
    }
}
