# 0006. 2단 캐시 (Caffeine + Redis) + Consistent Hashing 3-Shard

- **상태**: Accepted
- **결정일**: 2026-02-15
- **관련 ADR**: [#0002](./0002-autocomplete-cqrs-mapreduce.md), [#0007](./0007-nginx-rw-split-mysql-replication.md)

## Context

검색 쿼리는 비싸다 (Lucene + XGBoost re-rank + MySQL hydrate). 그러나 **인기 검색어는 소수의 키워드에 집중** 되어 있다 (long-tail 분포). 따라서 캐시 효과가 크다.

요구사항:

- 캐시 hit 시 P50 < 50 ms
- 캐시 miss 시에도 P95 < 3 s (전체 검색)
- 단일 캐시 노드 장애가 전체 서비스에 영향 주지 않게
- 12 GB 머신 메모리 안에서 동작

분산 환경 특수성:

- 서버 1, 2 양쪽에서 검색 처리 — 같은 키에 대해 둘 다 캐시하면 메모리 낭비
- 그러나 네트워크 RTT (1~5 ms) 도 응답에 가산

## Decision Drivers

1. **응답 시간** (캐시 hit P50 < 50 ms)
2. **메모리 효율** (12 GB 안에서)
3. **장애 격리** — 단일 Redis 노드 죽어도 전체 정지 안 되게
4. **확장성** — 노드 추가 시 키 재배치 최소화

## Considered Options

### Option A: 단일 Redis

- 장점: 가장 단순, 메모리 한 곳에 집중
- 단점:
  - **단일 실패점**
  - 네트워크 호출 매번 발생 — 캐시 hit 도 1~5 ms
  - 한 노드의 maxmemory 가 전체 캐시 용량 상한

### Option B: Caffeine 단독 (in-process L1)

- 장점: 0 ms 네트워크, 가장 빠름
- 단점:
  - **서버 1, 2 가 독립 캐시** — 같은 키를 양쪽이 따로 저장 → 메모리 낭비 + warm-up 비대칭
  - Caffeine 크기 제한 (수만 엔트리 정도) — long-tail 흡수 불가

### Option C: Redis Cluster (공식 클러스터)

- 장점: 표준 분산, 자동 reshard
- 단점:
  - **운영 부담** — gossip protocol, slot 관리, failover
  - 12 GB 머신에서 클러스터 노드 여러 개 띄우면 메모리 압박
  - 키 자동 분배가 우리 워크로드(자동완성 prefix 등)에 항상 최적은 아님

### Option D: Caffeine L1 + Redis L2 + Client-side Consistent Hashing 3-Shard (선택)

```
[L1 — Caffeine in-process, 서버별]
maxSize=10000, TTL=60s
  ↓ miss
[L2 — Redis, Consistent Hashing 3 shards]
shard1, shard2, shard3 — Jedis / Lettuce client routes
  ↓ miss
Lucene 검색
```

- 장점:
  - L1 hit = 0 ms 네트워크 (자주 요청되는 키)
  - L2 hit = 1~5 ms 네트워크 (long-tail 흡수)
  - **3-shard 분산** — 한 노드 죽어도 다른 키들은 살아 있음 (1/3 캐시 손실)
  - **Consistent Hashing + virtual nodes (150)** → 노드 추가/제거 시 키의 1/N 만 재배치
- 단점:
  - L1 캐시 일관성 문제 — 서버 1 의 L1 만 stale 일 수 있음 (TTL 짧게 두어 완화)
  - 클라이언트 라우팅 로직 직접 구현

## Decision

**Option D — Caffeine L1 + Redis L2 + Consistent Hashing 3-Shard** 선택.

### 왜 Consistent Hashing?

3-shard 단순 모듈로(`key.hashCode() % 3`)도 분배는 가능하지만, 나중에 4-shard 로 늘리면 **거의 모든 키가 다른 노드로** 재배치된다. Consistent Hashing 은 각 노드에 hash ring 의 일부 구간을 할당, 노드 추가 시 1/N 의 키만 옮긴다.

Virtual node 150 개를 도입해 hash ring 의 분포 편향을 완화 (수학적으로 σ 가 √N 에 반비례).

### 왜 2단?

- 자주 검색되는 인기 키워드 (head): L1 에서 처리 → 0 ms
- 가끔 검색되는 long-tail: L2 에서 처리 → 1~5 ms
- L1 만으로는 long-tail 흡수 불가 (메모리 한계), L2 만으로는 인기 키 응답이 1~5 ms 단조롭게 깔림

## Consequences

### Positive

- **캐시 hit율 ~82%** (실측, [README.md](../../README.md) Key Features)
- **장애 격리** — 1 샤드 다운 시 1/3 키만 영향
- **확장 용이** — 4번째 샤드 추가 시 약 1/4 키만 재배치 (vs 단순 모듈로의 거의 100%)
- **메모리 효율** — Caffeine 은 작게(per server 수십 MB), Redis 는 샤드별로 적당히

### Negative

- **L1-L2 일관성** — 다른 서버의 L1 은 invalidation 메시지 받지 못함. 짧은 TTL(60 s) 로 완화.
- **클라이언트 라우팅 로직** 직접 운영 — `RedisShardConfig`, Consistent Hashing 구현체 유지보수
- **virtual node 수 튜닝** — 150 이 너무 많으면 메모리, 너무 적으면 편향. 워크로드 따라 조정

### Neutral

- 자동완성도 이 3-shard 위에 올라감 — prefix 가 키이므로 자연스럽게 분산 ([ADR-0002](./0002-autocomplete-cqrs-mapreduce.md))

## Validation

- **캐시 hit율 ≥ 80%** — Caffeine + Redis 합산 기준, 현재 82% (충족)
- **샤드 키 분포 편차 < 10%** — `INFO keyspace` 로 각 샤드 키 수 비교
- **검색 P95 < 3 s** — 캐시 miss 비율 고려, 충족 (2.61 s)
- **노드 1대 강제 다운 후 P95 변화** — 1/3 키만 miss → Lucene 까지 가더라도 P95 유지 확인 (DR drill)

만약 hit율이 60% 아래로 떨어지면 TTL/maxSize 재조정 또는 캐시 키 설계 재검토.

## References

- 구현:
  - L1: `backend/src/main/java/com/wiki/engine/config/CaffeineConfig.java`
  - L2: `backend/src/main/java/com/wiki/engine/config/RedisShardConfig.java`
  - Consistent Hashing: `ConsistentHashRouter.java` (virtual nodes 150)
- Caffeine: [공식](https://github.com/ben-manes/caffeine)
- Consistent Hashing 원논문: David Karger et al., 1997 — [Consistent Hashing and Random Trees](https://dl.acm.org/doi/10.1145/258533.258660)
- 다이어그램: [readme-images/consistent-hashing.svg](../readme-images/consistent-hashing.svg)
