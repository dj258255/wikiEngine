# JVM / 메모리 튜닝 노트

> **대상**: wikiEngine (12M 문서, Lucene 단일 노드 임베드)
> **환경 가정**: ARM 2 vCPU / 12 GB RAM 단일 서버
>
> 이 문서는 **측정 결과 보고서가 아니라 튜닝 관점/방법론 정리**입니다. 어떤 항목을 어떻게 측정해야 하는지, 트레이드오프가 어디 있는지를 다룹니다. 구체적 수치는 환경마다 달라지므로 실측 시점에 채워 넣는 게 정직합니다.

목차:

- [1. 환경 컨텍스트](#1-환경-컨텍스트)
- [2. Baseline 관측 인프라](#2-baseline-관측-인프라)
- [3. Heap vs Page Cache 트레이드오프](#3-heap-vs-page-cache-트레이드오프)
- [4. GC 알고리즘 선택](#4-gc-알고리즘-선택)
- [5. Page Cache Cold/Warm](#5-page-cache-coldwarm)
- [6. Off-heap & Container Limit 산정](#6-off-heap--container-limit-산정)
- [7. Lucene 배치 인덱싱 OOM 해결 패턴](#7-lucene-배치-인덱싱-oom-해결-패턴)
- [8. 명령어 모음](#8-명령어-모음)

---

## 1. 환경 컨텍스트

수치는 환경 설명 없이는 해석할 수 없다. "Search p99 80 ms"가 12 코어 64 GB 에서 나온 것과 2 코어 12 GB 에서 나온 것은 의미가 완전히 다르다. 모든 측정은 환경과 함께 기록한다.

### 1.1 단일 서버 자원 배분

ARM 2 vCPU / 12 GB RAM 한 대에 다음 컨테이너가 공존하는 구성을 전제로 한다.

| 컨테이너 | 메모리 한도 | 역할 | 비고 |
|---|---|---|---|
| wiki-app-prod | 2 G | Spring Boot + Lucene | 튜닝 주 대상 |
| wiki-mysql-prod | 4 G | 12M 문서 + 검색 로그 | 데이터 무결성 — 축소 불가 |
| wiki-kafka-prod | 2 G | CDC 파이프라인 | 실험 중 일시 중단 가능 |
| wiki-redis-prod | 300 M | L2 캐시 + 자동완성 | 축소 불가 |
| wiki-alloy-prod | 512 M | 로그 수집 | 축소 가능 |
| wiki-cadvisor-prod | 256 M | 컨테이너 메트릭 | 필수 |
| wiki-nginx-prod | 256 M | 프록시 | 필수 |
| mysqld-exporter | 128 M | MySQL 메트릭 | 필수 |
| **합계** | **약 9.5 G** | | **실험용 여유 약 2.5 G** |

App 컨테이너를 6 G 로 증설하면 12 G 한도를 초과한다. 즉 **App heap 튜닝의 상한이 인프라 단위로 강제**된다 — "검색 성능을 위해 heap 크게" 같은 단순 답이 통하지 않는 환경.

### 1.2 JVM 기본 가정

- 시작 옵션 의도: `-Xms`/`-Xmx` 동일 설정으로 동적 확장 비용 제거
- GC log/NMT/HeapDumpOnOOM 옵션은 *처음에는 없었던* 상태에서 추가하는 흐름으로 다룬다 (관측 가능성 확보가 모든 튜닝의 1차 작업)

### 1.3 Lucene 인덱스 특성

| 항목 | 값 | 의미 |
|---|---|---|
| 문서 수 | 12,156,589 | 중간 규모 |
| 인덱스 크기 | 39 GB | 시스템 RAM(12 GB)보다 큼 — Page Cache 항상 부족 |
| Directory | `MMapDirectory` | OS Page Cache 직접 의존 |

**핵심 함의**: 인덱스(39 GB)가 RAM(12 GB)의 3배 이상이라 *전체 인덱스를 Cache 에 올릴 수 없다*. 따라서 "어떤 부분이 hot 인가"를 식별해서 그 부분만 우선 warm 하게 유지하는 전략이 의미가 있다.

### 1.4 부하 테스트 도구

- **k6** (`backend/load/search-load.js`)
- 시나리오: ramp-up 2 min → sustain 5 min (100 VU) → ramp-down 2 min
- 쿼리 풀: 한국어 일반 키워드 10 종 (자바, 스프링, 알고리즘 …)
- 출력: Grafana k6 대시보드 + JSON

---

## 2. Baseline 관측 인프라

### 2.1 정상 상태 / 문제

처음 상태는 **측정 자체가 안 되고 있는 상태**다. 운영 중인데:

- GC pause 분포 — `-Xlog:gc*` 없음
- Off-heap 사용량 — `NativeMemoryTracking` 비활성
- OOM 발생 시 원인 — `HeapDumpOnOutOfMemoryError` 없음

Prometheus 의 Micrometer JVM 메트릭이 있어도 GC pause 분포는 GC log 없이는 정밀 측정 불가.

### 2.2 대안 비교 — 어떻게 관측 인프라를 깔 것인가

| 접근 | 방법 | 장점 | 단점 |
|---|---|---|---|
| A. JMX 실시간 | JConsole, VisualVM | 즉시 시각화 | 지속적 기록 안 됨 |
| B. GC log + JFR | `-Xlog:gc*` + JFR | 지속적, 재현 가능 | 디스크 I/O 미미하게 증가 |
| C. APM (New Relic 등) | SaaS | 통합 관점 | 유료 |
| D. Prometheus 확장 | Micrometer | 기존 스택 | GC pause 세부는 한계 |

**선택**: B + D. 비용 0, 재현성 확보.

### 2.3 적용 — JVM 옵션

```yaml
java_opts: >-
  -Xms1g -Xmx1g
  -Xlog:gc*=info:file=/logs/gc-%t.log:time,level,tags:filecount=10,filesize=100M
  -XX:+HeapDumpOnOutOfMemoryError
  -XX:HeapDumpPath=/data/heapdump
  -XX:NativeMemoryTracking=summary
```

이 옵션 세트가 모든 후속 실험의 전제다. 측정 인프라 없이 튜닝하는 건 다른 비유를 찾기도 어려운, 그냥 추측이다.

---

## 3. Heap vs Page Cache 트레이드오프

### 3.1 왜 트레이드오프가 존재하는가

Lucene 은 `MMapDirectory` 로 segment 파일을 mmap. **mmap 된 영역은 JVM Heap 이 아니라 OS Page Cache** 가 관리한다. 검색은 inverted index 의 랜덤 접근이 많아 Page Cache hit/miss 가 latency 를 결정한다.

```
시스템 메모리 = JVM Heap + Off-heap (Metaspace 등) + Page Cache + OS
Page Cache    = 시스템 메모리 - (Heap + Off-heap + OS)
```

Heap 을 크게 잡을수록 Page Cache 영역이 줄어 검색이 느려지고, Heap 을 너무 작게 잡으면 GC 빈도가 늘어 응답이 느려진다.

### 3.2 후보 비교 (12 GB 단일 서버 기준)

| Heap | Container | Page Cache 여유(개략) | 예상 문제 |
|---|---|---|---|
| 512 MB | 1 G | ~10 GB | GC 과다, heap 부족 |
| **1 G** | **2 G** | **~9 GB** | 현재 baseline |
| 2 G | 4 G | ~7 GB | Heap 여유, Page Cache 충분 |
| 4 G | 6 G | ~5 GB | Page Cache 부족 가능성 |
| 8 G | 10 G | ~1 GB | Page Cache 심각 부족 |

12 GB 환경에서는 위 표의 8 G heap 케이스가 다른 컨테이너 자원과 충돌해 운영적으로 불가하다 — 실험 후보는 1 G / 2 G / 4 G.

### 3.3 ES 가이드 "절반 원칙" 의 함정

Elasticsearch 가이드는 *"시스템 메모리의 절반 이하를 Heap"* 을 권장한다. 그러나 이 원칙은 **인덱스가 RAM 안에 들어가는 시나리오** 를 가정한다.

이 프로젝트처럼 인덱스(39 GB)가 RAM(12 GB)의 3 배 이상이면 *어차피 Page Cache 가 부족*하므로, 가이드의 가정이 깨진다. 이 경우 "절반"보다 더 보수적인 *"Heap 최소화 + Hot segment 우선 warm-up"* 전략이 합리적이다.

### 3.4 비용 관점

OCI Free Tier 기준 비용 영향은 0. 그러나 동일 워크로드를 AWS 로 가져가면 `m7g.medium` (1 vCPU / 4 GB) ≈ 월 ₩24,000 부근. Heap 4 GB 가 필요하다고 잘못 결정하면 `m7g.large` 로 더블 비용. *튜닝의 비용 회수* 가 곧 클라우드 청구서.

---

## 4. GC 알고리즘 선택

### 4.1 GC 별 특성

**G1GC (region-based, mostly concurrent)**

- Heap 을 region(기본 16 MB)으로 분할, Young/Old 혼재
- **Humongous object**: region 의 50% 초과 객체는 Eden 우회 → Old 로 직접 할당. Young GC 로 회수 안 됨, fragmentation 유발
- Lucene segment merge 에서 큰 `byte[]` 가 humongous 가 되기 쉬움

**ZGC Generational (JDK 21+)**

- Colored pointer + concurrent compaction
- Pause time 이 heap 크기와 거의 무관 (sub-ms ~ 수 ms)
- CPU 오버헤드는 G1 대비 약간 높음 (concurrent 작업 비중↑)
- ARM 안정성은 JDK 21 에서 안정화

**Shenandoah (RedHat)**

- ZGC 와 유사한 concurrent compaction
- ARM 환경 안정성은 경우별로 검증 필요

### 4.2 비교 매트릭스

| GC | 적합 용도 | 검색 서비스 적합도 |
|---|---|---|
| Parallel | Throughput 우선 배치 | 부적합 (검색은 latency 우선) |
| G1GC | 범용 균형 | 기본값으로 합리적 |
| **ZGC Generational** | 초저지연 / p99 중시 | 검증해볼 가치 큼 |
| Shenandoah | 범용 | ARM 환경 별도 검증 필요 |

### 4.3 실험 시 비교 항목

- p50 / p95 / p99 검색 latency
- GC pause 분포 (`-Xlog:gc*` 파싱)
- GC throughput overhead %
- **Humongous allocation 빈도** — G1 에서만 의미 있음, Lucene segment merge 와 연관

---

## 5. Page Cache Cold/Warm

### 5.1 동작 원리

- mmap 된 페이지는 **접근 시점에 lazy load** (demand paging)
- OS 가 LRU 기반으로 evict — 메모리 압박 시 자주 안 쓰이는 페이지부터 제거
- **프로세스 재시작 시 Page Cache 는 유지** (OS 가 보존) — 단, OS 재부팅 / `echo 3 > drop_caches` / `vmtouch -e` 등으로 초기화 가능

### 5.2 Warm-up 전략 비교

| 방법 | 효과 | 단점 |
|---|---|---|
| A. 자연 warm-up | Page Cache 자연 채움 | 초기 트래픽의 latency 가 나쁨 |
| B. 관리자 쿼리 스크립트 | 빈번 키워드 위주 | 스크립트 유지보수 |
| C. `vmtouch -t` 전체 | 완전 warm | 39 GB 디스크 I/O — 시간/대역폭 |
| D. **Priority segment pre-load** | 검색 critical 파일만 | 구현 |

### 5.3 Priority Segment Pre-load

Lucene 파일 중 검색 hot path 는 다음 확장자:

| 확장자 | 의미 |
|---|---|
| `.tim` | term dictionary |
| `.tip` | term index |
| `.doc` | postings |
| `.fdt` / `.fdx` | stored fields |

이 확장자만 `vmtouch -t` 로 미리 로드하면 전체 39 GB 대신 hot subset 만 warm 으로 유지 가능.

---

## 6. Off-heap & Container Limit 산정

### 6.1 JVM 프로세스 RSS 구성

```
RSS = Heap (committed)
    + Metaspace (클래스 메타)
    + Thread stacks (스레드당 ~1 MB 기본)
    + Direct ByteBuffer (NIO)
    + JIT code cache (~256 MB 한도)
    + GC 내부 자료구조
    + Other (mmap 파일 — Lucene 이 크게 기여)
```

**핵심**: `-Xmx` 는 Heap 만 제한한다. **Container OOMKilled ≠ Java OutOfMemoryError**. Off-heap 이 컨테이너 한도를 넘으면 OS 가 SIGKILL — 이 경우 heap dump 자체가 안 남는다.

### 6.2 Container Limit 산정 방식 비교

| 방식 | 공식 | 평가 |
|---|---|---|
| A. Heap × 1.0 | Heap = Limit | 위험 — OOMKilled 빈발 |
| B. Heap × 1.5 | Heap 2 G → Limit 3 G | 보수적, 안전 |
| C. Heap × 2.0 | Heap 2 G → Limit 4 G | 안전하지만 낭비 |
| D. **실측 기반** | Heap + 실측 Off-heap + 20% 여유 | **정확** |

### 6.3 NMT 측정 카테고리

`jcmd <pid> VM.native_memory summary` 로 다음 카테고리별 Reserved/Committed 확인:

- Java Heap
- Class (Metaspace)
- Thread
- Code (JIT)
- GC (내부 자료구조)
- Compiler / Internal / Symbol / NMT
- Other (mmap — Lucene)

핵심 관찰점: Heap 크기에 따라 Off-heap 이 **비례하는가 / 독립적인가** — 비례 항목(Thread, GC) 과 독립 항목(Metaspace, JIT) 이 섞여 있다.

---

## 7. Lucene 배치 인덱싱 OOM 해결 패턴

### 7.1 메모리 특성

배치 인덱싱이 heap 을 압박하는 구조:

- `IndexWriter.ramBufferSizeMB` — segment 생성 전 in-memory 버퍼
- 스레드별 `IndexWriter$ThreadState` (각 약 40 MB 수준)
- 가공 중 Document 객체 누적 (GC 대상이지만 일시적으로 살아있음)
- Segment merge 시 임시 buffer

### 7.2 흔한 OOM 원인 3 가지

1. `ramBufferSizeMB` 가 Heap 의 50% 이상으로 커서 다른 작업 여유가 없음
2. `maxThreadStates` × 스레드별 점유량 합이 Heap 초과
3. 배치 크기가 너무 커서 Document 객체 일시 누적이 과다

### 7.3 해결책 비교

| 방법 | 위험도 | 검증 항목 |
|---|---|---|
| A. Heap 늘리기 | 낮음 | Off-heap 영향, Page Cache 잠식 |
| B. `ramBufferSizeMB` 축소 (예: 256) | 낮음 | 인덱싱 throughput 영향 |
| C. `maxThreadStates` 축소 (코어 수에 맞춤) | 낮음 | concurrency 영향 |
| D. 배치 크기 축소 + flush 주기 단축 | 중간 | throughput vs heap peak 트레이드오프 |

리소스 제약 환경(이 프로젝트)에서는 A 가 불가, **B + C + D 조합** 이 일반적으로 가장 깔끔.

### 7.4 Heap Dump 분석 흐름

OOM 발생 시:

1. `-XX:+HeapDumpOnOutOfMemoryError` 가 설정돼 있어야 dump 가 남는다
2. Eclipse MAT 의 **Leak Suspects Report** — 자동 탐지 (false positive 주의)
3. **Dominator Tree** — "X 를 해제하면 Y MB 회수 가능" 을 명확히 보여줌. Leak Suspects 보다 진단력이 높음
4. **Histogram** — 클래스별 인스턴스 수/크기. 비정상 인스턴스 카운트 탐지

핵심: Dominator Tree 의 top 5 를 본인 도메인 객체(IndexWriter, Document, Term 등)와 매칭해서 어디서 retained heap 이 큰지 짚는다.

---

## 8. 명령어 모음

### 8.1 JVM 측정

```bash
# Native Memory Tracking
docker exec wiki-app-prod jcmd 1 VM.native_memory summary

# GC 통계 (1초 간격)
docker exec wiki-app-prod jstat -gc 1 1000

# JVM info
docker exec wiki-app-prod jcmd 1 VM.info

# Heap 상태
docker exec wiki-app-prod jcmd 1 GC.heap_info

# Heap dump 즉시 생성
docker exec wiki-app-prod jcmd 1 GC.heap_dump /tmp/manual.hprof
```

### 8.2 시스템 측정

```bash
# 메모리 전반
free -m

# 컨테이너 RSS
docker stats wiki-app-prod --no-stream

# 프로세스 별 메모리
cat /proc/$(docker inspect wiki-app-prod --format '{{.State.Pid}}')/status \
  | grep -E "VmRSS|VmSize|VmData"

# Page Cache 히트 측정 (인덱스 경로)
vmtouch /data/lucene/wiki-index
```

### 8.3 GC 로그 분석

```bash
# GCViewer (로컬 GUI)
gcviewer ./gc.log

# gceasy.io (웹 업로드, 무료)
#   https://gceasy.io/
```

---

## 관련 문서

- 시스템 전체 구조: [ARCHITECTURE.md](./ARCHITECTURE.md)
- 기술 결정의 배경: [adr/](./adr/README.md)
- 로컬 개발 환경 / JVM 진단 명령: [DEVELOPMENT.md](./DEVELOPMENT.md)
