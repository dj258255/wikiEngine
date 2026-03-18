package com.wiki.engine.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();

        manager.registerCustomCache("searchResults",
                Caffeine.newBuilder()
                        .maximumSize(10_000)
                        .expireAfterWrite(Duration.ofMinutes(5))
                        .recordStats()
                        .build());

        // autocomplete 캐시 유지 — Trie fallback용 (Phase 11 이후 Redis flat KV로 대체)
        manager.registerCustomCache("autocomplete",
                Caffeine.newBuilder()
                        .maximumSize(10_000)
                        .expireAfterWrite(Duration.ofMinutes(10))
                        .recordStats()
                        .build());

        manager.registerCustomCache("postDetail",
                Caffeine.newBuilder()
                        .maximumSize(50_000)
                        .expireAfterAccess(Duration.ofMinutes(30))
                        .recordStats()
                        .build());

        return manager;
    }

    /** L1 캐시 — TieredCacheService에서 직접 사용 (검색 결과) */
    @Bean
    Cache<String, Object> searchResultsL1Cache() {
        return Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(Duration.ofMinutes(5))
                .recordStats()
                .build();
    }

    /** L1 캐시 — TieredCacheService에서 직접 사용 (게시글 상세) */
    @Bean
    Cache<String, Object> postDetailL1Cache() {
        return Caffeine.newBuilder()
                .maximumSize(50_000)
                .expireAfterAccess(Duration.ofMinutes(30))
                .recordStats()
                .build();
    }
}
