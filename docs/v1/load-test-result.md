# V1 부하 테스트 결과

## 실험 개요

- 실험일: 2026-08-31
- 대상 구현: `coupon.service.v1.V1CouponIssueService`
- 부하 도구: k6
- 데이터베이스: MariaDB 12.3
- 테스트 스크립트: `load-tests/k6/coupon-issue-concurrency.js`
- 목적: 락이 없는 V1 발급 흐름에서 동시성 문제와 데이터 불변식 위반 여부를 확인한다.

확인할 데이터 불변식은 다음과 같다.

```text
issue_count <= total_quantity
issue_count = total_quantity - remaining_quantity
remaining_quantity >= 0
```

각 요청은 서로 다른 `userId`를 사용해 동일 사용자 중복 발급이 아니라 동일 쿠폰의 재고 경합을 관찰한다.

## 1차 실험: 재고 100개, 1,000명 동시 요청

### 조건

```text
total_quantity: 100
VUs: 1,000
iterations per VU: 1
총 요청: 1,000
```

### HTTP 결과

| 항목 | 결과 |
|---|---:|
| `coupon_issue_created` | 100 |
| `coupon_issue_conflict` | 149 |
| `coupon_issue_unexpected` | 751 |
| `http_reqs` | 1,000 |
| `http_req_failed` | 90.00% |
| 처리율 | 275.55 req/s |
| 평균 응답시간 | 1.77s |
| p95 | 3.42s |

### DB 결과

| `total_quantity` | `remaining_quantity` | `issue_count` | `consistency_gap` |
|---:|---:|---:|---:|
| 100 | 0 | 100 | 0 |

DB에 저장된 결과만 보면 불변식은 유지됐다. 그러나 751건이 예상하지 못한 응답으로 끝났으므로 이 실행의 응답시간과 처리율은 V1 성능 기준으로 사용할 수 없다.

## 2차 진단 실험: 재고 100개, 100명 동시 요청

매진된 기존 쿠폰을 재사용한 실행은 모든 요청이 `409 Conflict`로 종료되어 결과에서 제외했다. 수량 100인 새 쿠폰을 생성한 뒤 다시 실행했다.

### 조건

```text
total_quantity: 100
VUs: 100
iterations per VU: 1
총 요청: 100
```

### HTTP 결과

| 항목 | 결과 |
|---|---:|
| `coupon_issue_created` | 18 |
| `coupon_issue_conflict` | 0 |
| `coupon_issue_unexpected` | 82 |
| `http_reqs` | 100 |
| `http_req_failed` | 82.00% |
| 처리율 | 105.67 req/s |
| 평균 응답시간 | 730.85ms |
| p95 | 868.07ms |

재고와 요청 수가 모두 100이므로 정상 기대 결과는 `201 Created` 100건과 예상하지 못한 오류 0건이다. 실제로는 18건만 성공하고 82건이 HTTP 500으로 실패했다.

이 실행의 DB 사후 값은 아직 기록하지 않았다. 다음 재현 시 `remaining_quantity`, `issue_count`, `consistency_gap`을 함께 기록한다.

## 문제

락이 없는 V1 발급 흐름에서 동일 쿠폰에 동시 요청이 들어오면 다수의 트랜잭션이 `coupon_issue` 저장 단계에서 실패한다. 100명 동시 요청에서 82%가 HTTP 500으로 종료돼 정상 발급 가능한 요청도 처리하지 못했다.

이번 실험에서는 초과 발급이나 음수 재고 같은 데이터 불변식 위반보다 트랜잭션 충돌과 높은 서버 오류율이 먼저 나타났다.

## 가설 및 원인 확인

애플리케이션 로그에서 다음 예외와 실패 SQL을 확인했다.

```text
MariaDB ER_CHECKREAD 1020
Record has changed since last read in table 'coupon_issue';
try restarting transaction

org.hibernate.exception.SnapshotIsolationException

insert into coupon_issue (coupon_id, issued_at, user_id)
values (?, ?, ?)
```

예외는 `V1CouponIssueService.issueCoupon()`의 `couponIssueRepository.save()`에서 발생했다. MariaDB 예외를 Hibernate가 `SnapshotIsolationException`으로 변환하고, Spring이 `JpaSystemException`으로 전달한다. 현재 `GlobalExceptionHandler`는 이를 예상하지 못한 예외로 처리해 HTTP 500을 반환한다.

현재 발급 흐름은 다음 순서다.

```text
Coupon 조회
-> coupon_issue 중복 여부 조회
-> Coupon 재고 감소
-> coupon_issue INSERT
-> 트랜잭션 커밋
```

여러 트랜잭션이 락 없이 같은 쿠폰에 대해 위 흐름을 실행하면서, 앞서 만든 읽기 스냅샷 이후 다른 트랜잭션이 변경한 `coupon_issue` 레코드 또는 인덱스와 충돌한 것으로 판단한다. MariaDB는 충돌한 트랜잭션을 롤백하므로 데이터 불변식은 유지될 수 있지만 요청 가용성은 크게 떨어진다.

실행 환경의 격리 설정은 다음 SQL로 추가 확인한다.

```sql
SHOW VARIABLES LIKE 'innodb_snapshot_isolation';
SHOW VARIABLES LIKE 'tx_isolation';
```

## 변경

없음. 이 결과는 비관적 락, 재시도, Atomic UPDATE 또는 DB 격리 설정 변경을 적용하지 않은 V1 기준선이다.

원인을 재현하고 기록하기 전 V1 동작을 변경하지 않았다.

## 결과

- 100명 동시 요청에서 18건 성공, 82건 HTTP 500이 발생했다.
- 실패 원인은 `coupon_issue` INSERT 중 발생한 MariaDB `ER_CHECKREAD 1020` 스냅샷 충돌이다.
- 충돌한 트랜잭션은 롤백되므로 확인한 1차 DB 결과에서는 불변식이 유지됐다.
- 데이터 정합성 위반은 확인되지 않았지만, 높은 오류율로 인해 V1은 동시 발급 요청을 안정적으로 처리하지 못한다.
- 오류 응답이 대량 포함된 현재 TPS와 응답시간은 최종 성능 기준으로 사용하지 않는다.

## 판단

V1의 동시성 문제는 재현됐다. 문제의 형태는 초과 발급보다 스냅샷 충돌에 의한 대량의 HTTP 500이다. 따라서 쿠폰 행을 발급 흐름 시작 시점에 잠가 트랜잭션을 직렬화하는 V2 비관적 락을 적용하고 동일한 스크립트로 비교할 근거가 있다.

V2에서는 다음 결과를 목표로 한다.

```text
재고 100개, 요청 100건
- 201 Created: 100
- unexpected: 0
- issue_count: 100
- remaining_quantity: 0
- consistency_gap: 0

재고 100개, 요청 1,000건
- 201 Created: 100
- 409 SOLD_OUT: 900
- unexpected: 0
- issue_count: 100
- remaining_quantity: 0
- consistency_gap: 0
```

비관적 락 적용 후에는 정확성뿐 아니라 평균 응답시간, p95, p99, 처리율, DB Lock Wait와 Connection Pool 점유도 함께 비교한다.

## 결과에서 제외한 실행

다음 실행은 애플리케이션 동시성 결과가 아니므로 기준 데이터에서 제외한다.

- 애플리케이션이 8080 포트에서 실행되지 않아 1,000건 모두 연결 거절된 실행
- DB 초기화 후 존재하지 않는 `couponId`를 사용해 모든 요청이 `404 Not Found`로 종료된 실행
- 이미 매진된 쿠폰을 재사용해 100건 모두 `409 Conflict`로 종료된 실행
