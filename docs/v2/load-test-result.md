# V2 비관적 락 부하 테스트 결과

## 실험 개요

- 실험일: 2026-08-31
- 대상 구현: `coupon.service.v2.V2CouponIssueService`
- 부하 도구: k6
- 애플리케이션: Spring Boot 4.1.1, Hibernate ORM 7.4.5
- 데이터베이스: MariaDB 12.3
- 실행 환경: 애플리케이션, MariaDB, k6를 동일한 Windows 로컬 환경에서 실행
- 테스트 스크립트: `load-tests/k6/coupon-issue-concurrency.js`

목적은 V1에서 발생한 스냅샷 충돌과 HTTP 500을 비관적 락으로 제거하고,
직렬화에 따른 응답시간과 시스템 병목을 관찰하는 것이다.

확인할 데이터 불변식은 다음과 같다.

```text
issue_count <= total_quantity
issue_count = total_quantity - remaining_quantity
remaining_quantity >= 0
```

## 구현 확인

단일 발급 요청에서 다음 SQL 흐름을 확인했다.

```text
coupon INSERT
-> coupon SELECT ... FOR UPDATE
-> coupon_issue 중복 확인 SELECT
-> coupon_issue INSERT
-> coupon UPDATE
-> COMMIT
```

`coupon_issue INSERT`가 `coupon UPDATE`보다 먼저 출력된 것은 Hibernate flush 순서에 따른 것이다.
두 SQL은 같은 트랜잭션에서 실행되며 실패하면 함께 롤백된다.

동일한 사용자로 순차 재요청했을 때 HTTP 409와 `COUPON_010` 중복 발급 오류가 발생했다.
두 번째 요청에서는 중복 확인 이후 발급 이력 INSERT와 쿠폰 UPDATE가 실행되지 않았다.

## 유효 실험: 재고 100개, 요청 100건

### 조건

```text
total_quantity: 100
새로 생성한 발급 가능 쿠폰
VUs: 100
총 요청: 100
서로 다른 userId 사용
```

CLI의 `--vus 100` 옵션으로 100 VU와 100 shared iterations를 실행했다.
이번 실행에서는 중복 충돌 없이 100개의 서로 다른 사용자 요청이 처리됐다.

### HTTP 결과

| 항목 | 결과 |
|---|---:|
| `coupon_issue_created` | 100 |
| `coupon_issue_conflict` | 0 |
| `coupon_issue_unexpected` | 0 |
| `http_reqs` | 100 |
| `iterations` | 100 |
| `http_req_failed` | 0.00% |
| 처리율 | 251.36 req/s |
| 평균 응답시간 | 210.73ms |
| 중앙값 | 212.56ms |
| p90 | 334.42ms |
| p95 | 343.19ms |
| 최대 응답시간 | 355.24ms |

V1의 동일한 100명 실험은 18건 성공, 82건 HTTP 500, p95 868.07ms였다.
V2에서는 100건이 모두 정상 발급돼 V1의 가용성 문제를 제거했다.

### DB 사후 검증

이번 실행에서 HTTP 201은 100건 확인했지만 다음 DB 집계값은 별도로 보존하지 않았다.
V3 비교 전에 동일 SQL로 V2와 V3의 사후 상태를 모두 기록한다.

```sql
SELECT
    c.id,
    c.total_quantity,
    c.remaining_quantity,
    COUNT(ci.id) AS issue_count,
    c.total_quantity
        - c.remaining_quantity
        - COUNT(ci.id) AS consistency_gap
FROM coupon c
LEFT JOIN coupon_issue ci
    ON ci.coupon_id = c.id
WHERE c.id = :couponId
GROUP BY
    c.id,
    c.total_quantity,
    c.remaining_quantity;
```

## 진단 실험: 재고 100개, 1,000 VU 순간 요청

### 첫 실행

| 항목 | 결과 |
|---|---:|
| `coupon_issue_created` | 100 |
| `coupon_issue_conflict` | 517 |
| `coupon_issue_unexpected` | 383 |
| `http_reqs` | 1,000 |
| `iterations` | 1,000 |
| 평균 응답시간 | 1.12s |
| p95 | 2.12s |

이 실행에서는 unexpected 응답의 상태와 네트워크 오류 원문을 별도로 보존하지 않아
V2 애플리케이션 성능 결과에서 제외한다.

### 재현 실행

| 항목 | 결과 |
|---|---:|
| `coupon_issue_created` | 100 |
| `coupon_issue_conflict` | 120 |
| `coupon_issue_unexpected` | 780 |
| `http_reqs` | 1,000 |
| `iterations` | 1,000 |
| 중단된 iteration | 0 |
| 평균 응답시간 | 252.32ms |
| p95 | 1.25s |

k6 로그에서 unexpected 요청의 원인을 확인했다.

```text
Request Failed
dial tcp 127.0.0.1:8080
connectex: No connection could be made because the target machine actively refused it.
```

애플리케이션 로그에는 다음 결과가 남았다.

```text
서버 ERROR: 0건
SnapshotIsolationException 또는 ER_CHECKREAD: 0건
Lock Wait timeout: 0건
Hikari connection timeout: 0건
SOLD_OUT 처리: 120건
```

따라서 이 실행의 780건은 Spring MVC 또는 V2 Service가 반환한 HTTP 500이 아니다.
1,000개의 순간 TCP 연결 중 일부가 애플리케이션에 도달하기 전에 로컬 HTTP 진입 구간에서 거절됐다.
테스트 직후 `127.0.0.1:8080` TCP 연결은 정상이어서 서버 프로세스가 종료된 것은 아니었다.

이 결과는 V2 DB 전략의 p95 기준으로 사용하지 않고 HTTP Connector 및 로컬 실행 환경의
수용 한계를 드러낸 별도 진단 결과로 기록한다.

## 결과에서 제외한 실행

다음 실행은 V2 비관적 락의 성능 기준에서 제외한다.

- 이미 발급 이력이 있거나 매진된 쿠폰을 재사용해 100건 모두 `409 Conflict`가 발생한 실행
- 1,000 VU 중 383건의 unexpected 원문을 보존하지 않은 첫 실행
- 1,000 VU 중 780건이 TCP 연결 단계에서 거절된 재현 실행
- `spring.jpa.show-sql=true`로 대량 SQL을 출력한 실행은 최종 성능 비교에서 제외

## 테스트 도구 보완 사항

현재 k6 스크립트는 다음 한계가 있다.

- 모든 HTTP 409를 하나의 `conflict`로 집계해 `SOLD_OUT`과 `DUPLICATE_ISSUE`를 구분하지 못한다.
- HTTP 상태가 없는 네트워크 오류와 HTTP 500을 모두 `unexpected`로 집계한다.
- `discardResponseBodies=true`여서 오류 코드를 검증하지 못한다.
- CLI `--vus`는 기존 scenario를 shared iterations로 대체하므로 사용자 ID 분배를 엄격히 보장하지 않는다.

V3 비교 전 다음 지표를 분리한다.

```text
201 CREATED
409 SOLD_OUT
409 DUPLICATE_ISSUE
HTTP 500
status 0 또는 network error
완료 iteration
중단 iteration
```

VU 수와 최대 실행 시간도 환경변수로 전달하되 `per-vu-iterations` executor를 유지해
각 VU가 고유한 `userId`로 정확히 한 번 요청하도록 만든다.

## 결과

- SQL의 `FOR UPDATE`를 확인해 비관적 쓰기 락 적용을 검증했다.
- 순차 중복 발급은 HTTP 409로 차단됐다.
- 새 쿠폰에 대한 100 VU 테스트에서 100건 모두 발급됐고 HTTP 500은 없었다.
- V1의 100 VU 실험과 비교해 성공 건수와 p95가 모두 개선됐다.
- 100 VU에서도 동일 쿠폰 행의 직렬화로 p95 343.19ms가 측정됐다.
- 1,000 VU 순간 요청은 애플리케이션 내부 예외보다 TCP 연결 거절이 먼저 발생했다.
- 1,000건 전체에 대한 `100 Created + 900 SOLD_OUT + unexpected 0` 목표는 아직 달성하지 못했다.

## 판단과 V3 진입

V2는 V1에서 발생한 스냅샷 충돌과 HTTP 500을 100 VU 조건에서 제거해 정합성과 가용성을 개선했다.
반면 하나의 쿠폰 행을 발급 트랜잭션 전체 동안 잠가 요청을 직렬화한다.
100 VU에서 p95 343.19ms가 측정됐으며 높은 순간 동시성에서는 HTTP 진입 구간의 수용 한계도 관찰됐다.

다음 가설을 검증하기 위해 V3 Atomic UPDATE로 진행한다.

```text
조건부 UPDATE 한 번으로 재고를 감소시키면
SELECT FOR UPDATE와 트랜잭션의 락 보유 시간을 줄여
동일한 부하에서 V2보다 p95와 처리량을 개선할 수 있다.
```

V3 비교에서는 다음 조건을 고정한다.

- 동일한 로컬 실행 환경과 데이터베이스
- 매 실행마다 새 쿠폰 생성
- 동일한 재고, VU, 요청 수와 사용자 ID 생성 방식
- `spring.jpa.show-sql=false`
- 워밍업 후 반복 실행
- HTTP 상태, 네트워크 오류, DB 사후 상태를 분리 기록

V2의 1,000 VU 목표가 미달했다는 사실은 유지하며, V3가 같은 HTTP 진입 조건에서
연결 거절까지 줄이는지도 함께 비교한다.
