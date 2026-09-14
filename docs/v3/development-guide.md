# V3 Atomic UPDATE Development Guide

상태: 구현과 자동 테스트 완료. k6 부하 실험과 V2 대비 수치 비교는 아직이며 `docs/v3/load-test-result.md`가 생기면 "결과"와 "판단"을 갱신한다.
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

아직 수행하지 않았다. V2와 같은 조건(재고 100, 100 VU와 1,000 VU, 새 쿠폰, `show-sql=false`)으로 k6를 돌리고 `docs/v3/load-test-result.md`에 기록한다.

## 판단

정합성과 가용성은 V2와 같은 수준임을 자동 테스트로 확인했다.
"V2보다 빠른가"는 부하 실험 전까지 판단하지 않는다. p95, 처리량, Lock Wait, 커넥션 점유를 비교한 뒤 이 절을 채우고 `v3-atomic-update` 태그를 만든다.
