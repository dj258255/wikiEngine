# 0007. Nginx R/W Split + MySQL Replication

- **상태**: Accepted
- **결정일**: 2026-02-25
- **관련 ADR**: [#0005](./0005-index-sync-modulith-cdc.md), [#0006](./0006-tiered-cache-consistent-hashing.md)

## Context

서버 1, 2 양쪽에서 트래픽을 받지만 **MySQL 은 Primary-Replica 구성** 이다. Replica 는 read-only. 따라서:

- 쓰기 요청(POST/PUT/DELETE)이 서버 2 로 가면, 서버 2 의 앱이 Replica 에 INSERT 시도 → 에러
- 또는 서버 2 가 매번 서버 1 의 Primary 로 원격 연결 → 네트워크 비용

요구사항:

- 모든 쓰기가 Primary 로 라우팅
- 읽기는 양 서버 분산
- 라우팅이 애플리케이션 코드에 흩어지지 않게 (관심사 분리)

## Decision Drivers

1. **데이터 일관성** — 쓰기는 반드시 Primary 로
2. **단순성** — 라우팅 로직이 한 곳에
3. **읽기 분산** — 서버 1, 2 부하 균등
4. **장애 시 fallback** — 서버 1 죽으면 GET 은 서버 2 로 자동 이전

## Considered Options

### Option A: 애플리케이션 레벨 라우팅 (AbstractRoutingDataSource)

```java
@Bean
public DataSource routingDataSource() {
    return new AbstractRoutingDataSource() {
        protected Object determineCurrentLookupKey() {
            return TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                ? "replica" : "primary";
        }
    };
}
```

- 장점: Spring 표준, 라우팅이 트랜잭션 의미와 일치
- 단점:
  - `@Transactional(readOnly = true)` 누락 시 잘못된 라우팅
  - 서버 2 의 앱이 여전히 Primary 로 원격 호출 가능 (R/W split 의 본질은 해결하지만 서버 간 부하 분산은 별개)

### Option B: ProxySQL / MaxScale 같은 DB 프록시

- 장점: 자동 쿼리 분석으로 READ/WRITE 분리, fail-over 지원
- 단점:
  - **추가 프로세스** — 12 GB 머신에 또 다른 컴포넌트
  - 운영 복잡도 ↑ (룰 정의, 헬스체크, 모니터링)

### Option C: Nginx L7 — HTTP 메서드 기반 라우팅 (선택)

```nginx
location ~ ^/api/ {
    if ($request_method = GET)  { proxy_pass http://read_backend; }
    if ($request_method = HEAD) { proxy_pass http://read_backend; }
    # POST, PUT, DELETE, PATCH
    proxy_pass http://write_backend;
}

upstream read_backend {
    server srv1:8080;
    server srv2:8080;  # Round Robin
}

upstream write_backend {
    server srv1:8080;       # Primary 만
    server srv2:8080 backup;
}
```

- 장점:
  - **메서드 레벨에서 명확한 분리** — DB 트랜잭션 어노테이션 누락 위험 제거
  - 서버 1 (Primary 컨테이너 같은 노드) 이 쓰기 전담 → 네트워크 hop 1 회 제거
  - GET 은 서버 1, 2 RR 로 부하 분산
  - 서버 1 다운 시 GET 은 서버 2 로 자동, 쓰기는 백업 라우팅 (서버 2 의 앱이 Primary 로 원격 연결)
- 단점:
  - **REST 메서드 ≠ DB 동작** 위험 — 멱등한 GET 안에서 쓰기를 하면 잘못된 라우팅. 코드 컨벤션으로 강제 필요
  - Nginx 설정이 또 하나의 운영 대상

## Decision

**Option C — Nginx L7 HTTP 메서드 기반 R/W Split** 선택.

선택 이유:

1. **메서드와 DB 동작 일치 강제** — REST 가이드라인에 충실하면 자연스러운 분리
2. **물리적 라우팅 통합** — 같은 노드(서버 1) 안에 Primary 와 쓰기 앱이 함께 있어 DB 연결이 로컬 socket. 네트워크 hop 제거.
3. **운영 단순** — 이미 Nginx 가 L7 LB 로 배치되어 있음. 추가 컴포넌트 0.

Option B(ProxySQL)는 더 정교하지만 12 GB 메모리 압박과 운영 인력 1 명 제약에서 과잉.

## Consequences

### Positive

- **쓰기 트랜잭션 100% Primary** — 코드에서 의도치 않은 라우팅 실수 원천 차단
- **GET 부하 50:50 분산** — k6 부하 테스트에서 서버 1, 2 CPU 사용률 ±5%p 이내
- **로컬 socket** — 서버 1 의 앱 → Primary 가 같은 노드라 TCP 오버헤드 거의 0
- **장애 시 자동 fallback** — `backup` 키워드로 Primary 다운 시 GET 은 서버 2 로

### Negative

- **GET 안에서 쓰기 금지** 가 코드 컨벤션에 의존 — 위반 시 Replica 에 쓰기 시도 에러
- **Replication lag** — Replica 가 늦으면 "방금 쓴 글을 읽기 GET 으로 못 보는" 경우 발생 (현재 lag ≈ 1 s)
- **사용자가 자신의 글을 못 봄** — 쓰고 바로 새로고침 시 Replica 가 아직 못 따라잡았다면 빈 결과
  - 완화책: 쓰기 직후 응답에 새 데이터 포함, 클라이언트가 캐시
  - 또는 특정 GET 경로(`/api/posts/{id}` 의 본인 글)는 강제 Primary 라우팅

### Neutral

- Lucene 인덱스 동기화는 별도 경로 ([ADR-0005](./0005-index-sync-modulith-cdc.md))
- Replica 가 끊겼을 때 운영 — 알람 + 수동 복구 절차

## Validation

- **POST P95 < 200 ms** — k6 실측 142 ms (충족)
- **GET 부하 분산** — Grafana 대시보드에서 서버 1, 2 CPU 사용률 ±5%p
- **Replica lag < 5 s** — Prometheus 알람 (`mysql_slave_lag_seconds`)
- **장애 drill** — 서버 1 강제 정지 후 GET 은 서버 2 만 응답, POST 는 5xx 응답 (목표 동작)

만약 Replication lag 가 사용자 경험에 자주 영향을 주면 (예: 자기 글이 안 보임 민원), `@Transactional(readOnly = false)` 강제 Primary 라우팅 분기 도입 검토.

## References

- Nginx 설정: `ansible/roles/nginx/templates/wiki.conf.j2`
- DataSource 설정: `backend/src/main/java/com/wiki/engine/config/DataSourceConfig.java`
- MySQL Replication 설정: `ansible/roles/mysql/tasks/replication.yml`
- 관련 다이어그램: [readme-images/request-flow.svg](../readme-images/request-flow.svg)
