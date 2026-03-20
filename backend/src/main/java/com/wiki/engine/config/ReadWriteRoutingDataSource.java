package com.wiki.engine.config;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Read/Write DataSource 라우팅.
 *
 * <p>{@code @Transactional(readOnly = true)} → Replica,
 * {@code @Transactional} → Primary로 자동 분기한다.
 *
 * <p>반드시 {@code LazyConnectionDataSourceProxy}로 감싸야 한다.
 * Spring은 트랜잭션 동기화 전에 {@code getConnection()}을 호출하므로,
 * LazyProxy 없이는 {@code isCurrentTransactionReadOnly()}가 항상 false를 반환한다.
 */
public class ReadWriteRoutingDataSource extends AbstractRoutingDataSource {

    @Override
    protected Object determineCurrentLookupKey() {
        return TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                ? "replica"
                : "primary";
    }
}
