package com.wiki.engine.post.internal.lucene;

import com.wiki.engine.common.BusinessException;
import com.wiki.engine.common.ErrorCode;
import org.apache.lucene.index.IndexCommit;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.SnapshotDeletionPolicy;
import org.apache.lucene.search.SearcherManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;

/**
 * Lucene 내부 관리 API.
 * rsync 기반 인덱스 동기화를 위한 엔드포인트를 제공한다.
 *
 * 보안: /internal/** 경로는 Nginx에서 deny all + Spring Security에서 내부 IP만 허용.
 *
 * Primary 전용:
 * - POST /internal/lucene/snapshot: commit + snapshot (rsync 전 호출)
 * - DELETE /internal/lucene/snapshot/{gen}: snapshot 해제 (rsync 후 호출)
 * - POST /internal/lucene/commit: 단순 commit
 *
 * Replica 전용:
 * - POST /internal/lucene/pause-refresh: rsync 중 maybeRefresh 차단
 * - POST /internal/lucene/resume-refresh: rsync 완료 후 즉시 refresh + 재개
 *
 * 양쪽:
 * - POST /internal/lucene/refresh: SearcherManager maybeRefresh
 */
@RestController
@RequestMapping("/internal/lucene")
class LuceneInternalController {

    private final IndexWriter indexWriter;
    private final SnapshotDeletionPolicy snapshotPolicy;
    private final SearcherManager searcherManager;
    private final LuceneReplicaRefresher replicaRefresher;

    LuceneInternalController(
            @Autowired(required = false) IndexWriter indexWriter,
            @Autowired(required = false) SnapshotDeletionPolicy snapshotPolicy,
            SearcherManager searcherManager,
            @Autowired(required = false) LuceneReplicaRefresher replicaRefresher) {
        this.indexWriter = indexWriter;
        this.snapshotPolicy = snapshotPolicy;
        this.searcherManager = searcherManager;
        this.replicaRefresher = replicaRefresher;
    }

    @PostMapping("/snapshot")
    ResponseEntity<String> snapshot() throws IOException {
        requirePrimary(indexWriter, snapshotPolicy);
        indexWriter.commit();
        IndexCommit commit = snapshotPolicy.snapshot();
        return ResponseEntity.ok(String.valueOf(commit.getGeneration()));
    }

    @DeleteMapping("/snapshot/{generation}")
    ResponseEntity<Void> releaseSnapshot(@PathVariable long generation) throws IOException {
        requirePrimary(snapshotPolicy);
        for (IndexCommit commit : snapshotPolicy.getSnapshots()) {
            if (commit.getGeneration() == generation) {
                snapshotPolicy.release(commit);
                break;
            }
        }
        return ResponseEntity.ok().build();
    }

    @PostMapping("/commit")
    ResponseEntity<Void> commit() throws IOException {
        requirePrimary(indexWriter);
        indexWriter.commit();
        return ResponseEntity.ok().build();
    }

    @PostMapping("/refresh")
    ResponseEntity<Void> refresh() throws IOException {
        searcherManager.maybeRefresh();
        return ResponseEntity.ok().build();
    }

    @PostMapping("/pause-refresh")
    ResponseEntity<Void> pauseRefresh() {
        requireReplica();
        replicaRefresher.pause();
        return ResponseEntity.ok().build();
    }

    @PostMapping("/resume-refresh")
    ResponseEntity<Void> resumeRefresh() throws IOException {
        requireReplica();
        replicaRefresher.resume();
        return ResponseEntity.ok().build();
    }

    private void requirePrimary(Object... beans) {
        for (Object bean : beans) {
            if (bean == null) {
                throw new BusinessException(ErrorCode.METHOD_NOT_ALLOWED);
            }
        }
    }

    private void requireReplica() {
        if (replicaRefresher == null) {
            throw new BusinessException(ErrorCode.METHOD_NOT_ALLOWED);
        }
    }
}
