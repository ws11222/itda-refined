# 잇다 (ITDA) — Backend Performance & Architecture Improvements

> Spring Boot 기반 공공복지 정책 추천 서비스의 백엔드를 외부 의존성 격리 / 데이터 액세스 최적화 관점에서 개선한 포트폴리오 프로젝트.

원 프로젝트는 서울대 SWPP 수업 팀 프로젝트(고령자 대상 공공복지 정책 추천 앱) 이며, 본 저장소는 그 백엔드 코드를 이력서/포트폴리오 목적으로 리팩토링·계측한 결과를 담는다.

---

## 헤드라인 결과

### 1. 외부 임베딩 서버 장애 격리 (Blast Radius 차단)

임베딩 서버 5초 지연 시 **무관한 `GET /my-profile` 응답**:

| 지표 | Before | After | 차이 |
|---|---|---|---|
| p50 | 4.01s | 7.1ms | 565× |
| **p95** | **5.04s** | **14ms** | **360×** |
| p99 | 5.05s | 38ms | 132× |
| 처리량 (req/min) | 93 | 300 | 3.2× |

→ 외부 의존성 장애가 시스템 전체로 전파되던 blast radius를 제거. ([PR #1](https://github.com/ws11222/itda-refined/pull/1) merged)

### 2. 추천 점수 계산 pgvector 푸시다운

피드 캐시 재생성 시 후보 정책 2163개 처리:

| 지표 | Before | After | 차이 |
|---|---|---|---|
| p50 | 1.37s | 72ms | 19× |
| **p95** | **2.04s** | **187ms** | **10.9×** |
| p99 | 2.28s | 481ms | 4.7× |
| 처리량 (req/s) | 4.9 | 15.1 | 3.1× |

→ 추천 점수 계산을 JVM dot product 에서 Postgres pgvector `<#>` 연산자로 푸시다운. ([PR #2](https://github.com/ws11222/itda-refined/pull/2))

> 모든 수치는 k6 부하 테스트로 동일 환경 (macOS, JDK 17, 단일 호스트, Mock embedding server) 에서 동일 시나리오를 before / after 두 jar 로 측정한 값. 상세한 시나리오 / SLO 정의 / 정당화는 [`load-test/README.md`](./load-test/README.md) 참고.

---

## 개선 요약

### PR #1 — Async Embedding Refresh & DB 커넥션 풀 보호

**문제**

```kotlin
@Transactional                              // DB 커넥션 점유
fun updateProfile(...) {
    embeddingService.getEmbedding(...)      // WebClient.block() ← 외부 응답 대기
                                            //   DB 커넥션 잡은 채로 수 초 대기
}
```

- DB 커넥션이 외부 응답 시간만큼 점유 → 풀(HikariCP 기본 10) 고갈 → 무관한 API 도 함께 응답 불가
- WebClient timeout 미설정 → 외부 서버 hang 시 스레드 무기한 블로킹
- 임베딩 실패 시 null 조용히 저장 → 손상된 임베딩이 영구화

**해결**

- 외부 I/O 를 트랜잭션 밖으로 분리한 `EmbeddingRefreshService` 신규 (`@Async` + `TransactionTemplate` 로 짧은 트랜잭션 2회로 쪼갬)
- `TransactionSynchronizationManager#afterCommit` 훅으로 비동기 트리거 → 커밋 전 race 회피
- Reactor Netty `HttpClient` 에 connect / response / read·write timeout 3종 명시 (`EmbeddingClientConfig`)
- `EmbeddingService.getEmbeddingOrThrow()` — null 대신 `EmbeddingUnavailableException` (503)

자세한 의사결정 과정 (옵션 A/B/C 비교, 의도적으로 안 한 것) 은 PR 본문 참조.

### PR #2 — pgvector SQL 푸시다운

**문제**

```kotlin
val programs = programRepository.findAllByUserInfo(userId)  // 2000+ ProgramEntity 전체 hydrate
programs.map { program ->
    dotProduct(program.embedding, preferenceEmbedding)      // JVM
    dotProduct(program.embedding, prefNoSeeLessEmbedding)   // JVM
    dotProduct(program.embedding, likesEmbedding)           // JVM
    dotProduct(program.embedding, bookmarksEmbedding)       // JVM
}
```

- 진짜 병목은 벡터 연산이 아닌 **DB → JVM 데이터 이동** (~8MB) 과 JPA hydration 비용

**해결**

```sql
SELECT
    p.id, p.category, p.title,
    (-(p.embedding <#> CAST(:pref AS vector)))::float8            AS score,
    (-(p.embedding <#> CAST(:prefNoSeeless AS vector)))::float8   AS denom,
    (-(p.embedding <#> CAST(:likes AS vector)))::float8           AS like_dot,
    (-(p.embedding <#> CAST(:bookmarks AS vector)))::float8       AS bookmark_dot
FROM program p
JOIN "user" u ON ...
ORDER BY score DESC
```

- 4개 inner product 를 **하나의 SQL 컬럼으로** 표현 → pgvector C 구현(SIMD) 이 처리
- 행당 페이로드 ~수 KB → 수십 bytes (95%↓), JPA hydration 제거

JVM 측에서는 keyword penalty 와 카테고리 그룹핑만 남김.

---

## 아키텍처

```
frontend/ (Android, Jetpack Compose)
    └→ backend/ (Spring Boot 3.5.6, Kotlin) ← 본 저장소의 주 개선 대상
           └→ embedding-server/ (FastAPI, BAAI/bge-m3, 1024-dim)
data-pipeline/ (Python, LangGraph ReAct) → DB 적재
```

### 백엔드 도메인 구조

| 패키지 | 역할 |
|---|---|
| `user/` | 사용자 프로필 / 임베딩 / 태그 |
| `program/` | 정책 데이터 / 검색 / 좋아요·북마크 |
| `feedCache/` | 추천 엔진 캐시 (1시간 TTL) |
| `embedding/` | 임베딩 서버 호출 클라이언트 |

### 추천 알고리즘 (현행)

```
preferenceEmbedding[i] = userEmb[i] * 0.3
                       + likesEmb[i] * 0.2
                       + bookmarksEmb[i] * 0.2
                       - dislikesEmb[i] * 0.3

score = programEmbedding · preferenceEmbedding
```

- 가중치 W_U=0.3 / W_L=W_B=0.2 / W_S=0.3
- 최근 10개 상호작용 임베딩 평균
- 키워드 필터 (장애, 탈북, 북한) — 태그 미보유 시 score -= 1.0
- 상위 10% 추천에 좋아요/북마크 기여도 기반 reason 텍스트 생성

---

## 빌드 & 실행

```bash
# 백엔드
cd backend && ./gradlew bootRun

# 임베딩 서버 (로컬)
docker compose up embedding-server -d

# data-pipeline
cd data-pipeline && pip install -r requirements.txt && python main.py --stage all

# 프론트엔드
# Android Studio 로 빌드
```

### 부하 테스트 재현

```bash
# 사전 조건: Docker Desktop, k6 (brew install k6), JDK 17+
bash load-test/scripts/run-all.sh        # before/after 각 5 시나리오 자동 실행
```

상세는 [`load-test/README.md`](./load-test/README.md). 시나리오별 SLO 정의, 수치 정당화, 결과 표 + 해석 모두 포함.

---

## 사용 기술

**Core**
- Kotlin / Spring Boot 3.5.6
- Spring Data JPA + Hibernate 6 (`hibernate-vector`)
- PostgreSQL 16 + [pgvector](https://github.com/pgvector/pgvector)
- Reactor Netty (`spring-boot-starter-webflux` 의 WebClient)

**Observability**
- Spring Boot Actuator + Micrometer Prometheus

**Load Testing**
- k6 (multiple executors: `constant-vus`, `shared-iterations`)
- FastAPI mock embedding server (fixed / lognormal random / outage 모드)
- Git worktree 기반 before/after JAR 자동 빌드

**Infrastructure**
- Docker Compose (격리된 5433 포트 DB, init 시 2163 program 자동 시드)

---

## 다음 작업 후보

- **HNSW 인덱스 추가** — 정책 수만 개 규모 대비 (`CREATE INDEX ... USING hnsw(embedding vector_ip_ops)`)
- **Program search 의 PG full-text search 푸시다운** — 비슷한 패턴
- **운영 환경 시뮬레이션** — `tc qdisc` 로 DB 네트워크 latency 주입
- **추천 알고리즘 자체 개선** — 2-stage (Candidate Retrieval + Learning-to-Rank)
- **그래프 시각화** — matplotlib 으로 결과 그래프 생성하여 README 임팩트 강화

---

## 원 프로젝트 정보

- 원 저장소: SNU SWPP 2025-2 Team 08 (Software Development Principles and Practice)
- 본 저장소: ws11222 (개인 포트폴리오 fork). 백엔드 코드 외 frontend / data-pipeline 은 원본 그대로.
