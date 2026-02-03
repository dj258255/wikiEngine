package com.wiki.engine.service;

import com.wiki.engine.entity.WikiPage;
import com.wiki.engine.parser.WikiXmlParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
@RequiredArgsConstructor
public class WikiImportService {

    private final JdbcTemplate jdbcTemplate;
    private static final int BATCH_SIZE = 1000;

    public void importFromXml(String filePath) {
        log.info("Starting wiki import from: {}", filePath);

        WikiXmlParser parser = new WikiXmlParser();
        List<WikiPage> batch = new ArrayList<>(BATCH_SIZE);
        AtomicLong totalCount = new AtomicLong(0);
        long startTime = System.currentTimeMillis();

        parser.parse(filePath, page -> {
            batch.add(page);

            if (batch.size() >= BATCH_SIZE) {
                saveBatch(batch);
                long count = totalCount.addAndGet(batch.size());
                batch.clear();

                if (count % 10000 == 0) {
                    long elapsed = System.currentTimeMillis() - startTime;
                    double rate = count / (elapsed / 1000.0);
                    log.info("Imported {} pages ({:.0f} pages/sec)", count, rate);
                }
            }
        });

        if (!batch.isEmpty()) {
            saveBatch(batch);
            totalCount.addAndGet(batch.size());
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("Import completed: {} pages in {} seconds", totalCount.get(), elapsed / 1000);
    }

    private void saveBatch(List<WikiPage> pages) {
        String sql = """
            INSERT INTO wiki_pages (id, title, namespace, content, redirect_to, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON DUPLICATE KEY UPDATE
                title = VALUES(title),
                namespace = VALUES(namespace),
                content = VALUES(content),
                redirect_to = VALUES(redirect_to),
                created_at = VALUES(created_at)
            """;

        jdbcTemplate.batchUpdate(sql, pages, BATCH_SIZE, (ps, page) -> {
            ps.setLong(1, page.getId());
            ps.setString(2, page.getTitle());
            ps.setInt(3, page.getNamespace());
            ps.setString(4, page.getContent());
            ps.setString(5, page.getRedirectTo());
            ps.setObject(6, page.getCreatedAt());
        });
    }
}
