package com.wiki.engine.post.internal.autocomplete;

import com.wiki.engine.config.ConsistentHashRouter;
import com.wiki.engine.post.internal.search.SearchLogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 자동완성 prefix_topk 배치 빌드 — Spring Batch Job.
 *
 * <p>설계 문서의 MapReduce 배턴을 Spring Batch로 구현:
 * - Map: 검색 로그에서 인기 검색어 추출 → 접두사 분해 (원본 + 자모 + 초성)
 * - Reduce: 접두사별 Top-K 집계
 * - Write: Redis에 버전 네임스페이스로 적재 → 버전 포인터 원자적 전환
 *
 * <p>Spring Batch JobRepository가 실행 이력/상태/재시작을 관리하고,
 * @Scheduled(매시간)로 Job을 트리거한다.
 */
@Slf4j
@Configuration
public class AutocompleteBatchConfig {

    private static final int TOPK = 10;
    private static final int MAX_PREFIX_LENGTH = 10;
    private static final int MAX_QUERIES = 10_000;
    private static final Duration KEY_TTL = Duration.ofHours(2);
    private static final String VERSION_KEY = "prefix:current_version";

    @Bean
    Job autocompleteBuildJob(JobRepository jobRepository, Step autocompleteBuildStep) {
        return new JobBuilder("autocompleteBuildJob", jobRepository)
                .start(autocompleteBuildStep)
                .build();
    }

    @Bean
    Step autocompleteBuildStep(JobRepository jobRepository,
                               PlatformTransactionManager transactionManager,
                               Tasklet autocompleteBuildTasklet) {
        return new StepBuilder("autocompleteBuildStep", jobRepository)
                .tasklet(autocompleteBuildTasklet, transactionManager)
                .build();
    }

    @Bean
    Tasklet autocompleteBuildTasklet(SearchLogRepository searchLogRepository,
                                     StringRedisTemplate redis,
                                     @Nullable ConsistentHashRouter hashRouter,
                                     JsonMapper jsonMapper) {
        return (contribution, chunkContext) -> {
            long start = System.nanoTime();

            List<Object[]> topQueries = searchLogRepository.findTopQueriesSince(
                    LocalDateTime.now().minusDays(7), MAX_QUERIES);

            if (topQueries.isEmpty()) {
                log.info("[Batch] prefix_topk 빌드 스킵: 검색 로그 없음");
                return RepeatStatus.FINISHED;
            }

            // Map: 검색어 → 접두사 분해 (원본 + 자모 + 초성)
            Map<String, PriorityQueue<ScoredQuery>> prefixMap = new HashMap<>();

            for (Object[] row : topQueries) {
                String query = (String) row[0];
                long count = ((Number) row[1]).longValue();
                if (query == null || query.isBlank()) continue;

                String normalized = query.toLowerCase();
                addPrefixes(prefixMap, normalized, normalized, count);

                String decomposed = JamoDecomposer.decompose(normalized);
                addPrefixes(prefixMap, decomposed, normalized, count);

                String choseong = JamoDecomposer.extractChoseong(normalized);
                if (choseong.length() >= 2) {
                    addPrefixes(prefixMap, choseong, normalized, count);
                }
            }

            // Reduce + Write: Top-K 정렬 → Redis 적재
            long newVersion = System.currentTimeMillis();
            int keyCount = 0;

            for (var entry : prefixMap.entrySet()) {
                List<String> topK = entry.getValue().stream()
                        .sorted(Comparator.comparingLong(ScoredQuery::score).reversed())
                        .map(ScoredQuery::query)
                        .toList();
                try {
                    String key = "prefix:v" + newVersion + ":" + entry.getKey();
                    StringRedisTemplate target = hashRouter != null ? hashRouter.getNode(key) : redis;
                    target.opsForValue().set(key, jsonMapper.writeValueAsString(topK), KEY_TTL);
                    keyCount++;
                } catch (Exception e) {
                    log.warn("[Batch] Redis 저장 실패: prefix={}, error={}", entry.getKey(), e.getMessage());
                }
            }

            // 버전 포인터 원자적 전환
            redis.opsForValue().set(VERSION_KEY, String.valueOf(newVersion));

            long elapsed = (System.nanoTime() - start) / 1_000_000;
            log.info("[Batch] prefix_topk 갱신 완료: version={}, keys={}, 소스 쿼리={}, {}ms",
                    newVersion, keyCount, topQueries.size(), elapsed);

            contribution.incrementWriteCount(keyCount);
            return RepeatStatus.FINISHED;
        };
    }

    private void addPrefixes(Map<String, PriorityQueue<ScoredQuery>> prefixMap,
                             String text, String originalQuery, long count) {
        for (int len = 1; len <= Math.min(text.length(), MAX_PREFIX_LENGTH); len++) {
            String prefix = text.substring(0, len);
            PriorityQueue<ScoredQuery> heap = prefixMap.computeIfAbsent(prefix,
                    k -> new PriorityQueue<>(Comparator.comparingLong(ScoredQuery::score)));
            boolean exists = heap.stream().anyMatch(sq -> sq.query().equals(originalQuery));
            if (!exists) {
                heap.offer(new ScoredQuery(originalQuery, count));
                if (heap.size() > TOPK) {
                    heap.poll();
                }
            }
        }
    }

    private record ScoredQuery(String query, long score) {}
}
