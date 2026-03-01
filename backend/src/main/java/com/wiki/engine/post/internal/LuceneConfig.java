package com.wiki.engine.post.internal;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.ko.KoreanAnalyzer;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.MMapDirectory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Lucene 검색엔진 설정.
 *
 * MMapDirectory: OS 페이지 캐시를 활용하여 인덱스 파일을 메모리에 매핑한다.
 * JVM 힙이 아닌 OS 영역을 사용하므로, 전체 RAM의 3/4을 페이지 캐시에 남겨둔다.
 *
 * KoreanAnalyzer (Nori): 한국어 형태소 분석기.
 * "대한민국을" → ["대한민국"], "한국어" → ["한국어"]로 정규화한다.
 * ngram의 "대한민국" → ["대한","한민","민국"] 분할 문제를 해결한다.
 */
@Configuration
class LuceneConfig {

    @Value("${lucene.index-path}")
    private String indexPath;

    @Bean
    Directory luceneDirectory() throws IOException {
        Path path = Path.of(indexPath);
        Files.createDirectories(path);
        return MMapDirectory.open(path);
    }

    @Bean
    Analyzer luceneAnalyzer() {
        return new KoreanAnalyzer();
    }

    @Bean(destroyMethod = "close")
    IndexWriter luceneIndexWriter(Directory directory, Analyzer analyzer) throws IOException {
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        config.setRAMBufferSizeMB(256);
        return new IndexWriter(directory, config);
    }

    @Bean(destroyMethod = "close")
    SearcherManager luceneSearcherManager(IndexWriter writer) throws IOException {
        return new SearcherManager(writer, null);
    }
}
