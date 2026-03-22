package com.wiki.engine.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

/**
 * Redis 샤딩 설정 (Phase 15).
 *
 * <p>redis.shards 프로퍼티가 설정되면 3개 독립 Redis 인스턴스에 대한
 * StringRedisTemplate을 생성하고, ConsistentHashRouter로 키 기반 라우팅을 수행한다.
 *
 * <p>기존 단일 Redis(spring.data.redis)는 블랙리스트 등 비샤딩 용도로 유지된다.
 */
@Configuration
@ConditionalOnProperty(name = "redis.sharding.enabled", havingValue = "true")
class RedisShardConfig {

    @Bean
    ConsistentHashRouter consistentHashRouter(
            @Value("${redis.shard1.host}") String host1,
            @Value("${redis.shard1.port:6379}") int port1,
            @Value("${redis.shard2.host}") String host2,
            @Value("${redis.shard2.port:6379}") int port2,
            @Value("${redis.shard3.host}") String host3,
            @Value("${redis.shard3.port:6379}") int port3,
            @Value("${redis.password:}") String password) {

        List<StringRedisTemplate> shardNodes = List.of(
                createTemplate(host1, port1, password),
                createTemplate(host2, port2, password),
                createTemplate(host3, port3, password)
        );
        return new ConsistentHashRouter(shardNodes);
    }

    private StringRedisTemplate createTemplate(String host, int port, String password) {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(host, port);
        if (password != null && !password.isBlank()) {
            config.setPassword(password);
        }
        LettuceConnectionFactory factory = new LettuceConnectionFactory(config);
        factory.afterPropertiesSet();
        return new StringRedisTemplate(factory);
    }
}
