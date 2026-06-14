# Load Test — Async Embedding Refresh

`PUT /api/v1/my-profile` 엔드포인트에서 외부 임베딩 서버 호출을 동기에서 비동기로 전환한 리팩토링의 효과를 k6로 측정한다.

## 가설

> 외부 임베딩 서버 응답 지연이 발생할 때, 개선 전(`@Transactional` 안에서 `WebClient.block()`) 구조는 DB 커넥션 풀(HikariCP 기본 10)을 점유한 채로 외부 응답을 기다리므로 부하 증가 시 풀 고갈로 가용성이 무너진다. 개선 후(`@Async` + `afterCommit`) 구조는 외부 I/O를 트랜잭션 밖으로 분리하므로 동일 조건에서 가용성과 응답 시간을 유지한다.

## SLO 정의

| 대상 | 분류 | SLO |
|---|---|---|
| `PUT /my-profile` | 일반 REST API | **p95 < 500ms**, 가용성 99.9%, 에러율 < 1% |
| 임베딩 refresh (백그라운드) | 분석 파이프라인 | p95 < 5s, 실패 시 다음 갱신에서 자연 복구 허용 |

> "일반 REST API p95 < 500ms" 는 업계 통용 기준 (참고: 결제 도메인 p99 < 200ms, 내부 마이크로서비스 p95 < 100ms).

## 시나리오

| ID | 설명 | VU | 임베딩 지연 | 측정 목적 |
|---|---|---|---|---|
| **S1 Baseline** | 정상 상태 | 10 | 200ms | 정상 동작 검증 |
| **S2 Saturation** | 부하 증가 | 50 | 200ms | 풀 한계점 탐색 |
| **S3 Degradation** | 외부 서비스 지연 | 10 | 5s | **핵심 비교** — 외부 지연이 우리 SLO를 침범하는가 |
| **S4 Outage** | 외부 서비스 다운 | 10 | 30s (>timeout 10s) | **핵심 비교** — 외부 장애를 격리하는가 |

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

> 측정 후 채워 넣을 것.

### 응답 시간 (`PUT /my-profile`)

| 시나리오 | Before p95 | Before error% | After p95 | After error% | 개선 |
|---|---|---|---|---|---|
| S1 Baseline | TBD | TBD | TBD | TBD | TBD |
| S2 Saturation | TBD | TBD | TBD | TBD | TBD |
| **S3 Degradation** | **TBD** | **TBD** | **TBD** | **TBD** | **TBD** |
| **S4 Outage** | **TBD** | **TBD** | **TBD** | **TBD** | **TBD** |

### 해석 (작성 예정)

- S1: 정상 상태에서 두 구조의 응답 시간은 사실상 동일해야 함 (개선의 비용이 0이라는 증거).
- S2: 풀 크기 초과 시 동기 구조에서 latency 증가 시작.
- S3: 동기 구조의 p95가 5s+ 로 폭발, 개선 후는 200 OK 즉시 반환으로 SLO 유지.
- S4: 동기 구조는 timeout(10s) 후 503 발생, 개선 후는 200 OK 반환 + 백그라운드 stale 발생.

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
