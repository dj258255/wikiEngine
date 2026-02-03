package com.wiki.engine.runner;

import com.wiki.engine.service.WikiImportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@Profile("import")
@RequiredArgsConstructor
public class WikiImportRunner implements CommandLineRunner {

    private final WikiImportService wikiImportService;

    @Override
    public void run(String... args) {
        if (args.length == 0) {
            log.error("Usage: --spring.profiles.active=import <xml-file-path>");
            return;
        }

        String filePath = args[0];
        log.info("Starting import from: {}", filePath);
        wikiImportService.importFromXml(filePath);
    }
}
