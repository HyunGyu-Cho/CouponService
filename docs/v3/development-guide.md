# V3 Atomic UPDATE Development Guide

상태: 완료. 구현, 자동 테스트, k6 부하 실험과 V2 대비 수치 비교를 마쳤다. 실험 수치는 [V3 부하 테스트 결과](load-test-result.md)에 있다.
공통 규칙은 [공통 개발 가이드](../development-guide.md)를 따른다.

## 문제

V2 비관적 락은 100 VU 조건에서 HTTP 500을 제거했지만, 쿠폰 행 잠금을 발급 트랜잭션 전체 동안 유지해 요청을 직렬화한다.
100 VU에서 p95 343.19ms가 측정됐고 락 보유 구간 동안 DB 커넥션도 함께 점유된다. 자세한 수치는 [V2 부하 테스트 결과](../v2/load-test-result.md)에 있다.

## 가설

```text
remaining_quantity > 0 조건을 포함한 UPDATE 한 번으로 재고를 감소시키면
명시적인 SELECT FOR UPDATE와 긴 락 보유 구간을 줄일 수 있다.
그 결과 동일 부하에서 V2보다 p95와 처리량이 개선될 수 있다.
```

V3도 같은 쿠폰 행을 갱신하므로 경합 자체가 사라지지는 않는다. V2와 같은 조건에서 수치로 비교한 뒤 판단한다.

## 변경

### 발급 흐름

```text
@Transactional(READ COMMITTED) 시작
-> 쿠폰 존재 확인
-> 중복 발급 확인 (coupon_issue 조회)
-> 조건부 UPDATE: remaining_quantity > 0 AND 발급 기간 안이면 1 감소
-> 갱신 행 수가 0이면 재고를 바꾸지 않고 원인만 판별해 오류 응답
-> CouponIssue 저장 (Coupon은 getReferenceById 참조)
-> 커밋
```

V2와 비교하면 쿠폰 행 잠금이 "SELECT FOR UPDATE부터 커밋까지"에서 "UPDATE부터 커밋까지"로 줄어든다. 중복 확인과 존재 확인은 잠금 밖에서 끝난다.

### Repository

`decreaseRemainingQuantityIfIssuable()`은 `@Modifying(clearAutomatically = true, flushAutomatically = true)`를 쓴다.
UPDATE 뒤 영속성 컨텍스트를 비워, 실패 원인 판별용 `findById()`가 옛 값이 아닌 DB 최신 값을 읽게 하기 위해서다.

### 엔티티 책임 분리

`Coupon.validateIssuable()`은 검증만 하고 상태를 바꾸지 않는다. `Coupon.issue()`만 잔여 수량을 감소시킨다.
V1과 V2는 `issue()`를 호출하고, V3는 재고 감소를 DB가 맡으므로 실패 원인 판별에 `validateIssuable()`만 사용한다.
이 분리 덕분에 V3의 판별 경로에서 재고가 이중 감소하지 않는다.

### 트랜잭션 격리 수준을 READ COMMITTED로 낮춘다

구현 중 재현한 문제다. 기본 격리 수준(REPEATABLE READ)에서 동시성 테스트를 돌리면 300건 중 265건이 다음 오류로 실패했다.

```text
JpaSystemException: Record has changed since last read in table 'coupon'; try restarting transaction
[update coupon ... set remaining_quantity = remaining_quantity - 1 where id = ? and remaining_quantity > 0 ...]
```

원인은 MariaDB의 스냅샷 격리(`innodb_snapshot_isolation`, 11.6부터 기본 ON)다. 트랜잭션 안의 첫 SELECT(쿠폰 존재 확인)가 스냅샷을 만들고, 그 뒤 다른 트랜잭션이 쿠폰 행을 갱신하면 이 트랜잭션의 UPDATE가 "읽은 뒤 바뀐 행"으로 거부된다. V1에서 `coupon_issue` INSERT가 실패한 것과 같은 계열의 문제이며, 이번에는 `coupon` 행에서 났다.

V3의 정합성은 스냅샷이 아니라 조건부 UPDATE(재고)와 UNIQUE 제약(중복)이 보장한다. 따라서 REPEATABLE READ가 필요 없고, `@Transactional(isolation = Isolation.READ_COMMITTED)`로 낮춰 문제를 없앴다.
V2는 첫 문장이 SELECT FOR UPDATE라 스냅샷이 잠금 이후에 만들어지므로 이 문제가 없다. 격리 수준 변경은 V3 발급 트랜잭션에만 적용하며 다른 조회에는 영향이 없다.

### 초안에서 고친 문제

2026-09-14 점검에서 발견한 4건을 모두 반영했다.

1. `validateIssuable()`이 재고를 감소시키던 것을 검증 전용으로 분리
2. 중복 확인이 UPDATE 뒤에 있던 순서를 앞으로 이동
3. `@Modifying`에 `clearAutomatically` 추가
4. JPQL 파라미터 이름 오타 `isseudAt` 수정

## 결과

### 자동 테스트

`src/test`에 세 버전이 같은 시나리오를 도는 동시성 테스트를 두었다. 재고 100개, 서로 다른 사용자 300명, 32 스레드로 동시에 발급을 요청한다.

| 버전 | 불변식 (발급 수 == 전체 - 잔여, 초과 발급 없음) | 정확히 100장 발급 | 예상 밖 예외 | 초과 요청 200건의 응답 |
|---|---|---|---|---|
| V1 | 유지 | 아니오 (실행마다 30~40장) | 다수 (스냅샷 충돌) | 확인 안 함 |
| V2 | 유지 | 예 | 0건 | 전부 SOLD_OUT |
| V3 | 유지 | 예 | 0건 | 전부 SOLD_OUT |

V1은 문서화된 기준선이므로 불변식만 검증한다. V2와 V3는 정합성과 가용성을 모두 검증한다.
V3 순차 시나리오(중복, 매진, 기간 전후, 없는 쿠폰)에서 재고가 변하지 않고 정확한 오류 코드가 나오는 것도 `V3CouponIssueServiceTest`로 확인했다.

### 부하 실험

2026-09-15에 V2와 V3를 같은 환경에서 번갈아 측정했다. 상세 수치와 조건은 [V3 부하 테스트 결과](load-test-result.md)에 있다.

100 VU, 재고 100, 예열 후 실행:

| 항목 | V3 (2회) | V2 (예열 후 1회) |
|---|---:|---:|
| 발급 성공 | 100 / 100 | 100 |
| HTTP 500, 네트워크 오류 | 0 | 0 |
| p95 | 273~310ms | 485ms |
| 처리율 | 292~331 req/s | 191 req/s |
| `consistency_gap` | 0 | 0 |

1,000 VU 순간 요청 (각 1회):

| 항목 | V3 | V2 |
|---|---:|---:|
| 서버 도달 / TCP 연결 거절 | 401 / 599 | 336 / 664 |
| 도달 요청의 처리 | 100 발급 + 301 매진 | 100 발급 + 236 매진 |
| HTTP 500 | 0 | 0 |
| `consistency_gap` | 0 | 0 |

1,000 VU에서는 두 구현 모두 로컬 HTTP 진입 구간의 연결 수용 한계가 먼저 드러났다. 서버에 도달한 요청 수가 달라 응답시간은 비교하지 않는다.

## 판단

가설은 확인됐다. 100 VU 조건에서 V3는 V2보다 p95가 약 1.6~1.8배 빠르고 처리율이 약 1.5~1.7배 높으며, 정합성과 가용성은 V2와 같은 수준이다.

복잡도 증가는 감수할 가치가 있다.

- 늘어난 것: 조건부 UPDATE JPQL 하나, 갱신 0건일 때 원인을 판별하는 경로, V3 트랜잭션의 격리 수준 변경 한 줄.
- 줄어든 것: 명시적 `SELECT FOR UPDATE`와 트랜잭션 전체에 걸친 행 잠금. 잠금 구간이 UPDATE부터 커밋까지로 짧아졌다.
- 정합성의 근거가 "잠금 순서"에서 "UPDATE 조건과 UNIQUE 제약"으로 옮겨져, 코드만 읽어도 무엇이 초과 발급을 막는지 드러난다.

한계도 분명하다.

- 같은 쿠폰 행에 대한 경합 자체는 남아 있다. 요청이 늘수록 DB의 행 갱신 대기가 길어지며, 이는 V4 Redis 원자 연산이 다룰 문제다.
- 높은 순간 동시성에서는 DB 전략과 무관하게 HTTP 진입 구간이 먼저 포화된다. 이 한계는 Rate Limit과 Virtual Waiting Room 단계의 주제다.
- Lock Wait와 커넥션 풀 점유 수치는 수집하지 않았다. 100 VU에서 오류가 없어 판단에 필요하지 않았지만, DB 경합을 다시 다룰 때 Actuator 지표로 함께 기록한다.

V3를 실제 서비스에서도 유지할 만한 동기 발급의 기준 구현으로 삼고, `v3-atomic-update` 태그로 보존한 뒤 V4로 진행한다.
