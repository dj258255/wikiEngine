package com.wiki.engine.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;

import javax.sql.DataSource;
import java.util.Map;

/**
 * Primary/Replica DataSource 라우팅 설정 (Phase 12).
 *
 * <p>Spring Boot의 단일 DataSource 자동설정을 대체한다.
 * {@code @Transactional(readOnly=true)} → Replica, {@code @Transactional} → Primary.
 *
 * <p>LazyConnectionDataSourceProxy가 실제 Statement 생성 시점까지 커넥션 획득을 지연시켜,
 * 트랜잭션 속성이 설정된 후에 라우팅 결정을 한다.
 */
@Configuration(proxyBeanMethods = false)
public class DataSourceConfig {

    @Bean
    @ConfigurationProperties("spring.datasource.primary.hikari")
    public HikariDataSource primaryDataSource() {
        return new HikariDataSource();
    }

    @Bean
    @ConfigurationProperties("spring.datasource.replica.hikari")
    public HikariDataSource replicaDataSource() {
        return new HikariDataSource();
    }

    @Bean
    @Primary
    public DataSource dataSource(
            @Qualifier("primaryDataSource") HikariDataSource primaryDataSource,
            @Qualifier("replicaDataSource") HikariDataSource replicaDataSource) {
        ReadWriteRoutingDataSource routingDS = new ReadWriteRoutingDataSource();
        routingDS.setTargetDataSources(Map.of(
                "primary", primaryDataSource,
                "replica", replicaDataSource
        ));
        routingDS.setDefaultTargetDataSource(primaryDataSource);
        routingDS.afterPropertiesSet();

        return new LazyConnectionDataSourceProxy(routingDS);
    }

    // Flyway는 application.yml에서 직접 url/user/password를 지정하여 Primary에서만 실행.
    // @FlywayDataSource 빈 방식은 Spring Boot 4에서 자동설정과 충돌하므로 설정 기반으로 전환.
}
