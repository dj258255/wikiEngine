package com.wiki.engine.post.internal;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.search.SearcherManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Lucene Replica 모드 전용 SearcherManager 주기적 갱신.
 *
 * Primary 모드에서는 매 write 후 maybeRefresh()를 호출하므로 이 컴포넌트 불필요.
 * Replica 모드에서는 rsync로 갱신된 인덱스 파일을 감지하기 위해
 * 30초마다 maybeRefresh()를 호출한다.
 *
 * rsync 중 레이스 컨디션 방지 (LUCENE-628):
 * - pause(): rsync 시작 전 호출하여 maybeRefresh 차단
 * - resume(): rsync 완료 후 호출하여 즉시 refresh + 주기적 refresh 재개
 * - 30초 auto-resume: 스크립트 비정상 종료 시 안전장치
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "lucene.mode", havingValue = "replica")
class LuceneReplicaRefresher {

    private final SearcherManager searcherManager;
    private volatile boolean paused = false;
    private volatile long pausedAt = 0;

    private static final long AUTO_RESUME_TIMEOUT_MS = 30_000;

    @Scheduled(fixedRate = 30_000)
    void refresh() throws IOException {
        if (paused && System.currentTimeMillis() - pausedAt > AUTO_RESUME_TIMEOUT_MS) {
            log.warn("Refresh pause 타임아웃 — 자동 resume (lucene-sync.sh 실패 가능성)");
            paused = false;
        }
        if (!paused) {
            searcherManager.maybeRefresh();
        }
    }

    void pause() {
        this.pausedAt = System.currentTimeMillis();
        this.paused = true;
        log.info("Lucene replica refresh paused for rsync");
    }

    void resume() throws IOException {
        this.paused = false;
        searcherManager.maybeRefresh();
        log.info("Lucene replica refresh resumed");
    }
}
