# wikiEngine Documentation

이 디렉토리는 wikiEngine 의 **설계 의도**와 **운영 노하우**를 기록합니다.

> 빠르게 시작하려면 루트의 [README.md](../README.md) 부터 보세요. 이 문서들은 "왜 이렇게 만들었는가"를 깊이 다룹니다.

---

## 문서 인덱스

### 아키텍처

| 문서 | 다루는 내용 |
|---|---|
| [**ARCHITECTURE.md**](./ARCHITECTURE.md) | C4 Model 경량 — Context → Container → Component 3단 줌. 시스템 전체 그림. |
| [**adr/**](./adr/README.md) | Architecture Decision Records — 핵심 기술 결정 7건의 *why* |

### 개발 / 운영

| 문서 | 다루는 내용 |
|---|---|
| [**DEVELOPMENT.md**](./DEVELOPMENT.md) | 로컬 개발 환경 셋업, 테스트, 디버깅, JVM 진단 |
| [**JVM_MEMORY_NOTES.md**](./JVM_MEMORY_NOTES.md) | JVM/메모리 튜닝 관점 정리 — Heap vs Page Cache, GC 비교, Off-heap, Lucene OOM 패턴 |

---

## 어떻게 읽어야 하나요?

처음 방문한 사람을 위한 추천 동선입니다.

1. [루트 README](../README.md) — 프로젝트가 무엇이고, 무엇을 다루는지 5분
2. [ARCHITECTURE.md](./ARCHITECTURE.md) — 시스템 전체 구조 한 눈에 10분
3. [adr/README.md](./adr/README.md) — 관심 가는 결정 1~2건 픽업, 각 5분
4. [DEVELOPMENT.md](./DEVELOPMENT.md) — 직접 실행해보고 싶다면

---

## 문서 작성 규칙

- **ADR은 append-only**: 결정이 바뀌면 기존 ADR을 수정하지 말고 새 번호로 작성 후 `Supersedes #XXX` 로 링크
- **다이어그램**: 가능하면 텍스트 (Mermaid) 우선, 복잡한 그림은 [readme-images/](./readme-images/) 에 SVG
- **언어**: 한국어가 기본, 코드 식별자/명령어/외래 기술 용어는 원어 그대로
