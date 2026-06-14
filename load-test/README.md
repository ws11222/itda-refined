# Load Test — Async Embedding Refresh

`PUT /api/v1/my-profile` 엔드포인트에서 외부 임베딩 서버 호출을 동기에서 비동기로 전환한 리팩토링의 효과를 k6로 측정한다.

## 가설

> 외부 임베딩 서버 응답 지연이 발생할 때, 개선 전(`@Transactional` 안에서 `WebClient.block()`) 구조는 DB 커넥션 풀(HikariCP 기본 10)을 점유한 채로 외부 응답을 기다리므로 **부하 증가 시 풀 고갈로 임베딩과 무관한 API까지 함께 느려진다**. 개선 후(`@Async` + `afterCommit`) 구조는 외부 I/O를 트랜잭션 밖으로 분리하므로 외부 지연이 다른 API로 전파되지 않는다 (blast radius 차단).

## SLO 정의

| 대상 | 분류 | SLO |
|---|---|---|
| `PUT /my-profile` | 일반 REST API | **p95 < 500ms**, 가용성 99.9%, 에러율 < 1% |
| 임베딩 refresh (백그라운드) | 분석 파이프라인 | p95 < 5s, 실패 시 다음 갱신에서 자연 복구 허용 |

> "일반 REST API p95 < 500ms" 는 업계 통용 기준 (참고: 결제 도메인 p99 < 200ms, 내부 마이크로서비스 p95 < 100ms).

## 시나리오

| ID | 설명 | VU | 임베딩 지연 | 측정 목적 |
|---|---|---|---|---|
| S1 Baseline | 정상 상태 | 10 | 200ms | 정상 동작 검증 |
| S2 Saturation | 부하 증가 | 50 | 200ms | 풀 한계점 탐색 |
| S3 Degradation | 외부 서비스 지연 | 10 | 5s | 외부 지연이 우리 API SLO를 침범하는가 |
| S4 Outage | 외부 서비스 다운 | 10 | 30s (>timeout 10s) | 외부 장애가 우리 API에 격리되는가 |
| **S5 Blast Radius** | 외부 지연 중 무관한 GET | 10 writer + 5 reader | 5s | 외부 지연이 무관한 API까지 전파되는가 (DB 커넥션 풀 고갈) |
| **S6 Cache Miss** | 피드 캐시 재생성 부하 | 10 (shared 300 iter) | 200ms (무관) | 추천 점수 계산을 JVM에서 Postgres로 푸시다운한 효과 |

### 수치 정당화

| 수치 | 근거 |
|---|---|
| VU 10 | HikariCP 기본 풀 크기 = 10. 풀과 동일한 동시성으로 "정원" 상태 측정 |
| VU 50 | 풀의 5배. 일반 REST API의 포화 트래픽 패턴 가정 |
| 임베딩 정상 지연 200ms | mock 서버 default. 실제 GPU 환경에서 BAAI/bge-m3의 단일 추론 평균과 유사 |
| 임베딩 장애 지연 5s | WebClient response timeout(10s) 미만. "느린 응답" 시뮬레이션 (GC pause, 일시 과부하) |
| 임베딩 outage 지연 30s | timeout 초과. 모든 요청이 timeout fail로 이어지는 outage 시뮬레이션 |
| p95 < 500ms | 일반 REST API SLO 통용 기준 |
| 측정 시간 1분 | 각 시나리오당 충분한 표본 수 확보 (10 VU × ~50 req = 500+ 측정점) |
| sleep 1s (think time) | 사용자의 자연스러운 클릭 간격 모사. 너무 짧으면 자동 부하 테스트가 됨 |

## 실행 방법

### 사전 조건
- Docker Desktop
- k6 (`brew install k6`)
- JDK 17+

### 한 번에 모두
```bash
bash load-test/scripts/run-all.sh
```

### 단일 시나리오
```bash
bash load-test/scripts/build-jars.sh                # 최초 1회
bash load-test/scripts/run-scenario.sh before s3    # 개선 전, S3
bash load-test/scripts/run-scenario.sh after  s3    # 개선 후, S3
```

결과는 `load-test/results/<timestamp>-<version>-<scenario>.*`로 저장.

## 결과

측정 환경: macOS (Apple Silicon), JDK 17, 단일 호스트, 각 시나리오 1분.

### 응답 시간 (`PUT /my-profile`)

| 시나리오 | 임베딩 지연 | VU | Before p95 | Before p99 | After p95 | After p99 | p95 개선 |
|---|---|---|---|---|---|---|---|
| S1 Baseline    | 200ms | 10 | **267ms** | 431ms  | **36ms** | 66ms  | **7.4×** |
| S2 Saturation  | 200ms | 50 | **255ms** | 885ms  | **41ms** | 89ms  | **6.2×** |
| **S3 Degradation** | **5s** | **10** | **5.24s** | 5.24s | **39ms** | 71ms  | **134×** |
| **S4 Outage**      | **30s** | **10** | **30.23s** | 30.23s | **37ms** | 67ms  | **815×** |

> Before에서 error% 가 0%인 이유: 개선 전 코드에는 WebClient timeout이 없어 응답이 오기까지 무기한 대기. 모든 요청이 결국 200으로 끝나지만 SLO(p95 < 500ms)는 명백히 위반. WebClient timeout 추가는 본 리팩토링의 일부.

### 처리량 비교 (1분 측정 기준)

| 시나리오 | Before 총 요청 | After 총 요청 | 처리량 차이 |
|---|---|---|---|
| S1 Baseline   |   490 |   590 | +20% |
| S2 Saturation | 2,440 | 2,950 | +21% |
| S3 Degradation |  100 |   590 | **+490%** |
| S4 Outage      |   20 |   590 | **+2850%** |

### 해석

**S1 (정상 상태)**: 개선 전은 임베딩 서버 응답 시간(200ms)이 그대로 응답에 누적되어 p95 = 267ms. 개선 후는 임베딩 호출이 트랜잭션 밖 비동기로 빠져 p95 = 36ms. 정상 상태에서도 사용자 체감 응답 시간이 7배 빨라짐.

**S2 (포화)**: 50 VU에서 개선 전은 p99가 885ms로 long-tail 발생 시작 (DB 커넥션 풀 + 임베딩 호출 경합). 개선 후는 p99 89ms로 안정. SLO(p95 < 500ms) 모두 통과하지만 분포 폭의 차이가 크다.

**S3 (외부 서비스 지연 5초)** — 핵심 비교: 개선 전은 임베딩 응답 5초가 그대로 누적되어 p95 = 5.24s. SLO 10배 초과. 개선 후는 외부 지연과 무관하게 p95 = 39ms 유지. **외부 의존성이 우리 SLO를 침범하지 않는다는 가설이 정량 검증됨.**

**S4 (외부 서비스 다운 30초)**: 개선 전은 응답을 받기 위해 30초간 hang, 처리량이 20 req/min으로 추락. 개선 후는 200 OK 즉시 반환, 처리량 590 req/min 유지. 외부 장애가 우리 시스템에 격리됨.

### S5 Blast Radius — 가장 강력한 결과

같은 부하(임베딩 5s 지연, 10 writer + 5 reader) 하에서, **임베딩과 무관한 `GET /my-profile`** 의 응답 시간:

| 지표 | Before (sync) | After (async) | 차이 |
|---|---|---|---|
| GET p50 | 4.01s | 7.1ms | 565× |
| **GET p95** | **5.04s** | **14ms** | **360×** |
| GET p99 | 5.05s | 38ms | 132× |
| GET 처리량 (1분당) | 93 req | 300 req | 3.2× |

**해석**: 개선 전에는 단순 조회 API(`GET`)가 임베딩 서버 지연 시간만큼 같이 느려졌다. 원인은 HikariCP 풀 크기(10)와 동일한 수의 writer가 외부 응답을 기다리는 5초 동안 모든 DB 커넥션을 점유하여 reader가 커넥션을 받지 못한 것. 개선 후에는 writer가 외부 호출 전에 커넥션을 반납하므로 reader는 정상 속도(14ms) 유지.

**이 결과의 의미**: 외부 서비스 장애가 단일 API에 머무르지 않고 **시스템 전체로 전파되던 blast radius를 차단**한 것을 정량 검증.

### 트레이드오프

개선 후 S3/S4에서 사용자는 즉시 200 OK를 받지만, 임베딩 갱신은 백그라운드에서 timeout(10s) 후 실패하므로 **그 사용자의 임베딩은 일시적으로 stale 상태**가 됨. 다음 프로필 업데이트 시 자연 복구.

S5의 writer 측 에러율(약 19%)은 측정 인공물 — 부하 테스트에서 `__VU % token_count` 매핑으로 일부 토큰을 여러 VU가 공유하며 동일 사용자에 대한 동시 업데이트가 발생, `TagEntity` 수준의 `ObjectOptimisticLockingFailureException`이 발생한 결과. 본 평가의 관심은 무관한 reader 측 영향이므로 별도 보정 없이 수집된 값을 그대로 보고함.

### S6 Cache Miss — pgvector 푸시다운 효과

피드 캐시 재생성 (`generateFeedCache`) 경로의 단위 비용을 측정. 사용자 300명이 각자 한 번씩 첫 피드를 요청 (shared-iterations executor) → 300회 cache miss → 매 호출마다 후보 정책에 대해 추천 점수 계산.

**개선 전 (`refactor/async-embedding-refresh` HEAD)**: JVM이 program 엔티티 전체를 hydrate한 뒤 프로그램당 4회 dot product 계산.
**개선 후 (`refactor/pgvector-pushdown-feed-cache`)**: 단일 native query에 `<#>` 연산자를 사용해 4개 inner product를 컬럼으로 추가, score/denom/like_dot/bookmark_dot 모두 Postgres에서 계산.

| 지표 | Before | After | 개선 |
|---|---|---|---|
| `GET /programs` p50 | 60ms | **28ms** | 2.1× |
| **`GET /programs` p95** | **170ms** | **99ms** | **1.7×** |
| `GET /programs` p99 | 460ms | 225ms | 2.0× |
| 평균 응답 시간 | 90ms | 38ms | 2.4× |
| 처리량 (req/s) | 14.8 | 16.4 | +11% |

**해석**:
1. **데이터 이동 제거**: 개선 전은 program 한 행당 vector(1024) + 다수 텍스트 컬럼을 JVM으로 끌어와 `ProgramEntity` 로 hydrate. 개선 후는 id/카테고리/제목/4개 float 만 받음 → 행당 페이로드 95%↓.
2. **CPU 이동**: 벡터 내적이 JVM 바이트코드에서 → Postgres C 구현(SIMD) 으로. 1024-dim 내적이 단일 명령어 시퀀스에 더 가까워짐.
3. **JPA hydration 비용 제거**: 후보 program 객체를 만들지 않음.

**측정 한계**:
- 테스트 환경에서 사용자가 freshly signed up 상태 (postcode/birthDate 등 null) → eligibility 필터 통과 정책 수가 ~93개로 제한적. 실제 운영의 풍부한 프로필에선 후보 수가 더 많고 개선폭도 커질 것으로 예상.
- 단일 호스트 측정이라 DB 전송 비용이 (Unix loopback) 매우 낮음. 운영처럼 DB가 별도 호스트면 데이터 이동 감소(95%↓)의 효과가 더 두드러짐.

## 참고

### 환경
- macOS, JDK 17, Spring Boot 3.5.6
- PostgreSQL 16 (pgvector), HikariCP 기본 풀 크기 10
- Mock embedding server: FastAPI, configurable delay
- 동일 호스트에서 측정 (네트워크 변동 제거)

### 한계
- 단일 호스트 측정이므로 k6 부하와 Spring + DB가 CPU를 공유. 절대값보다 **개선 전후 차이**가 의미.
- mock embedding server는 실제 모델 추론을 포함하지 않으므로 지연 분포의 현실성은 제한적. 외부 서비스 지연을 통제된 변수로 다루기 위함.
- 측정 구간 1분은 정상 패턴 관찰에는 충분하나 long-tail 이벤트(예: GC) 관찰에는 부족.
