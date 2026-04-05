package com.wiki.engine.post.internal.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

/**
 * Redis 샤딩 설정.
 *
 * <p>redis.sharding.enabled=true이면 3개 독립 Redis 인스턴스에 대한
 * ConsistentHashRouter Bean을 생성한다.
 * false이면 Bean을 생성하지 않아, 각 서비스가 기존 단일 Redis로 동작한다.
 */
@Configuration
class RedisShardConfig {

    private static final Logger log = LoggerFactory.getLogger(RedisShardConfig.class);

    @Bean
    ConsistentHashRouter consistentHashRouter(
            @Value("${redis.sharding.enabled:false}") boolean enabled,
            @Value("${redis.shard1.host:localhost}") String host1,
            @Value("${redis.shard1.port:6379}") int port1,
            @Value("${redis.shard2.host:localhost}") String host2,
            @Value("${redis.shard2.port:6379}") int port2,
            @Value("${redis.shard3.host:localhost}") String host3,
            @Value("${redis.shard3.port:6379}") int port3,
            @Value("${redis.password:}") String password) {

        if (!enabled) {
            log.info("Redis 샤딩 비활성화 — 기존 단일 Redis로 동작");
            return null;
        }

        log.info("Redis 샤딩 활성화 — 3노드 ConsistentHashRouter 생성: {}:{}, {}:{}, {}:{}",
                host1, port1, host2, port2, host3, port3);

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
