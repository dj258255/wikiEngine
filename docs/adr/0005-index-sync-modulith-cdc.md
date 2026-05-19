# 0005. 인덱스 동기화 — Spring Modulith 이벤트 + Debezium CDC 이중화

- **상태**: Accepted
- **결정일**: 2026-03-10
- **관련 ADR**: [#0001](./0001-lucene-direct-vs-elasticsearch.md), [#0007](./0007-nginx-rw-split-mysql-replication.md)

## Context

Lucene 인덱스는 **서버 1, 서버 2 양쪽에 각자** 존재한다 ([ADR-0001](./0001-lucene-direct-vs-elasticsearch.md) — 단일 노드 임베드). 쓰기는 서버 1(Primary)로만 가지만([ADR-0007](./0007-nginx-rw-split-mysql-replication.md)), 검색은 양쪽에서 처리한다.

따라서 **게시글 INSERT/UPDATE/DELETE 가 양쪽 인덱스에 모두 반영**되어야 한다. 동기화 실패 시:

- 서버 1 검색 결과: 신규 게시글 포함
- 서버 2 검색 결과: 신규 게시글 누락
- 사용자가 새로고침할 때마다 결과가 달라지는 일관성 문제 발생

요구사항:

- 트랜잭션 일관성 (게시글 commit 후에만 인덱스 갱신)
- 서버 1 ↔ 서버 2 인덱스 동기화 (네트워크 단절·재시작 시에도 결국 일관성)
- Lucene 인덱싱 실패가 CRUD 트랜잭션을 깨면 안 됨

## Decision Drivers

1. **CRUD 트랜잭션 무결성** (최우선 — 데이터 원본은 MySQL)
2. **양 서버 인덱스 일관성** (결국 일관성, ≤ 수 초)
3. **운영 단순성**
4. **단일 실패점 회피**

## Considered Options

### Option A: 동기 인덱싱 (CRUD 와 같은 트랜잭션)

```java
@Transactional
public void createPost(...) {
    postRepository.save(post);
    luceneIndexService.add(post);  // 같은 트랜잭션
}
```

- 장점: 즉시 검색 가능, 코드 단순
- 단점:
  - **Lucene 실패 = CRUD 실패** — 인덱스 디스크 풀, 락 등의 문제로 게시글 저장 자체가 안 됨
  - 서버 2 인덱스 갱신 방법 없음 (트랜잭션은 서버 1 안)
  - 다른 서버로 전파 안 됨

### Option B: Spring Modulith 이벤트 단독

```java
@Transactional
public void createPost(...) {
    postRepository.save(post);
    eventPublisher.publishEvent(new PostEvent.Created(post));
}

@TransactionalEventListener(phase = AFTER_COMMIT)
public void onCreated(PostEvent.Created event) {
    luceneIndexService.add(event.post());
}
```

- 장점:
  - 트랜잭션 분리 — Lucene 실패해도 CRUD 성공
  - 인-프로세스, RPC 없음, 같은 JVM
- 단점:
  - **이벤트는 같은 JVM 안에만 전파** — 서버 2 가 받을 수 없음
  - 앱 재시작 직전 발생한 이벤트는 유실 가능

### Option C: Debezium CDC 단독

```
MySQL binlog → Debezium → Kafka "posts.cdc"
                                ↓
                Server 1 & 2 PostsCdcConsumer → Lucene 인덱스 갱신
```

- 장점:
  - **양 서버가 같은 Kafka 토픽 소비** — 자연스러운 일관성
  - 앱이 죽어도 binlog 는 남아 있음 → 재시작 후 따라잡기 가능
- 단점:
  - 인덱싱이 binlog 지연만큼 느려짐 (수십 ms ~ 수 초)
  - Kafka·Debezium 가용성에 의존

### Option D: Modulith 이벤트 + Debezium CDC 이중화 (선택)

서버 1 안에서는 **Modulith 이벤트로 즉시** 인덱싱하고, 서버 2 는 **CDC 로** 인덱싱.

```
[서버 1 — 같은 JVM 안에서 트랜잭션 직후 인덱싱]
PostService.create()
  → @TransactionalEventListener(AFTER_COMMIT)
  → LuceneIndexEventHandler
  → server1 Lucene IndexWriter (maybeRefresh)

[서버 2 — CDC 컨슈머가 binlog 변경 적용]
MySQL binlog
  → Debezium
  → Kafka "posts.cdc"
  → server2 PostsCdcConsumer
  → server2 Lucene IndexWriter
```

- 장점: 양 경로 모두 동작 — **단일 실패점 회피**
- 단점: 두 경로 모두 운영해야 함

## Decision

**Option D — Modulith 이벤트 + Debezium CDC 이중화** 선택.

각 경로의 역할:

- **Modulith 이벤트** = 서버 1 즉시성 + CRUD 트랜잭션 분리. 사용자가 자신의 글을 바로 검색했을 때 결과에 등장.
- **Debezium CDC** = 서버 1↔2 일관성 + 재시작 복원력. binlog 가 source of truth, 어떤 이유로 이벤트 핸들러가 실패해도 CDC 가 따라잡음.

서버 1 의 CDC 컨슈머는 자기가 이미 인덱싱한 글에 대해 멱등 처리 (Lucene `updateDocument` 는 동일 id 면 덮어쓰므로 안전).

### 결정의 핵심: source of truth 분리

- 데이터 원본 = MySQL (Flyway + JPA)
- 인덱스 = Lucene (재구축 가능, 보조 데이터)
- Lucene 실패 ≠ 데이터 손실. 최악의 경우 재색인 (12 M 건 69 분).

## Consequences

### Positive

- **CRUD 안정성** — Lucene/Kafka 가 죽어도 게시글은 저장됨
- **양 서버 일관성** — CDC 가 결국 일관성 보장
- **재시작 복원** — Debezium 의 offset 추적으로 자동 따라잡기
- **NRT 검색** — Modulith 이벤트 경로로 서버 1 은 ms 단위 반영

### Negative

- **이중 운영 부담** — Kafka·Debezium 추가 운영 (Kafka 4.2 KRaft 모드로 ZooKeeper 회피로 일부 완화)
- **eventual consistency** — 서버 2 는 binlog → Kafka → 컨슈머 지연만큼 늦음 (현재 약 1~2 s)
- **멱등 책임** — 컨슈머가 같은 이벤트를 두 번 받을 가능성 대비 필요 (Lucene `updateDocument` 로 처리)

### Neutral

- 인덱스가 망가지면 어차피 전체 재색인 — `sync-index.sh` (rsync) 가 또 다른 백업 경로

## Validation

- **CRUD 응답 시간** — POST 49 ms 평균, P95 142 ms — Lucene 인덱싱이 트랜잭션과 분리되어 영향 없음 ([BENCHMARK_REPORT.md](../BENCHMARK_REPORT.md))
- **서버 1 ↔ 2 인덱스 지연** — Kafka consumer lag 메트릭으로 모니터링 (목표 < 5 s)
- **이벤트 처리 실패율** — Spring Modulith 이벤트 publication 테이블의 미완료 row 카운트 (Modulith 가 자동 재시도)
- **CDC offset 진행** — Prometheus `kafka_consumer_lag` 알람

만약 양 경로가 동시에 자주 깨지면 단일화 (CDC only) 검토 (`Supersedes #0005`).

## References

- 구현:
  - 이벤트 발행: `backend/src/main/java/com/wiki/engine/post/PostEvent.java`, `PostService.java`
  - 이벤트 핸들러: `backend/src/main/java/com/wiki/engine/post/internal/lucene/LuceneIndexEventHandler.java`
  - CDC 컨슈머: `backend/src/main/java/com/wiki/engine/post/internal/cdc/PostsCdcConsumer.java`
- Spring Modulith 이벤트: [공식 문서](https://docs.spring.io/spring-modulith/reference/events.html) — `@TransactionalEventListener` + Event Publication Registry
- Debezium MySQL Connector: [공식 문서](https://debezium.io/documentation/reference/stable/connectors/mysql.html)
- 다이어그램: [readme-images/cdc-flow.svg](../readme-images/cdc-flow.svg)
