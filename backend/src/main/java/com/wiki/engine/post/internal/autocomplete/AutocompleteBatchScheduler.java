package com.wiki.engine.post.internal.autocomplete;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 자동완성 배치 Job을 매시간 트리거한다.
 *
 * <p>Spring Batch JobLauncher로 실행하면:
 * - JobRepository에 실행 이력(시작시간, 종료시간, 상태, 처리 건수) 자동 기록
 * - 타임스탬프 파라미터로 매번 새 JobInstance 생성 (중복 실행 방지)
 * - 실패 시 재시작 가능 (FAILED 상태에서 동일 파라미터로 재실행)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AutocompleteBatchScheduler {

    private final JobLauncher jobLauncher;
    private final Job autocompleteBuildJob;

    @Scheduled(cron = "0 0 * * * *")
    public void runAutocompleteBuildJob() {
        try {
            var params = new JobParametersBuilder()
                    .addLong("timestamp", System.currentTimeMillis())
                    .toJobParameters();
            jobLauncher.run(autocompleteBuildJob, params);
        } catch (Exception e) {
            log.error("[Batch] 자동완성 배치 Job 실행 실패", e);
        }
    }
}
