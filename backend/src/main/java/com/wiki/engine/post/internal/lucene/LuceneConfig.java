package com.wiki.engine.post.internal.lucene;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.ko.KoreanAnalyzer;
import org.apache.lucene.analysis.ko.KoreanPartOfSpeechStopFilter;
import org.apache.lucene.analysis.ko.KoreanTokenizer;
import org.apache.lucene.analysis.ko.POS;
import org.apache.lucene.analysis.ko.dict.UserDictionary;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.ngram.NGramTokenFilter;
import org.apache.lucene.analysis.standard.StandardTokenizer;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.KeepOnlyLastCommitDeletionPolicy;
import org.apache.lucene.index.SnapshotDeletionPolicy;
import org.apache.lucene.facet.FacetsConfig;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.MMapDirectory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Lucene 검색엔진 설정.
 *
 * Primary/Replica 모드 분리:
 * - primary (기본): IndexWriter + SnapshotDeletionPolicy + NRT SearcherManager
 * - replica: SearcherManager only (읽기 전용, rsync 동기화 감지)
 *
 * lucene.mode 프로퍼티로 제어하며, 미설정 시 primary로 동작한다.
 *
 * MMapDirectory: OS 페이지 캐시를 활용하여 인덱스 파일을 메모리에 매핑한다.
 * KoreanAnalyzer (Nori): 한국어 형태소 분석기.
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

    /**
     * PerFieldAnalyzerWrapper: 필드별 분석기 분리.
     *
     * - 기본(title, content 등): Nori 한국어 형태소 분석기 (IC 제거)
     * - title_ngram: 2-3gram 문자 단위 분석기 (형태소 분석 우회)
     *
     * Nori가 불완전한 입력("안녕하세")을 비표준적으로 토큰화하는 문제를
     * N-gram 필드로 보완한다. 검색 시 Nori 매칭(MUST) + N-gram 부스트(SHOULD)를
     * 결합하면, "안녕하세요" 문서가 n-gram 오버랩 점수로 "하세" 문서보다 상위에 노출된다.
     *
     * PerFieldAnalyzerWrapper 덕분에 analyzer.tokenStream("title_ngram", text)로
     * 호출하면 자동으로 NGram 분석기가 적용된다.
     */
    @Bean
    Analyzer luceneAnalyzer() {
        Analyzer noriAnalyzer = createNoriAnalyzer();
        Analyzer ngramAnalyzer = createNgramAnalyzer();
        return new PerFieldAnalyzerWrapper(noriAnalyzer, Map.of("title_ngram", ngramAnalyzer));
    }

    /**
     * IC(감탄사)를 stop tags에서 제거한 Nori 분석기.
     * standalone '안녕'이 IC로 태깅 → 필터링 → 빈 쿼리가 되는 문제를 방지한다.
     */
    private Analyzer createNoriAnalyzer() {
        UserDictionary userDict = loadUserDictionary();
        Set<POS.Tag> stopTags = EnumSet.copyOf(KoreanPartOfSpeechStopFilter.DEFAULT_STOP_TAGS);
        stopTags.remove(POS.Tag.IC);
        return new KoreanAnalyzer(userDict, KoreanTokenizer.DEFAULT_DECOMPOUND, stopTags, false);
    }

    /**
     * 2-3gram 문자 단위 분석기.
     * "안녕하세요" → ["안녕", "녕하", "하세", "세요", "안녕하", "녕하세", "하세요"]
     * 형태소 분석 없이 문자 시퀀스를 직접 매칭하므로, 불완전 입력이나 OOV에 강하다.
     */
    private Analyzer createNgramAnalyzer() {
        return new Analyzer() {
            @Override
            protected TokenStreamComponents createComponents(String fieldName) {
                StandardTokenizer tokenizer = new StandardTokenizer();
                TokenStream filter = new LowerCaseFilter(tokenizer);
                filter = new NGramTokenFilter(filter, 2, 3, false);
                return new TokenStreamComponents(tokenizer, filter);
            }
        };
    }

    private UserDictionary loadUserDictionary() {
        try (InputStream is = getClass().getResourceAsStream("/userdict_ko.txt")) {
            if (is == null) {
                return null;
            }
            try (Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                return UserDictionary.open(reader);
            }
        } catch (IOException e) {
            return null;
        }
    }

    @Bean
    FacetsConfig facetsConfig() {
        return new FacetsConfig();
    }

    /**
     * Primary 모드: rsync 시 세그먼트 보호를 위한 SnapshotDeletionPolicy.
     * snapshot()으로 커밋 포인트를 고정하면 해당 세그먼트가 머지/GC에서 삭제되지 않는다.
     */
    @Bean
    @ConditionalOnProperty(name = "lucene.mode", havingValue = "primary", matchIfMissing = true)
    SnapshotDeletionPolicy snapshotDeletionPolicy() {
        return new SnapshotDeletionPolicy(new KeepOnlyLastCommitDeletionPolicy());
    }

    /**
     * Primary 모드에서만 IndexWriter 생성.
     * Replica 모드에서는 이 빈이 생성되지 않는다.
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "lucene.mode", havingValue = "primary", matchIfMissing = true)
    IndexWriter luceneIndexWriter(Directory directory, Analyzer analyzer,
                                  SnapshotDeletionPolicy snapshotPolicy) throws IOException {
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        config.setRAMBufferSizeMB(256);
        config.setIndexDeletionPolicy(snapshotPolicy);
        return new IndexWriter(directory, config);
    }

    /**
     * SearcherManager:
     * - Primary: IndexWriter 기반 NRT reader (uncommitted 변경도 즉시 반영)
     * - Replica: Directory 기반 reader (committed 변경만 감지, rsync 후 maybeRefresh로 갱신)
     *
     * Replica 모드에서 디렉토리가 비어있으면 (첫 배포, rsync 전) 빈 인덱스를 초기화한다.
     * rsync로 실제 인덱스가 복사되면 maybeRefresh()가 자동으로 감지하여 전환한다.
     */
    @Bean(destroyMethod = "close")
    SearcherManager luceneSearcherManager(
            @Autowired(required = false) IndexWriter writer,
            Directory directory, Analyzer analyzer) throws IOException {
        if (writer != null) {
            return new SearcherManager(writer, null);
        }
        // Replica: 디렉토리가 비어있으면 빈 인덱스 초기화 (기동 실패 방지)
        if (!DirectoryReader.indexExists(directory)) {
            try (IndexWriter tempWriter = new IndexWriter(directory, new IndexWriterConfig(analyzer))) {
                tempWriter.commit();
            }
        }
        return new SearcherManager(directory, null);
    }
}
