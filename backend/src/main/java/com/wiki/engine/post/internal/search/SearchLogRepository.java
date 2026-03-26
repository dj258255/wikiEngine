package com.wiki.engine.post.internal.search;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface SearchLogRepository extends JpaRepository<SearchLog, Long> {

    /**
     * 시간 버킷 기반 upsert.
     * 같은 (query, time_bucket)이면 count를 누적, 없으면 새로 삽입.
     * SearchLogCollector.flush()에서 호출.
     */
    @Modifying
    @Query(value = """
            INSERT INTO search_logs (query, time_bucket, count)
            VALUES (:query, :timeBucket, :count)
            ON DUPLICATE KEY UPDATE count = count + VALUES(count)
            """, nativeQuery = true)
    void upsert(@Param("query") String query,
                @Param("timeBucket") LocalDateTime timeBucket,
                @Param("count") long count);

    /**
     * 지정 시점 이후의 인기 검색어를 시간 버킷별 합산하여 조회.
     * Trie 인기도 점수 계산에 사용.
     *
     * 반환: query별 (총 검색 횟수, 가장 최근 버킷 시점)
     */
    @Query(value = """
            SELECT query, SUM(count) AS total_count, MAX(time_bucket) AS last_searched
            FROM search_logs
            WHERE time_bucket >= :since
            GROUP BY query
            ORDER BY total_count DESC
            LIMIT :lmt
            """, nativeQuery = true)
    List<Object[]> findTopQueriesSince(@Param("since") LocalDateTime since,
                                       @Param("lmt") int lmt);

    /**
     * 오래된 로그 정리. 30일 이전 데이터 삭제.
     */
    @Modifying
    @Query(value = """
            DELETE FROM search_logs WHERE time_bucket < :before
            """, nativeQuery = true)
    int deleteOlderThan(@Param("before") LocalDateTime before);
}
