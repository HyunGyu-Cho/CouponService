# V3 Atomic UPDATE 부하 테스트 결과

## 실험 개요

- 실험일: 2026-09-15
- 대상 구현: `coupon.service.v3.V3CouponIssueService`, 비교 대상 `coupon.service.v2.V2CouponIssueService`
- 부하 도구: k6 v2.2.0
- 애플리케이션: Spring Boot 4.1.1
- 데이터베이스: MariaDB 12.3
- 실행 환경: 애플리케이션, MariaDB, k6를 동일한 Windows 로컬 환경에서 실행
- 테스트 스크립트: `load-tests/k6/coupon-issue-concurrency.js` (응답 원인별 분리 집계 버전)
- 실행 방법: README "부하 테스트 실행"

목적은 V2 비관적 락과 V3 조건부 Atomic UPDATE를 같은 날, 같은 환경, 같은 조건에서 측정해
"V3가 V2보다 p95와 처리량을 개선하는가"를 수치로 판단하는 것이다.

## 고정한 조건

V2 문서에서 예고한 대로 다음을 고정했다.

- 매 실행마다 재고 100의 새 쿠폰을 생성하고 발급 기간 안에 실행
- `per-vu-iterations` executor로 VU마다 고유한 `userId`로 정확히 한 번 요청
- `spring.jpa.show-sql=false`
- 구현 전환은 `COUPON_ISSUE_VERSION` 환경변수로만 하고 코드와 DB 스키마는 동일
- 각 구현에서 애플리케이션 시작 후 워밍업 1회를 돌린 뒤 기록
- HTTP 상태와 오류 코드, 네트워크 오류, DB 사후 상태를 분리 기록

DB 사후 상태는 다음 SQL로 확인했다. `consistency_gap`은 0이어야 한다.

```sql
SELECT c.id, c.total_quantity, c.remaining_quantity,
       COUNT(ci.id) AS issue_count,
       c.total_quantity - c.remaining_quantity - COUNT(ci.id) AS consistency_gap
FROM coupon c
LEFT JOIN coupon_issue ci ON ci.coupon_id = c.id
WHERE c.id = :couponId
GROUP BY c.id, c.total_quantity, c.remaining_quantity;
```

## 유효 실험: 재고 100개, 100 VU

각 구현을 2회씩 실행했다. V2 1회차는 애플리케이션 재시작 직후 워밍업 없이 실행한 값이라 비교에서 제외하고 참고로만 남긴다.

### HTTP 결과

| 항목 | V3 1회차 | V3 2회차 | V2 1회차 (예열 전, 제외) | V2 2회차 |
|---|---:|---:|---:|---:|
| 쿠폰 ID | 33 | 35 | 36 | 37 |
| `coupon_issue_created` | 100 | 100 | 100 | 100 |
| 그 외 모든 집계 | 0 | 0 | 0 | 0 |
| `http_req_failed` | 0.00% | 0.00% | 0.00% | 0.00% |
| 처리율 | 292.16 req/s | 330.63 req/s | 130.36 req/s | 191.39 req/s |
| 평균 응답시간 | 179.31ms | 230.82ms | 578.17ms | 312.53ms |
| 중앙값 | 164.65ms | 230.06ms | 582.56ms | 350.34ms |
| p90 | 305.06ms | 271.23ms | 698.95ms | 465.53ms |
| p95 | 309.67ms | 273.42ms | 723.13ms | 485.01ms |
| 최대 응답시간 | 322.54ms | 278.20ms | 747.47ms | 503.50ms |

### DB 사후 검증

| 쿠폰 ID | 구현 | `total_quantity` | `remaining_quantity` | `issue_count` | `consistency_gap` |
|---:|---|---:|---:|---:|---:|
| 33 | V3 | 100 | 0 | 100 | 0 |
| 35 | V3 | 100 | 0 | 100 | 0 |
| 36 | V2 | 100 | 0 | 100 | 0 |
| 37 | V2 | 100 | 0 | 100 | 0 |

### 비교

예열된 실행끼리 비교하면 V3가 V2보다 빠르다.

```text
p95:    V3 273~310ms  vs  V2 485ms   -> V3가 약 1.6~1.8배 빠름
처리율: V3 292~331/s   vs  V2 191/s   -> V3가 약 1.5~1.7배 높음
정합성: 4회 모두 consistency_gap 0, HTTP 500 0건, 네트워크 오류 0건
```

V2의 8월 31일 측정값(p95 343.19ms)과 오늘의 V2 2회차(485.01ms)는 같은 코드인데도 차이가 크다.
로컬 환경의 날짜별 편차가 이 정도이므로, 구현 간 비교는 반드시 같은 날 같은 세션에서 번갈아 측정한 값으로만 한다.

## 진단 실험: 재고 100개, 1,000 VU 순간 요청

각 구현을 1회씩 실행했다. 이번에는 스크립트가 네트워크 오류를 분리 집계하므로 V2 실험에서 "unexpected"로만 남았던 780건의 정체를 확인할 수 있다.

### HTTP 결과

| 항목 | V3 (쿠폰 34) | V2 (쿠폰 38) |
|---|---:|---:|
| `coupon_issue_created` | 100 | 100 |
| `coupon_issue_sold_out` | 301 | 236 |
| `coupon_issue_network_error` | 599 | 664 |
| `coupon_issue_server_error` | 0 | 0 |
| `coupon_issue_unexpected` | 0 | 0 |
| 서버에 도달한 요청 | 401 | 336 |
| `http_reqs` | 1,000 | 1,000 |
| 완료 / 중단 iteration | 1,000 / 0 | 1,000 / 0 |
| 도달 요청의 평균 응답시간 | 591.36ms | 256.88ms |
| 도달 요청의 p95 | 686.50ms | 445.87ms |

"도달 요청" 수치는 k6의 `{ expected_response:true }` 하위 집계다. 전체 `http_req_duration`은 즉시 거절된 요청이 0ms로 섞여 평균이 왜곡되므로 사용하지 않는다.

### DB 사후 검증

| 쿠폰 ID | 구현 | `total_quantity` | `remaining_quantity` | `issue_count` | `consistency_gap` |
|---:|---|---:|---:|---:|---:|
| 34 | V3 | 100 | 0 | 100 | 0 |
| 38 | V2 | 100 | 0 | 100 | 0 |

### 해석

- 두 구현 모두 서버 오류 0건, 정합성 위반 0건이다. 서버에 도달한 요청은 전부 정상 발급 또는 매진으로 처리됐다.
- 네트워크 오류 599건과 664건은 모두 `status 0`, 즉 TCP 연결 단계에서 거절된 요청이다. V2 문서의 가설대로 병목은 DB 전략이 아니라 로컬 HTTP 진입 구간의 순간 연결 수용 한계다.
- 서버에 도달한 요청 수가 실행마다 다르므로(401 vs 336) 두 구현의 응답시간을 직접 비교할 수 없다. V3가 더 많은 요청을 받아 같은 쿠폰 행에 더 높은 경합을 겪은 결과 p95가 높게 나온 것으로 볼 수 있지만, 1회 실행으로는 단정하지 않는다.
- V3가 연결 거절 자체를 줄이지는 못했다. 이는 예상된 결과이며, 연결 수용량은 이후 단계(Rate Limit, Virtual Waiting Room)의 주제다.

## 측정하지 않은 것

- DB Lock Wait, Hikari 커넥션 풀 active/pending 수치는 이번 실행에서 수집하지 않았다. 100 VU에서 두 구현 모두 오류 0건이라 병목 진단 없이도 판단이 가능했으나, 이후 단계에서 DB 경합을 다룰 때 Actuator 지표로 함께 기록한다.
- 1,000 VU는 각 1회만 실행했다. 반복 측정 전까지 응답시간 비교 근거로 쓰지 않는다.

## 결과에서 제외한 실행

- 쿠폰 ID 자리에 문자열을 넣어 100건 모두 400 `INVALID_REQUEST`로 끝난 실행 (스크립트의 unexpected 집계가 동작함을 확인한 부수 효과)
- V3 워밍업 (쿠폰 32)과 V2 1회차 (쿠폰 36, 재시작 직후)

## 결과

- 100 VU에서 V3는 V2보다 p95가 약 1.6~1.8배 빠르고 처리율이 약 1.5~1.7배 높다.
- 6회 실행 모두 발급 100건, `consistency_gap` 0, HTTP 500 0건이다.
- 1,000 VU에서 두 구현 모두 600건 안팎이 TCP 연결 단계에서 거절됐고, 도달한 요청은 전부 정상 처리됐다.
- V2 실험에서 원인 미상이던 "unexpected"는 분리 집계로 네트워크 오류임이 확인됐다.

## 판단

V3의 가설 "조건부 UPDATE 한 번으로 락 보유 구간을 줄이면 V2보다 p95와 처리량이 개선된다"는 100 VU 조건에서 수치로 확인됐다.
정합성은 V2와 같은 수준을 유지했고, 복잡도 증가는 JPQL UPDATE 하나와 실패 원인 판별 경로, 격리 수준 변경 한 줄이다.
같은 쿠폰 행에 대한 경합 자체는 남아 있으며, 높은 순간 동시성에서는 HTTP 진입 구간이 먼저 포화된다. 최종 판단은 [V3 개발 가이드](development-guide.md)의 "판단"에 적는다.
